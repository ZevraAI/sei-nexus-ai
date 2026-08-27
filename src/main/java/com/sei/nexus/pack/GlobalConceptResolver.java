package com.sei.nexus.pack;

import com.sei.nexus.common.NexusException;
import com.sei.nexus.enterprise.DataColumn;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.EntityRelationship;
import com.sei.nexus.semantic.SemanticRepository;
import com.sei.nexus.sql.DynamicSqlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Global Concept Resolution — Phase 1, READ-ONLY.
 *
 * <p>Answers: "for this tenant connection and its assigned Industry Pack, which Global
 * Business Concepts are plausible matches for each existing Business Object, and what
 * deterministic evidence supports each candidate?" Never mutates any database state —
 * no {@code pack_key}/{@code concept_key} write, no proposal record, nothing persisted.
 * Every result is computed fresh from existing, already-persisted metadata (plus one
 * optional, genuinely read-only live call to the tenant's own source database for table/
 * column comments — no Zevra state is touched by that call either).
 *
 * <p><b>Candidate space enforcement</b>: the candidate concepts for a connection are
 * exactly, and only, {@code pack.groups()[].concepts()} for the ONE pack found ACTIVE for
 * that {@code connectionKey} in {@code nexus_tenant_pack} (Global Pack Foundation).
 * There is no code path here that ever reads a different pack's concepts for a given
 * connection — a Retail connection structurally cannot see Logistics concepts.
 *
 * <p><b>Deliberately NOT reused</b>: {@link PackEntityMapper#match} selects the single
 * best table for a whole entity (a different problem shape — one winner per entity), and
 * its core {@code patternMatch} method is {@code private}. This class replicates that
 * method's normalization RULE exactly (strip non-alphanumeric, bidirectional substring
 * containment) rather than calling it, since evaluating one specific table/column against
 * one specific concept's patterns — and keeping every match's evidence for an explainable
 * trail, not just a winner — is a different shape of computation. Extracting a shared
 * helper would mean modifying {@code PackEntityMapper.java}, out of scope for this
 * read-only addition. {@link com.sei.nexus.semantic.EntityCandidateService}'s exact-match
 * SQL is a different problem entirely (existing-entity reuse, not Pack-concept matching)
 * and is not applicable here.
 *
 * <p><b>No LLM.</b> No AI call anywhere in this class, deliberately, per this phase's scope.
 *
 * <p><b>Phase 1B — relationship evidence.</b> Reuses the existing, already-populated
 * {@code nexus_entity_relationship} table (written only by {@code RelationshipDiscoveryService},
 * never by this class) to distinguish a Business Object's own identity columns from its
 * outgoing foreign-key/reference columns. A column confirmed as an outgoing
 * {@code source_column} (this entity is {@code source_entity_key}) never contributes
 * {@code identifier_role}/{@code key_column_pattern} identity evidence toward the candidate it
 * would otherwise spuriously support — e.g. {@code purchase_orders.supplier_id} is a confirmed
 * reference to Supplier, not evidence that a Purchase Order object is itself a Supplier. This
 * class never calls {@code RelationshipDiscoveryService} and never writes to
 * {@code nexus_entity_relationship} — if no relationship rows exist for an object (discovery
 * never run, or nothing to discover), evidence gathering proceeds exactly as it did before this
 * change; a lookup that answers "no relationships known" is not a lookup that failed.
 */
@Service
public class GlobalConceptResolver {

    private static final Logger log = LoggerFactory.getLogger(GlobalConceptResolver.class);

    private final IndustryPackRepository packRepository;
    private final EnterpriseMapRepository enterpriseMapRepository;
    private final SemanticRepository semanticRepository;
    private final DynamicSqlService dynamicSqlService;

    public GlobalConceptResolver(IndustryPackRepository packRepository,
                                  EnterpriseMapRepository enterpriseMapRepository,
                                  SemanticRepository semanticRepository,
                                  DynamicSqlService dynamicSqlService) {
        this.packRepository = packRepository;
        this.enterpriseMapRepository = enterpriseMapRepository;
        this.semanticRepository = semanticRepository;
        this.dynamicSqlService = dynamicSqlService;
    }

