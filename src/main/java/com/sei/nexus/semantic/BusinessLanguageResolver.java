package com.sei.nexus.semantic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.QuestionKeywords;
import com.sei.nexus.enterprise.DataColumn;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.enterprise.ValueDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Business Language Resolution (PRO-31; architecture PRO-30).
 *
 * <p>A deterministic, provenance-tagged annotation layer that runs once per
 * question at the top of context assembly. It resolves question tokens against
 * a per-tenant <b>Resolution Index</b> derived entirely from existing metadata
 * — Operational Vocabulary, entity names + bindings, Value Domain values, and
 * column names of entity-bound tables — and produces a {@link ResolvedQuestion}
 * whose two outputs are retrieval token expansion and the RESOLUTIONS prompt
 * block. Nothing is ever substituted into the question string.
 *
 * <p>Design invariants (all from PRO-30, cited by section):
 * <ul>
 *   <li><b>Derived, not stored</b> (§3.2): the index is built per question from
 *       existing tables only; no new metadata store.</li>
 *   <li><b>Referents only</b> (§3.2): every target points at an existing
 *       vocabulary row, entity binding, persisted domain value, or column.</li>
 *   <li><b>Deterministic matching</b> (§3.2): the existing normalization family
 *       (exact + singular/plural + separator-insensitive) extended with n-grams
 *       up to {@value #MAX_NGRAM} words. No fuzzy/embedding matching.</li>
 *   <li><b>Precedence</b> (§2): company &gt; pack (&gt; universal, which is the
 *       model and is never emitted). First-match-wins within a tier; conflicting
 *       tiers resolve silently in favor of company; ambiguity (one surface, two
 *       kinds) lists both — the model chooses from the offered set (§3.4).</li>
 *   <li><b>Bounded</b> (§3.2): at most {@code nexus.blr.max-resolutions}
 *       (default 8) resolutions per question.</li>
 *   <li><b>Zero-cost fallback</b> (§3.4): resolver error or zero matches yields
 *       an empty {@link ResolvedQuestion}, and every consumer then behaves
 *       byte-identically to today.</li>
 * </ul>
 *
 * <p>Tier provenance: entities carry {@code created_by}, so pack-instantiated
 * entities resolve with {@code pack:<id>} provenance as soon as pack apply
 * stamps it (PRO-30 roadmap R3). The vocabulary table does not yet carry
 * {@code created_by} (also R3); until then every vocabulary row resolves as
 * {@code company} — the seam ({@link #tierOf}) is in place, not the data.
 */
@Service
public class BusinessLanguageResolver {

    private static final Logger log = LoggerFactory.getLogger(BusinessLanguageResolver.class);

    /** Multi-word terms resolve via n-grams up to this many words (PRO-30 §3.2). */
    static final int MAX_NGRAM = 3;

    private static final String TIER_COMPANY = "company";

    // Two-letter English function words that must not be mistaken for TX-class
    // codes by unresolved-term detection (DLR-local: the shared stop-word list
    // is not extended, so PRO-31 matching behavior is untouched).
    private static final Set<String> SHORT_FUNCTION_WORDS = Set.of(
            "am", "as", "be", "by", "do", "go", "he", "hi", "if", "it", "my",
            "no", "ok", "so", "up", "us", "we");

    // Non-informative tokens excluded from retrieval expansion (SQL noise words).
    // Kept deliberately small — expansion tokens only join keyword sets, they are
    // never rendered, so a stray token costs nothing in the prompt.
    private static final Set<String> SQL_NOISE = Set.of(
            "select", "from", "where", "and", "or", "not", "null", "now", "case",
            "when", "then", "else", "end", "like", "between", "interval", "true",
            "false", "current_date", "current_timestamp", "distinct", "order", "group");

    // Resolutions per question — the cap-not-tenant-size prompt invariant (§6).
    @Value("${nexus.blr.max-resolutions:8}")
    private int maxResolutions = 8;

    // Unresolved literal-shaped terms per question (PRO-32 §2.1, DLR).
    @Value("${nexus.dlr.max-unresolved-terms:3}")
    private int maxUnresolvedTerms = 3;

    private final SemanticRepository semanticRepository;
    private final EnterpriseMapRepository enterpriseMapRepository;
    private final ObjectMapper objectMapper;

    public BusinessLanguageResolver(SemanticRepository semanticRepository,
                                    EnterpriseMapRepository enterpriseMapRepository,
                                    ObjectMapper objectMapper) {
        this.semanticRepository = semanticRepository;
        this.enterpriseMapRepository = enterpriseMapRepository;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Resolves the question against the domain-scoped Resolution Index.
     * Never throws: any failure degrades to the empty result (§3.4).
     */
    public ResolvedQuestion resolve(String question, List<String> domainKeys) {
        String original = question != null ? question : "";
        if (original.isBlank() || domainKeys == null || domainKeys.isEmpty()) {
            return ResolvedQuestion.empty(original);
        }
        try {
            DerivedIndex index = buildIndex(domainKeys);
            if (index.entries().isEmpty()) return ResolvedQuestion.empty(original);
            ResolvedQuestion resolved = resolveAgainstIndex(original, index.entries(), maxResolutions);
            if (!resolved.isEmpty()) {
                log.info("Business language resolved {} term(s): {}", resolved.resolutions().size(),
                        resolved.resolutions().stream()
                                .map(r -> "\"" + r.surface() + "\"->" + r.kind().label())
                                .toList());
            }
            // ── Deterministic Literal Resolution (PRO-33 / PRO-32 §2.1, §3) ──
            // Unresolved literal-shaped tokens become the constrained-choice
            // targets; the ranked domain-bearing columns are both the offered
            // candidates and the validator's scope. Empty on both sides keeps
            // the pipeline byte-identical (zero-cost guarantee).
            List<String> unresolved = detectUnresolvedTerms(
                    original, resolved.resolutions(), maxUnresolvedTerms);
            List<ResolvedQuestion.LiteralCandidate> candidates =
                    rankLiteralCandidates(index.columns());
            if (!unresolved.isEmpty() && !candidates.isEmpty()) {
                log.info("Literal resolution: {} unresolved term(s) {} offered {} candidate column(s)",
                        unresolved.size(), unresolved, candidates.size());
            }
            return new ResolvedQuestion(original, resolved.resolutions(),
                    resolved.expandedTokens(), unresolved, candidates);
        } catch (Exception e) {
            log.warn("Business language resolution failed — continuing without resolutions: {}",
                    e.getMessage());
            return ResolvedQuestion.empty(original);
        }
    }

    // =========================================================================
    // Index derivation (instance side — DB reads mapped to plain rows)
    // =========================================================================

    /** The per-question derived index: matchable entries plus the raw column
     *  rows they came from (the literal-candidate source, PRO-32 C2). */
    record DerivedIndex(List<IndexEntry> entries, List<IndexedColumn> columns) {}

    /**
     * Derives the Resolution Index from existing stores (§3.2): ACTIVE entities
     * and vocabulary in the domain scope, plus columns + persisted value domains
     * of the tables those entities are bound to. Column and value matching is
     * confined to entity-bound tables to bound cost.
     */
    private DerivedIndex buildIndex(List<String> domainKeys) {
        List<IndexedEntity> entities = new ArrayList<>();
        List<IndexedVocab>  vocab    = new ArrayList<>();
        List<IndexedColumn> columns  = new ArrayList<>();

        Map<String, DataObject> boundObjects = new LinkedHashMap<>();
        Map<String, String> qualifiedByEntityKey = new HashMap<>();

        for (String domainKey : domainKeys) {
            for (BusinessEntity e : semanticRepository.findEntitiesByDomain(domainKey)) {
                if (!"ACTIVE".equals(e.status())) continue;
                if (e.primaryObjectKey() == null || e.primaryObjectKey().isBlank()) continue;
                DataObject obj = boundObjects.computeIfAbsent(e.primaryObjectKey(),
                        k -> enterpriseMapRepository.findDataObjectByKey(k).orElse(null));
                if (obj == null) continue;
                String qualified = obj.schemaName() + "." + obj.tableName();
                qualifiedByEntityKey.put(e.entityKey(), qualified);
                entities.add(new IndexedEntity(e.entityKey(), e.entityName(),
                        e.primaryObjectKey(), qualified, obj.tableName(), tierOf(e.createdBy())));
            }
            for (OperationalVocabulary v : semanticRepository.findTermsByDomain(domainKey)) {
                // Vocabulary carries no created_by yet (PRO-30 R3): company tier.
                vocab.add(new IndexedVocab(v.term(), v.sqlEquivalent(), v.entityKey(), TIER_COMPANY));
            }
        }
        boundObjects.values().removeIf(java.util.Objects::isNull);

        // Columns + persisted domain values of bound tables only.
        Map<String, Optional<ValueDomain>> domainCache = new HashMap<>();
        for (DataObject obj : boundObjects.values()) {
            for (DataColumn col : enterpriseMapRepository.findColumnsByObject(obj.objectKey())) {
                List<String> values = List.of();
                boolean authoritative = false;
                if (col.valueDomainKey() != null) {
                    Optional<ValueDomain> vd = domainCache.computeIfAbsent(
                            col.valueDomainKey(), enterpriseMapRepository::findValueDomainByKey);
                    if (vd.isPresent()) {
                        values = parseDomainValues(vd.get());
                        authoritative = vd.get().isAuthoritative();
                    }
                }
                columns.add(new IndexedColumn(obj.objectKey(), obj.tableName(),
                        col.columnName(), values, col.isStatus(), col.isFilterable(),
                        col.roleSource(), authoritative));
            }
        }

        Map<String, String> entityNameByKey = new HashMap<>();
        for (IndexedEntity e : entities) entityNameByKey.put(e.entityKey(), e.entityName());

        return new DerivedIndex(
                buildIndexEntries(entities, vocab, columns, entityNameByKey, qualifiedByEntityKey),
                List.copyOf(columns));
    }

    /** Tier from a created_by value: {@code pack:<id>} rows keep their pack identity. */
    static String tierOf(String createdBy) {
        return createdBy != null && createdBy.startsWith("pack:") ? createdBy : TIER_COMPANY;
    }

    private List<String> parseDomainValues(ValueDomain domain) {
        try {
            return objectMapper.readValue(domain.domainValuesJson(), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // =========================================================================
    // Index assembly (static, DB-free — the testable seam)
    // =========================================================================

    /** Internal row shapes — keep DB mapping separate from assembly/matching. */
    record IndexedEntity(String entityKey, String entityName, String objectKey,
                         String qualifiedTable, String tableName, String tier) {}
    record IndexedVocab(String term, String sqlEquivalent, String entityKey, String tier) {}
    record IndexedColumn(String objectKey, String tableName, String columnName,
                         List<String> domainValues, boolean status, boolean filterable,
                         String roleSource, boolean authoritative) {

        /** PRO-31 shape — no Semantic Role / domain-authority information. */
        IndexedColumn(String objectKey, String tableName, String columnName,
                      List<String> domainValues) {
            this(objectKey, tableName, columnName, domainValues,
                    false, false, com.sei.nexus.enterprise.DataColumn.ROLE_INFERRED, false);
        }
    }

    /** One matchable index entry: a term, what it resolves to, and its expansion tokens. */
    record IndexEntry(String term, ResolvedQuestion.Kind kind, String target, String tier,
                      Set<String> expansionTokens) {}

    /**
     * Builds index entries in PRO-30 §3.2 source order: vocabulary (VALUE via
     * sql_equivalent, else ENTITY via entity binding), entity names (ENTITY),
     * persisted domain values (VALUE), column names of bound tables (COLUMN).
     * Every target is composed from an existing referent, never invented.
     */
    static List<IndexEntry> buildIndexEntries(List<IndexedEntity> entities,
            List<IndexedVocab> vocab, List<IndexedColumn> columns,
            Map<String, String> entityNameByKey, Map<String, String> qualifiedByEntityKey) {

        List<IndexEntry> entries = new ArrayList<>();

        // 1. Operational Vocabulary — the primary, writable source.
        for (IndexedVocab v : vocab) {
            if (v.term() == null || v.term().isBlank()) continue;
            String qualified = v.entityKey() != null ? qualifiedByEntityKey.get(v.entityKey()) : null;
            if (v.sqlEquivalent() != null && !v.sqlEquivalent().isBlank()) {
                // Curated predicate wins the prompt line; the bound table still
                // feeds expansion so retrieval sees the right block.
                Set<String> tokens = expansionTokens(v.sqlEquivalent(), qualified);
                entries.add(new IndexEntry(v.term(), ResolvedQuestion.Kind.VALUE,
                        v.sqlEquivalent(), v.tier(), tokens));
            } else if (qualified != null) {
                String entityName = entityNameByKey.get(v.entityKey());
                if (entityName == null) continue;
                entries.add(new IndexEntry(v.term(), ResolvedQuestion.Kind.ENTITY,
                        entityTarget(entityName, qualified), v.tier(),
                        expansionTokens(entityName, qualified)));
            }
        }

        // 2. Business Entity names — reuses the PRO-19 tier-1 binding store.
        for (IndexedEntity e : entities) {
            if (e.entityName() == null || e.entityName().isBlank()) continue;
            entries.add(new IndexEntry(e.entityName(), ResolvedQuestion.Kind.ENTITY,
                    entityTarget(e.entityName(), e.qualifiedTable()), e.tier(),
                    expansionTokens(e.entityName(), e.qualifiedTable())));
        }

        // 3. Persisted Value Domain values — the reverse direction of PRO-24:
        //    question token → column = 'value'. Only persisted values resolve;
        //    the discovery gates are never bypassed.
        for (IndexedColumn c : columns) {
            for (String value : c.domainValues()) {
                if (value == null || value.isBlank()) continue;
                String target = c.columnName() + " = '" + value.replace("'", "''") + "'";
                entries.add(new IndexEntry(value, ResolvedQuestion.Kind.VALUE,
                        target, TIER_COMPANY,
                        expansionTokens(c.columnName() + " " + value, c.tableName())));
            }
        }

        // 4. Column names — raw column-name matching confined to bound tables.
        for (IndexedColumn c : columns) {
            if (c.columnName() == null || c.columnName().isBlank()) continue;
            entries.add(new IndexEntry(c.columnName(), ResolvedQuestion.Kind.COLUMN,
                    c.tableName() + "." + c.columnName(), TIER_COMPANY,
                    expansionTokens(c.columnName(), c.tableName())));
        }

        return entries;
    }

    private static String entityTarget(String entityName, String qualifiedTable) {
        return entityName + " (table: " + qualifiedTable + ")";
    }

    /**
     * Canonical tokens a resolution implies for retrieval expansion: identifier
     * words from the target text plus the bound table's name — lowercase, the
     * same shape {@code QuestionKeywords.extract} produces, so they can join
     * every keyword set by simple union.
     */
    static Set<String> expansionTokens(String targetText, String tableName) {
        Set<String> tokens = new LinkedHashSet<>();
        collectTokens(targetText, tokens);
        collectTokens(tableName, tokens);
        return tokens;
    }

    private static void collectTokens(String text, Set<String> out) {
        if (text == null || text.isBlank()) return;
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
            if (raw.isBlank()) continue;
            addToken(raw, out);
            if (raw.indexOf('_') >= 0) {
                for (String part : raw.split("_+")) addToken(part, out);
            }
        }
    }

    private static void addToken(String token, Set<String> out) {
        if (token.length() < 3) return;
        if (SQL_NOISE.contains(token) || QuestionKeywords.isStopWord(token)) return;
        out.add(token);
    }

    // =========================================================================
    // Deterministic Literal Resolution (PRO-33 / PRO-32) — static seams
    // =========================================================================

    /**
     * Conservative unresolved-term detection (PRO-32 §2.1): a token is an
     * unresolved literal candidate when it (a) matched no resolution, (b) is
     * not a stop word, and (c) is literal-shaped — a quoted phrase, an
     * all-caps/alphanumeric code, or a short token (&lt; 3 chars) that
     * {@code QuestionKeywords.extract} would drop (the exact TX shape).
     * Plain prose words are never flagged; pure numbers are user-supplied
     * ground truth (verbatim-exempt in validation) and never flagged either.
     */
    static List<String> detectUnresolvedTerms(String question,
            List<ResolvedQuestion.Resolution> resolutions, int cap) {
        if (question == null || question.isBlank() || cap <= 0) return List.of();

        Set<String> resolvedTokens = new java.util.HashSet<>();
        for (ResolvedQuestion.Resolution r : resolutions) {
            for (String t : r.surface().toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (!t.isBlank()) resolvedTokens.add(t);
            }
        }

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();

        // Quoted phrases are explicit literal signals from the user.
        java.util.regex.Matcher q = java.util.regex.Pattern
                .compile("['\"]([^'\"]{2,40})['\"]").matcher(question);
        while (q.find() && out.size() < cap) {
            String phrase = q.group(1).trim();
            if (phrase.isBlank()) continue;
            String lower = phrase.toLowerCase(Locale.ROOT);
            if (resolvedTokens.containsAll(List.of(lower.split("[^a-z0-9]+")))) continue;
            if (seen.add(lower)) out.add(phrase);
        }

        for (String raw : question.split("[\\s,?!.;:]+")) {
            if (out.size() >= cap) break;
            String cleaned = raw.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
            if (cleaned.length() < 2) continue;
            String lower = cleaned.toLowerCase(Locale.ROOT);
            if (QuestionKeywords.isStopWord(lower)) continue;
            if (resolvedTokens.contains(lower)) continue;
            if (cleaned.matches("\\d+(\\.\\d+)?")) continue;   // numbers: verbatim ground truth

            boolean shortCode  = cleaned.length() < 3
                    && !SHORT_FUNCTION_WORDS.contains(lower);                   // "TX", "tx"
            boolean allCaps    = cleaned.matches("[A-Z][A-Z0-9]{1,9}");         // "USA", "DLX"
            boolean alnumCode  = cleaned.matches("(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9-]{2,12}"); // "N30"
            if (!(shortCode || allCaps || alnumCode)) continue;

            if (seen.add(lower)) out.add(cleaned);
        }
        return List.copyOf(out);
    }

    /**
     * Ranks domain-bearing columns as literal-resolution candidates
     * (PRO-32 C2): authoritative domains first, then status-role columns,
     * then declared/confirmed filterable ones — the PRO-29 Semantic Roles,
     * consumed, never re-decided. Columns without persisted domain values
     * offer nothing and are excluded. Stable within ranks.
     */
    static List<ResolvedQuestion.LiteralCandidate> rankLiteralCandidates(
            List<IndexedColumn> columns) {
        if (columns == null || columns.isEmpty()) return List.of();
        return columns.stream()
                .filter(c -> c.domainValues() != null && !c.domainValues().isEmpty())
                .sorted(java.util.Comparator
                        .<IndexedColumn>comparingInt(c -> c.authoritative() ? 0 : 1)
                        .thenComparingInt(c -> c.status() ? 0 : 1)
                        .thenComparingInt(c -> c.filterable()
                                && !com.sei.nexus.enterprise.DataColumn.ROLE_INFERRED
                                        .equals(c.roleSource()) ? 0 : 1))
                .map(c -> new ResolvedQuestion.LiteralCandidate(
                        c.tableName(), c.columnName(), c.authoritative(), c.domainValues()))
                .toList();
    }

    // =========================================================================
    // Matching (static, DB-free — the testable seam)
    // =========================================================================

    /**
     * Resolves the question's n-grams (≤ {@value #MAX_NGRAM} words) against the
     * index. Entries are visited company-tier first (§2 precedence), in source
     * order within a tier; the first entry to claim a (surface, kind) pair wins,
     * so a pack mapping that conflicts with a company mapping is simply not
     * emitted, while one surface resolving to two different kinds yields both —
     * the offered-set ambiguity contract (§3.4). Output is capped.
     */
    static ResolvedQuestion resolveAgainstIndex(String question, List<IndexEntry> index, int cap) {
        Map<String, String> surfaceByVariant = questionVariants(question);
        if (surfaceByVariant.isEmpty()) return ResolvedQuestion.empty(question);

        List<IndexEntry> ordered = new ArrayList<>(index);
        ordered.sort(java.util.Comparator.comparingInt(e -> TIER_COMPANY.equals(e.tier()) ? 0 : 1));

        Map<String, ResolvedQuestion.Resolution> chosen = new LinkedHashMap<>();
        Set<String> expanded = new LinkedHashSet<>();

        for (IndexEntry entry : ordered) {
            if (chosen.size() >= cap) break;
            String surface = matchSurface(entry.term(), surfaceByVariant);
            if (surface == null) continue;
            String key = surface.toLowerCase(Locale.ROOT) + "|" + entry.kind();
            if (chosen.containsKey(key)) continue;   // first-match-wins / tier precedence
            chosen.put(key, new ResolvedQuestion.Resolution(
                    surface, entry.kind(), entry.target(), entry.tier()));
            expanded.addAll(entry.expansionTokens());
        }

        if (chosen.isEmpty()) return ResolvedQuestion.empty(question);
        return new ResolvedQuestion(question, List.copyOf(chosen.values()),
                Set.copyOf(expanded));
    }

    /** The question surface an index term matches, or null. */
    private static String matchSurface(String term, Map<String, String> surfaceByVariant) {
        for (String variant : normalizedVariants(term)) {
            String surface = surfaceByVariant.get(variant);
            if (surface != null) return surface;
        }
        return null;
    }

    /**
     * All n-grams (1..{@value #MAX_NGRAM} words) of the question, mapped from
     * their normalized variants to the surface form as the user wrote it.
     * Stop-word unigrams never match (an index value "in" must not resolve
     * against every preposition); multi-word grams keep their stop words so
     * phrases like "net 30" survive intact.
     */
    static Map<String, String> questionVariants(String question) {
        String[] rawTokens = question.split("[\\s,?!.;:]+");
        List<String> tokens = new ArrayList<>();
        for (String raw : rawTokens) {
            String cleaned = raw.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
            if (!cleaned.isBlank()) tokens.add(cleaned);
        }

        Map<String, String> surfaceByVariant = new LinkedHashMap<>();
        for (int start = 0; start < tokens.size(); start++) {
            for (int len = 1; len <= MAX_NGRAM && start + len <= tokens.size(); len++) {
                String surface = String.join(" ", tokens.subList(start, start + len));
                String lower = surface.toLowerCase(Locale.ROOT);
                if (len == 1 && (lower.length() < 2 || QuestionKeywords.isStopWord(lower))) continue;
                for (String variant : normalizedVariants(surface)) {
                    surfaceByVariant.putIfAbsent(variant, surface);
                }
            }
        }
        return surfaceByVariant;
    }

    /**
     * The existing normalization family (PRO-30 §3.2): lowercase,
     * separator-insensitive (spaced + compressed forms), tolerant of a
     * singular/plural difference on the last word — the same shapes
     * {@code EntityCandidateService.matchTokens} produces for PRO-22.
     */
    static Set<String> normalizedVariants(String phrase) {
        if (phrase == null || phrase.isBlank()) return Set.of();
        String[] words = phrase.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<String> parts = new ArrayList<>();
        for (String w : words) if (!w.isBlank()) parts.add(w);
        if (parts.isEmpty()) return Set.of();

        List<String> singularParts = new ArrayList<>(parts);
        int last = singularParts.size() - 1;
        singularParts.set(last, QuestionKeywords.singular(singularParts.get(last)));

        Set<String> variants = new LinkedHashSet<>();
        variants.add(String.join(" ", parts));
        variants.add(String.join(" ", singularParts));
        variants.add(String.join("", parts));
        variants.add(String.join("", singularParts));
        return variants;
    }
}
