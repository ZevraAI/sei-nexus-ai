package com.sei.nexus.semantic;

import com.sei.nexus.common.QuestionKeywords;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Business Entity selection support (PRO-22) — tiers 0 and 1 of the approved
 * architecture, plus the single source of the tier-2 prompt block and JSON
 * contract text so the two onboarding analyzers cannot drift.
 *
 * <ul>
 *   <li><b>Tier 0:</b> deterministic lookup of the ACTIVE entity already bound
 *       to a data object — reused without any AI involvement.</li>
 *   <li><b>Tier 1:</b> bounded (≤ {@value #MAX_CANDIDATES}) candidate retrieval
 *       by normalized table-name tokens against entity names, entity keys and
 *       Operational Vocabulary terms — SQL-side, never load-all.</li>
 * </ul>
 *
 * This service performs no business reasoning: it retrieves and renders; the
 * reuse-vs-create judgment stays with the AI (tier 2) and integrity checks stay
 * with the registration pipeline.
 */
@Service
public class EntityCandidateService {

    static final int MAX_CANDIDATES = 5;
    private static final int MAX_TERMS_PER_CANDIDATE = 3;
    private static final int MAX_DESCRIPTION_CHARS = 120;

    private final SemanticRepository repository;

    public EntityCandidateService(SemanticRepository repository) {
        this.repository = repository;
    }

    /** A candidate as offered to the AI: stable key, name, physical grounding, synonyms. */
    public record Candidate(String entityKey, String entityName, String boundTable,
                            List<String> terms, String description) {}

    // ── Tier 0 ────────────────────────────────────────────────────────────────

    /** The ACTIVE entity already bound to this data object, if any (oldest wins). */
    public Optional<BusinessEntity> findBoundEntity(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return Optional.empty();
        return repository.findActiveByPrimaryObjectKey(objectKey);
    }

    /** Lookup for backend validation of an AI-selected key (any status). */
    public Optional<BusinessEntity> findEntity(String entityKey) {
        if (entityKey == null || entityKey.isBlank()) return Optional.empty();
        return repository.findEntityByKey(entityKey);
    }

    // ── Tier 1 ────────────────────────────────────────────────────────────────

    /** Bounded candidate list for a table being onboarded in a domain. */
    public List<Candidate> retrieve(String domainKey, String tableName) {
        if (domainKey == null || domainKey.isBlank() || tableName == null || tableName.isBlank()) {
            return List.of();
        }
        List<SemanticRepository.EntityCandidate> rows =
                repository.findCandidateEntities(domainKey, matchTokens(tableName), MAX_CANDIDATES);

        List<Candidate> candidates = new ArrayList<>(rows.size());
        for (SemanticRepository.EntityCandidate row : rows) {
            List<String> terms = repository.findTermsByEntity(row.entityKey()).stream()
                    .map(OperationalVocabulary::term)
                    .filter(t -> t != null && !t.isBlank())
                    .limit(MAX_TERMS_PER_CANDIDATE)
                    .toList();
            String desc = row.description() != null && row.description().length() > MAX_DESCRIPTION_CHARS
                    ? row.description().substring(0, MAX_DESCRIPTION_CHARS) + "…"
                    : row.description();
            candidates.add(new Candidate(row.entityKey(), row.entityName(),
                    row.boundTable(), terms, desc));
        }
        return candidates;
    }

    /**
     * Normalized match tokens for a table name — the same normalization family
     * already used by RelationshipDiscoveryService's slug fallback and
     * PackEntityMapper's pattern match: strip warehouse prefixes, generate
     * spaced/compressed and singular variants.
     * e.g. "lgs_purchase_orders" → [purchase orders, purchase order,
     *      purchaseorders, purchaseorder, purchase-orders, purchase-order]
     */
    static List<String> matchTokens(String tableName) {
        String norm = tableName.toLowerCase().trim()
                .replaceAll("^(lgs_|stg_|dim_|fact_|tbl_|vw_)", "");
        if (norm.isBlank()) return List.of();

        String[] words = norm.split("_+");
        String lastSingular = QuestionKeywords.singular(words[words.length - 1]);
        String[] singularWords = words.clone();
        singularWords[singularWords.length - 1] = lastSingular;

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        // spaced forms — match lower(entity_name) ("store targets" → "Store Target")
        tokens.add(String.join(" ", words));
        tokens.add(String.join(" ", singularWords));
        // compressed forms — match replace(entity_key,'-','') ("storetarget")
        tokens.add(String.join("", words));
        tokens.add(String.join("", singularWords));
        // hyphenated forms — match vocabulary terms stored slug-like
        tokens.add(String.join("-", words));
        tokens.add(String.join("-", singularWords));
        return List.copyOf(tokens);
    }

    // ── Tier 2 prompt text (single source for both analyzers) ────────────────

    /** Candidate block appended to the analyzer's user message; empty when no candidates. */
    public String renderPromptBlock(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nExisting business entities that may already represent this table:\n");
        for (Candidate c : candidates) {
            sb.append("- entity_key=").append(c.entityKey())
              .append(" | name=").append(c.entityName());
            if (c.boundTable() != null && !c.boundTable().isBlank()) {
                sb.append(" | table=").append(c.boundTable());
            }
            if (!c.terms().isEmpty()) {
                sb.append(" | terms: ").append(String.join(", ", c.terms()));
            }
            if (c.description() != null && !c.description().isBlank()) {
                sb.append(" | desc: ").append(c.description());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** JSON-contract addition for the analyzer's system prompt; empty when no candidates. */
    public String resolutionContract(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";
        return """

                The user message lists existing business entities that may already represent this table.
                Decide whether this table is the SAME business concept as one of them, and add this field
                to your JSON response:
                "entityResolution": {"decision": "REUSE" or "CREATE", "entityKey": "<required when REUSE: one of the listed entity_key values>", "confidence": 0.0-1.0, "reason": "one sentence"}
                Rules: entityKey MUST be copied exactly from the list — never invent one. If none of the
                listed entities is the same concept, use {"decision": "CREATE"}.
                """;
    }
}