    /**
     * Production entry point: looks up the connection's ACTIVE pack assignment itself.
     * Throws if none exists — there is nothing to resolve against without one.
     */
    public List<BusinessObjectResolution> resolveForConnection(String connectionKey) {
        TenantPack assignment = packRepository.findActivePackForConnection(connectionKey)
                .orElseThrow(() -> new NexusException(HttpStatus.BAD_REQUEST,
                        "No ACTIVE Industry Pack assignment for connection: " + connectionKey));
        IndustryPack pack = packRepository.findPackById(assignment.packKey())
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Industry pack not found: " + assignment.packKey()));
        return resolveAgainstPack(connectionKey, pack);
    }

    /**
     * Diagnostic/test entry point: resolves against an explicitly supplied pack, bypassing
     * the {@code nexus_tenant_pack} lookup. Used by real-tenant validation while no real,
     * shipped pack file has {@code groups()} populated yet (this phase does not modify Pack
     * JSON — see the validation report for why this overload exists).
     */
    public List<BusinessObjectResolution> resolveAgainstPack(String connectionKey, IndustryPack pack) {
        List<ConceptWithGroup> concepts = flattenConcepts(pack);
        List<DataObject> objects = enterpriseMapRepository.findDataObjectsByConnection(connectionKey);

        List<BusinessObjectResolution> results = new ArrayList<>();
        for (DataObject object : objects) {
            var boundEntity = semanticRepository.findActiveByPrimaryObjectKey(object.objectKey());
            if (boundEntity.isEmpty()) {
                // No registered Business Entity for this physical object yet — nothing to
                // resolve (resolution operates on Business Objects, not raw physical tables).
                continue;
            }
            results.add(resolveOne(connectionKey, pack.packId(), boundEntity.get(), object, concepts));
        }
        return results;
    }

    // ── per-object resolution ────────────────────────────────────────────────────

    private BusinessObjectResolution resolveOne(String connectionKey, String packKey, BusinessEntity entity,
                                                 DataObject object, List<ConceptWithGroup> concepts) {
        List<DataColumn> columns = enterpriseMapRepository.findColumnsByObject(object.objectKey());
        Set<String> outgoingRelationshipColumns = findOutgoingRelationshipColumns(entity.entityKey());

        String tableComment = null;
        Map<String, String> columnComments = Map.of();
        try {
            DynamicSqlService.TableDescription described = dynamicSqlService.describeTableWithComments(
                    object.connectionKey(), object.schemaName(), object.tableName());
            tableComment = described.tableComment();
            columnComments = new LinkedHashMap<>();
            for (Map<String, Object> col : described.columns()) {
                Object name = col.getOrDefault("column_name", col.get("columnName"));
                Object comment = col.get("column_comment");
                if (name != null && comment != null) columnComments.put(String.valueOf(name), String.valueOf(comment));
            }
        } catch (Exception e) {
            // Source comments are enrichment, never a dependency — exactly the same
            // graceful-degradation posture as BusinessObjectBatchAnalyzer's own comment
            // retrieval. A stale/unreachable source connection must not fail resolution.
            log.debug("Source comment retrieval unavailable for {}.{}: {}",
                    object.schemaName(), object.tableName(), e.getMessage());
        }

        List<ConceptCandidate> candidates = new ArrayList<>();
        for (ConceptWithGroup c : concepts) {
            ConceptCandidate candidate = evaluateConcept(entity, object, columns, tableComment, columnComments,
                    c, outgoingRelationshipColumns);
            if (candidate.overallStrength() != EvidenceStrength.NONE) {
                candidates.add(candidate);
            }
        }

        ResolutionOutcome outcome = determineOutcome(candidates);
        return new BusinessObjectResolution(connectionKey, packKey, entity.entityKey(), entity.entityName(),
                object.objectKey(), object.tableName(), outcome, candidates);
    }

    private ResolutionOutcome determineOutcome(List<ConceptCandidate> candidates) {
        if (candidates.isEmpty()) return ResolutionOutcome.UNRESOLVED;

        long strongCount = candidates.stream().filter(c -> c.overallStrength() == EvidenceStrength.STRONG).count();
        long atLeastMediumCount = candidates.stream()
                .filter(c -> c.overallStrength() == EvidenceStrength.STRONG || c.overallStrength() == EvidenceStrength.MEDIUM)
                .count();

        if (strongCount >= 2) return ResolutionOutcome.CONFLICTING;
        if (strongCount == 1 && atLeastMediumCount == 1) return ResolutionOutcome.CLEAR;
        if (atLeastMediumCount >= 2) return ResolutionOutcome.AMBIGUOUS;
        // Exactly one candidate, and it never reached STRONG (MEDIUM or WEAK alone) — a lone
        // weak signal with nothing to compare it against is not confident enough to call CLEAR.
        return ResolutionOutcome.UNRESOLVED;
    }

    // ── per-concept evidence gathering ───────────────────────────────────────────

    private ConceptCandidate evaluateConcept(BusinessEntity entity, DataObject object, List<DataColumn> columns,
                                              String tableComment, Map<String, String> columnComments,
                                              ConceptWithGroup c, Set<String> outgoingRelationshipColumns) {
        PackEntity concept = c.concept();
        List<ConceptEvidence> evidence = new ArrayList<>();
        int strongEligibleSignals = 0;
        boolean anyMediumSignal = false;

        // ── table_pattern (strong-eligible) ──
        if (matchesAnyPattern(object.tableName(), concept.tablePatterns())) {
            evidence.add(new ConceptEvidence("table_pattern",
                    object.tableName() + " matches a table_pattern for " + concept.name(), EvidenceStrength.MEDIUM));
            strongEligibleSignals++;
        }

        // ── key_column_pattern, with identifier-role corroboration (strong-eligible) ──
        // Phase 1B: a column already confirmed (via nexus_entity_relationship) as THIS entity's
        // own outgoing reference to a DIFFERENT entity must never count as identity evidence for
        // the concept that reference points at — e.g. purchase_orders.supplier_id matching
        // Supplier's key_column_pattern is not evidence that a Purchase Order IS a Supplier.
        boolean keyColumnMatch = false;
        boolean keyColumnMatchIsIdentifier = false;
        for (DataColumn col : columns) {
            if (matchesAnyPattern(col.columnName(), concept.keyColumnPatterns())) {
                if (outgoingRelationshipColumns.contains(normalize(col.columnName()))) {
                    evidence.add(new ConceptEvidence("outgoing_relationship_excluded",
                            col.columnName() + " matches a key_column_pattern for " + concept.name()
                                    + ", but nexus_entity_relationship confirms it is this object's own "
                                    + "outgoing reference to a different entity — excluded from identity evidence",
                            EvidenceStrength.NONE));
                    continue;
                }
                keyColumnMatch = true;
                if (col.isIdentifier()) {
                    keyColumnMatchIsIdentifier = true;
                    evidence.add(new ConceptEvidence("identifier_role",
                            col.columnName() + " matches a key_column_pattern AND is_identifier=true",
                            EvidenceStrength.STRONG));
                } else {
                    evidence.add(new ConceptEvidence("key_column_pattern",
                            col.columnName() + " matches a key_column_pattern (not flagged as an identifier)",
                            EvidenceStrength.MEDIUM));
                }
            }
        }
        if (keyColumnMatchIsIdentifier) strongEligibleSignals++;
        else if (keyColumnMatch) anyMediumSignal = true;

        // ── UDT/enum name mentioning the concept (strong-eligible) ──
        for (DataColumn col : columns) {
            if (col.udtName() != null && mentions(col.udtName(), concept)) {
                evidence.add(new ConceptEvidence("udt_enum",
                        col.columnName() + "'s underlying type '" + col.udtName() + "' relates to " + concept.name(),
                        EvidenceStrength.STRONG));
                strongEligibleSignals++;
                break; // one corroborating UDT hit is enough evidence of this signal type
            }
        }

        // ── source table/column comments (strong-eligible; highest-quality when present) ──
        if (tableComment != null && mentions(tableComment, concept)) {
            evidence.add(new ConceptEvidence("source_table_comment",
                    "the source database's own table comment mentions " + concept.name(), EvidenceStrength.STRONG));
            strongEligibleSignals++;
        }
        for (Map.Entry<String, String> e : columnComments.entrySet()) {
            if (mentions(e.getValue(), concept)) {
                evidence.add(new ConceptEvidence("source_column_comment",
                        "column " + e.getKey() + "'s source comment mentions " + concept.name(), EvidenceStrength.STRONG));
                strongEligibleSignals++;
                break;
            }
        }

        // ── alias match against entity name / table name (medium, not strong-eligible) ──
        if (matchesAnyAlias(object.tableName(), concept) || matchesAnyAlias(entity.entityName(), concept)) {
            evidence.add(new ConceptEvidence("alias",
                    "entity/table name matches one of " + concept.name() + "'s aliases", EvidenceStrength.MEDIUM));
            anyMediumSignal = true;
        }

        // ── weak signals: free-text overlap, group_label overlap ──
        boolean anyWeakSignal = false;
        if (entity.description() != null && mentions(entity.description(), concept)) {
            evidence.add(new ConceptEvidence("entity_description",
                    "the entity's description mentions " + concept.name(), EvidenceStrength.WEAK));
            anyWeakSignal = true;
        }
        if (entity.operationalMeaning() != null && mentions(entity.operationalMeaning(), concept)) {
            evidence.add(new ConceptEvidence("operational_meaning",
                    "the entity's operational_meaning mentions " + concept.name(), EvidenceStrength.WEAK));
            anyWeakSignal = true;
        }
        if (entity.groupLabel() != null && c.groupName() != null
                && normalize(entity.groupLabel()).equals(normalize(c.groupName()))) {
            evidence.add(new ConceptEvidence("group_label",
                    "entity's group_label '" + entity.groupLabel() + "' matches the concept's group '"
                            + c.groupName() + "' — a loose signal only, group_label is not a controlled vocabulary",
                    EvidenceStrength.WEAK));
            anyWeakSignal = true;
        }

        EvidenceStrength overall;
        if (strongEligibleSignals >= 2) overall = EvidenceStrength.STRONG;
        else if (strongEligibleSignals == 1 || anyMediumSignal) overall = EvidenceStrength.MEDIUM;
        else if (anyWeakSignal) overall = EvidenceStrength.WEAK;
        else overall = EvidenceStrength.NONE;

        return new ConceptCandidate(c.groupKey(), concept.conceptKey(), concept.name(), evidence, overall);
    }

    // ── shared helpers ────────────────────────────────────────────────────────────

    /**
     * Phase 1B: the set of this entity's OWN outgoing reference columns — normalized column
     * names for every {@code nexus_entity_relationship} row where this entity is
     * {@code source_entity_key}. Read-only; never triggers discovery, never writes anything.
     * Reuses {@link SemanticRepository#findRelationshipsByEntity}, which returns rows where the
     * entity is source OR target — filtered here to source-side only, since only a column that
     * is THIS entity's own outgoing reference should be excluded from its own identity evidence
     * (a column this entity is merely the TARGET of is unrelated to this exclusion). Returns an
     * empty set — not an error — when no relationship rows exist for this entity, so evidence
     * gathering proceeds exactly as it did before this change.
     */
    private Set<String> findOutgoingRelationshipColumns(String entityKey) {
        List<EntityRelationship> relationships = semanticRepository.findRelationshipsByEntity(entityKey);
        Set<String> columns = new HashSet<>();
        for (EntityRelationship r : relationships) {
            if (entityKey.equals(r.sourceEntityKey()) && r.sourceColumn() != null) {
                columns.add(normalize(r.sourceColumn()));
            }
        }
        return columns;
    }

    private record ConceptWithGroup(String groupKey, String groupName, PackEntity concept) {}

    private List<ConceptWithGroup> flattenConcepts(IndustryPack pack) {
        List<ConceptWithGroup> flat = new ArrayList<>();
        if (pack.groups() == null) return flat;
        for (PackGroup g : pack.groups()) {
            if (g.concepts() == null) continue;
            for (PackEntity concept : g.concepts()) {
                flat.add(new ConceptWithGroup(g.groupKey(), g.groupName(), concept));
            }
        }
        return flat;
    }

    // Below this length, bidirectional substring containment produces false positives — e.g.
    // column "id" is a literal substring of key_column_pattern "po_id"/"purchase_order_id" for
    // EVERY *_id concept, which is not real evidence of anything. Discovered by this task's own
    // unit tests (Case 5). PackEntityMapper's original bidirectional-containment rule is safe
    // for table names (typically long, distinctive) but not safe applied uncritically to short
    // column names — this guard is the one deliberate deviation from replicating that rule
    // byte-for-byte, and is applied to both directions of the containment check.
    private static final int MIN_LENGTH_FOR_SUBSTRING_CONTAINMENT = 4;

    /** Replicates PackEntityMapper.patternMatch's normalization rule, with a short-string guard
     *  (see {@link #MIN_LENGTH_FOR_SUBSTRING_CONTAINMENT}) — see class javadoc. */
    private boolean matchesAnyPattern(String value, List<String> patterns) {
        if (patterns == null || value == null) return false;
        String normValue = normalize(value);
        if (normValue.isEmpty()) return false;
        for (String pattern : patterns) {
            String normPattern = normalize(pattern);
            if (normPattern.isEmpty()) continue;
            if (normValue.equals(normPattern)) return true;
            if (normValue.length() >= MIN_LENGTH_FOR_SUBSTRING_CONTAINMENT
                    && normPattern.length() >= MIN_LENGTH_FOR_SUBSTRING_CONTAINMENT
                    && (normValue.contains(normPattern) || normPattern.contains(normValue))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyAlias(String value, PackEntity concept) {
        if (value == null || concept.aliases() == null) return false;
        String normValue = normalize(value);
        if (normValue.isEmpty()) return false;
        for (String alias : concept.aliases()) {
            String normAlias = normalize(alias);
            if (normAlias.isEmpty()) continue;
            if (normValue.equals(normAlias)) return true;
            if (normValue.length() >= MIN_LENGTH_FOR_SUBSTRING_CONTAINMENT
                    && normAlias.length() >= MIN_LENGTH_FOR_SUBSTRING_CONTAINMENT
                    && (normValue.contains(normAlias) || normAlias.contains(normValue))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whole-word/phrase check for whether free text (a source comment, entity description, etc.)
     * mentions this concept by name/alias — word-boundary regex, not plain substring containment.
     * Discovered by this task's own real-tenant validation: a plain {@code .contains()} check on
     * a short alias like "po" spuriously matched almost any prose containing "position", "report",
     * "component" — anything with "po" inside a longer word. Word boundaries fix this at the root,
     * more correctly than a minimum-length threshold would (the same principled fix that
     * {@link #matchesAnyPattern}/{@link #matchesAnyAlias} approximate with a length guard for
     * normalized identifier tokens, which have no natural word boundaries to anchor on).
     */
    private boolean mentions(String text, PackEntity concept) {
        if (text == null || text.isBlank()) return false;
        if (containsWord(text, concept.name())) return true;
        if (concept.aliases() != null) {
            for (String alias : concept.aliases()) {
                if (containsWord(text, alias)) return true;
            }
        }
        return false;
    }

    private boolean containsWord(String text, String term) {
        if (term == null || term.isBlank()) return false;
        String pattern = "\\b" + java.util.regex.Pattern.quote(term.trim().toLowerCase()) + "\\b";
        return java.util.regex.Pattern.compile(pattern).matcher(text.toLowerCase()).find();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
