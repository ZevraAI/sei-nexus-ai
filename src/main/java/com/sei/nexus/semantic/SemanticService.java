package com.sei.nexus.semantic;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SemanticService {

    private static final Logger log = LoggerFactory.getLogger(SemanticService.class);

    private static final String FIND_ENTITIES =
            "SELECT entity_key, entity_name, node_type, description, " +
            "operational_meaning, investigation_hints, status, primary_object_key " +
            "FROM nexus_business_entity WHERE domain_key = ANY(?::text[]) AND status = 'ACTIVE' LIMIT 50";

    private static final String FIND_VOCABULARY =
            "SELECT term, definition, sql_equivalent, entity_key " +
            "FROM nexus_operational_vocabulary WHERE domain_key = ANY(?::text[]) AND status = 'ACTIVE' LIMIT 30";

    private final JdbcTemplate jdbc;
    private final AzureOpenAiClient aiClient;
    private final SemanticRepository repository;

    public SemanticService(JdbcTemplate jdbc, AzureOpenAiClient aiClient,
                           SemanticRepository repository) {
        this.jdbc = jdbc;
        this.aiClient = aiClient;
        this.repository = repository;
    }

    /**
     * A business term (an entity name or a vocabulary term) bound to the physical
     * table it resolves to via nexus_business_entity.primary_object_key. The
     * primaryObjectKey equals nexus_data_object.object_key, i.e. the key of the
     * business object the term resolves to.
     */
    public record EntityBinding(String matchText, String primaryObjectKey) {}

    /**
     * Rendered semantic context plus two derivations from the same rows, so the
     * context assembler (ChatService) needs no extra queries:
     * <ul>
     *   <li>{@code bindings} — term→table bindings for entity-block ranking (PRO-19);</li>
     *   <li>{@code termLinesByObjectKey} — pre-rendered "Business terms" lines
     *       ({@code "term" = sql_equivalent}) per bound table, attached to the
     *       selected entity blocks as structural companions (PRO-24). Only ACTIVE
     *       terms with a non-blank SQL equivalent qualify, capped per table.</li>
     * </ul>
     */
    public record SemanticContext(String contextText, List<EntityBinding> bindings,
                                  Map<String, List<String>> termLinesByObjectKey) {
        public static final SemanticContext EMPTY = new SemanticContext("", List.of(), Map.of());
    }

    // Business-terms lines attached per entity block (PRO-24) — bounded per table
    // so prompt cost never scales with vocabulary volume.
    private static final int MAX_TERM_LINES_PER_OBJECT = 3;

    // Internal row shapes — keep query mapping separate from assembly so the
    // rendering + binding logic is testable without a database.
    record EntityRow(String entityKey, String name, String type, String description,
                     String meaning, String hints, String primaryObjectKey) {}
    record VocabRow(String term, String definition, String sqlEquivalent, String entityKey) {}

    /**
     * Builds a semantic context string for a given question within the specified domains.
     * Returns relevant entity definitions and vocabulary that help answer the question.
     */
    public String buildSemanticContext(List<String> domainKeys, String question) {
        return semanticContextWithBindings(domainKeys, question).contextText();
    }

    /**
     * Same rendered context as {@link #buildSemanticContext}, plus the
     * entity-name and vocabulary-term → primary_object_key bindings used by the
     * planner context assembler as its primary relevance signal.
     */
    public SemanticContext semanticContextWithBindings(List<String> domainKeys, String question) {
        if (domainKeys == null || domainKeys.isEmpty()) {
            return SemanticContext.EMPTY;
        }
        try {
            String[] domainArray = domainKeys.toArray(new String[0]);

            List<EntityRow> entities = jdbc.query(FIND_ENTITIES,
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", domainArray)),
                    (rs, rowNum) -> new EntityRow(
                            rs.getString("entity_key"),
                            rs.getString("entity_name"),
                            rs.getString("node_type"),
                            rs.getString("description"),
                            rs.getString("operational_meaning"),
                            rs.getString("investigation_hints"),
                            rs.getString("primary_object_key")));

            List<VocabRow> vocab = jdbc.query(FIND_VOCABULARY,
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", domainArray)),
                    (rs, rowNum) -> new VocabRow(
                            rs.getString("term"),
                            rs.getString("definition"),
                            rs.getString("sql_equivalent"),
                            rs.getString("entity_key")));

            return assemble(entities, vocab);
        } catch (Exception e) {
            log.warn("Failed to build semantic context for domains {}: {}", domainKeys, e.getMessage());
            return SemanticContext.EMPTY;
        }
    }

    /**
     * Renders the semantic context text (format unchanged) and collects the
     * term→table bindings. Vocabulary terms resolve to a table transitively:
     * term.entity_key → entity.primary_object_key. Entities without a table
     * link (e.g. pack-created entities) contribute context text but no binding.
     */
    static SemanticContext assemble(List<EntityRow> entities, List<VocabRow> vocab) {
        StringBuilder sb = new StringBuilder();
        Map<String, String> entityTable = new java.util.HashMap<>();
        List<EntityBinding> bindings = new java.util.ArrayList<>();

        if (!entities.isEmpty()) {
            sb.append("=== Business Entities ===\n");
            for (EntityRow e : entities) {
                StringBuilder row = new StringBuilder(e.name());
                if (e.type() != null && !e.type().isBlank()) row.append(" (").append(e.type()).append(")");
                if (e.description() != null && !e.description().isBlank()) row.append(": ").append(e.description());
                if (e.meaning() != null && !e.meaning().isBlank()) row.append(" | ").append(e.meaning());
                if (e.hints() != null && !e.hints().isBlank()) row.append(" | Hint: ").append(e.hints());
                sb.append("- ").append(row).append("\n");

                if (e.primaryObjectKey() != null && !e.primaryObjectKey().isBlank()) {
                    if (e.entityKey() != null) entityTable.put(e.entityKey(), e.primaryObjectKey());
                    if (e.name() != null) bindings.add(new EntityBinding(e.name(), e.primaryObjectKey()));
                }
            }
            sb.append("\n");
        }

        Map<String, List<String>> termLines = new java.util.LinkedHashMap<>();
        if (!vocab.isEmpty()) {
            sb.append("=== Operational Vocabulary ===\n");
            for (VocabRow v : vocab) {
                String row = v.term() + ": " + v.definition();
                if (v.sqlEquivalent() != null && !v.sqlEquivalent().isBlank()) {
                    row = row + " [SQL: " + v.sqlEquivalent() + "]";
                }
                sb.append("- ").append(row).append("\n");

                String objectKey = v.entityKey() != null ? entityTable.get(v.entityKey()) : null;
                if (v.term() != null && objectKey != null) {
                    bindings.add(new EntityBinding(v.term(), objectKey));

                    // Business-terms companion line (PRO-24): only sql-equipped
                    // terms earn prompt tokens; capped per bound table.
                    if (v.sqlEquivalent() != null && !v.sqlEquivalent().isBlank()) {
                        List<String> lines = termLines.computeIfAbsent(
                                objectKey, k -> new java.util.ArrayList<>());
                        if (lines.size() < MAX_TERM_LINES_PER_OBJECT) {
                            lines.add("\"" + v.term() + "\" = " + v.sqlEquivalent());
                        }
                    }
                }
            }
        }

        return new SemanticContext(sb.toString(), List.copyOf(bindings), Map.copyOf(termLines));
    }

    // -------------------------------------------------------------------------
    // CRUD operations delegated from SemanticController
    // -------------------------------------------------------------------------

    public BusinessEntity createOrUpdateEntity(Map<String, Object> body, String userEmail) {
        String entityKey = str(body, "entityKey", "entity_key");
        if (entityKey == null || entityKey.isBlank()) entityKey = Keys.uniqueKey("entity");
        Instant now = Instant.now();
        BusinessEntity entity = new BusinessEntity(
                entityKey,
                str(body, "domainKey",          "domain_key"),
                str(body, "entityName",          "entity_name"),
                str(body, "description"),
                str(body, "primaryObjectKey",    "primary_object_key"),
                str(body, "operationalMeaning",  "operational_meaning"),
                str(body, "investigationHints",  "investigation_hints"),
                str(body, "status") != null ? str(body, "status") : "ACTIVE",
                userEmail,
                now, now,
                null,
                // Grouping Foundation Fix: the AI-generated category from the shared
                // onboarding analysis (analyzeTableBatch), carried through
                // MetadataRegistrationService unchanged. Omitted (e.g. a manual
                // Semantic Layer edit) ⇒ null here, preserved via the repository's
                // COALESCE — never erases a group an onboarding flow already set.
                str(body, "groupLabel", "group_label"),
                // Global Pack Foundation: no current caller (Onboarding, Discover, Pack apply,
                // or the manual Semantic Layer edit) supplies these — this task adds only the
                // plumbing, never a classification. Omitted ⇒ null here, preserved via the
                // repository's COALESCE — never erases a reference a future mapping step set.
                str(body, "packKey", "pack_key"),
                str(body, "conceptKey", "concept_key"));
        repository.saveEntity(entity);
        return repository.findEntityByKey(entityKey).orElseThrow();
    }

    /** Thin pass-through — same layering as every other read method here (e.g. {@link
     *  #buildSemanticContext}), just not previously needed by a caller outside {@code
     *  SemanticController} (which calls {@link SemanticRepository} directly for this one). */
    public Optional<BusinessEntity> findEntityByKey(String entityKey) {
        return repository.findEntityByKey(entityKey);
    }

    /** Thin pass-through to the existing soft-delete ({@code status = 'ARCHIVED'}) — the same
     *  operation the Semantic Layer UI's own "Archive" button already performs via {@link
     *  SemanticRepository#archiveEntity}, exposed here so non-controller callers (Industry Pack
     *  removal) don't need their own {@link SemanticRepository} dependency. */
    public void archiveEntity(String entityKey) {
        repository.archiveEntity(entityKey);
    }

    /** Thin pass-through — see {@link SemanticRepository#clearPackAssociationForConnection}.
     *  Returns the number of Business Entities affected. */
    public int clearPackAssociationForConnection(String packKey, String connectionKey) {
        return repository.clearPackAssociationForConnection(packKey, connectionKey);
    }

    /** Thin pass-through — see {@link SemanticRepository#associatePackKeyForConnection}.
     *  Returns the number of Business Entities affected. */
    public int associatePackKeyForConnection(String packKey, String connectionKey) {
        return repository.associatePackKeyForConnection(packKey, connectionKey);
    }

    /** Thin pass-through — PRO-22 tier-0 lookup, reused here to find the EXISTING entity (if
     *  any) bound to a physical object, so Apply Pack's LLM classification step never creates
     *  one — see {@link SemanticRepository#findActiveByPrimaryObjectKey}. */
    public Optional<BusinessEntity> findActiveByPrimaryObjectKey(String objectKey) {
        return repository.findActiveByPrimaryObjectKey(objectKey);
    }

    /** Thin pass-through — see {@link SemanticRepository#setConceptKey}. */
    public void setConceptKey(String entityKey, String conceptKey) {
        repository.setConceptKey(entityKey, conceptKey);
    }

    /** Thin pass-through — Concept-Scoped Metadata Narrowing Stage 1: see {@link
     *  SemanticRepository#findDistinctConceptKeysForConnection}. */
    public List<String> findDistinctConceptKeysForConnection(String connectionKey) {
        return repository.findDistinctConceptKeysForConnection(connectionKey);
    }

    /** Thin pass-through — Concept-Scoped Metadata Narrowing Stage 2: see {@link
     *  SemanticRepository#findEntitiesByConnectionAndConcepts}. */
    public List<BusinessEntity> findEntitiesByConnectionAndConcepts(String connectionKey, List<String> conceptKeys) {
        return repository.findEntitiesByConnectionAndConcepts(connectionKey, conceptKeys);
    }

    /** Thin pass-through — AI Knowledge sync watermark: see {@link
     *  SemanticRepository#findEntitiesChangedAfterForConnection}. */
    public List<BusinessEntity> findEntitiesChangedAfterForConnection(String connectionKey, java.time.Instant since) {
        return repository.findEntitiesChangedAfterForConnection(connectionKey, since);
    }

    /**
     * Downstream Context Boundary for Concept-Scoped Metadata Narrowing: the same rendered
     * {@link SemanticContext} shape as {@link #semanticContextWithBindings}, but sourced ONLY
     * from the Business Entities bound to {@code objectKeys} — an already Stage-2-resolved
     * physical scope (see {@code AgentBrain#conceptScopedModel} /
     * {@code ResolvedBusinessModel#conceptScoped()}) — instead of every ACTIVE entity in a
     * domain. This is the fix for the leak traced in the "show me all open orders" investigation:
     * once Stage 1 selects a concept and Stage 2 resolves it to physical objects, every
     * downstream context channel — not just the physical-schema block — must be bounded by that
     * same resolved scope, so an unselected entity (e.g. Purchase Order, when only
     * Sales Transaction was selected) can never re-enter the prompt through this channel.
     *
     * <p>Deliberately entity-only: unlike {@link #semanticContextWithBindings}, this method does
     * not query {@code nexus_operational_vocabulary} — that table has no {@code
     * primary_object_key}/object-key relationship to filter by (only a domain-scoped one), and
     * the Operational Vocabulary/Business Language Resolution mechanism is explicitly out of
     * scope for this fix. Term lines and vocabulary bindings are simply absent from the
     * returned context; the entity block — where the leaked "Purchase Order" text actually
     * originated — is fully scoped.
     *
     * <p>{@code objectKeys} empty/null ⇒ {@link SemanticContext#EMPTY} — a legitimate, honest
     * "nothing in scope" outcome when Stage 1 selected no concept, never a fallback to a
     * broader retrieval.
     */
    public SemanticContext semanticContextForObjectKeys(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return SemanticContext.EMPTY;
        }
        try {
            List<EntityRow> entities = repository.findEntitiesByObjectKeys(objectKeys).stream()
                    .map(e -> new EntityRow(e.entityKey(), e.entityName(), e.entityType(),
                            e.description(), e.operationalMeaning(), e.investigationHints(),
                            e.primaryObjectKey()))
                    .toList();
            return assemble(entities, List.of());
        } catch (Exception e) {
            log.warn("Failed to build concept-scoped semantic context for object keys {}: {}",
                    objectKeys, e.getMessage());
            return SemanticContext.EMPTY;
        }
    }

    /** Thin pass-through to the existing soft-delete ({@code status = 'INACTIVE'}) — the same
     *  operation the Semantic Layer UI's own vocabulary "Delete" action already performs (via a
     *  full re-upsert on the frontend). This status-only variant is used by Industry Pack
     *  removal, which does not have every original field on hand to safely re-upsert with. */
    public void deactivateTerm(String termKey) {
        repository.deactivateTerm(termKey);
    }

    /** Thin pass-through — used by Industry Pack apply to make idempotent vocabulary
     *  reuse/reactivation explicit and observable. */
    public Optional<OperationalVocabulary> findTermByKey(String termKey) {
        return repository.findTermByKey(termKey);
    }

    public EntityLifecycleState addLifecycleState(String entityKey, Map<String, Object> body) {
        String stateKey = body.containsKey("stateKey")
                ? (String) body.get("stateKey")
                : Keys.uniqueKey("state");
        Object normalSeqObj = body.get("normalSequence");
        Integer normalSequence = normalSeqObj != null
                ? Integer.parseInt(String.valueOf(normalSeqObj)) : null;
        EntityLifecycleState state = new EntityLifecycleState(
                stateKey, entityKey,
                (String) body.get("stateName"),
                (String) body.get("stateCode"),
                (String) body.get("meaning"),
                Boolean.parseBoolean(String.valueOf(body.getOrDefault("isTerminal", "false"))),
                Boolean.parseBoolean(String.valueOf(body.getOrDefault("isException", "false"))),
                normalSequence,
                (String) body.get("nextStates"),
                (String) body.get("detectionRule"),
                Instant.now());
        repository.saveLifecycleState(state);
        return state;
    }

    public EntityRelationship addRelationship(String entityKey, Map<String, Object> body) {
        String relationshipKey = body.containsKey("relationshipKey")
                ? (String) body.get("relationshipKey")
                : Keys.uniqueKey("rel");
        EntityRelationship rel = new EntityRelationship(
                relationshipKey, entityKey,
                (String) body.get("targetEntityKey"),
                (String) body.get("relationshipType"),
                (String) body.get("sourceColumn"),
                (String) body.get("targetColumn"),
                (String) body.get("joinGuidance"),
                Boolean.parseBoolean(String.valueOf(body.getOrDefault("crossSystem", "false"))),
                (String) body.get("identityResolution"),
                Instant.now());
        repository.saveRelationship(rel);
        return rel;
    }

    public OperationalVocabulary createTerm(Map<String, Object> body) {
        String termKey = str(body, "termKey", "term_key");
        if (termKey == null || termKey.isBlank()) termKey = Keys.uniqueKey("term");
        Instant now = Instant.now();
        OperationalVocabulary term = new OperationalVocabulary(
                termKey,
                str(body, "domainKey",     "domain_key"),
                str(body, "entityKey",     "entity_key"),
                str(body, "term"),
                str(body, "definition"),
                str(body, "sqlEquivalent", "sql_equivalent"),
                str(body, "examples"),
                str(body, "status") != null ? str(body, "status") : "ACTIVE",
                now, now);
        repository.saveTerm(term);
        return term;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Reads the first non-null value for a list of key aliases.
     *  Accepts both camelCase and snake_case callers without duplicating logic. */
    private String str(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object v = body.get(key);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return null;
    }

    public EntityDataMapping addMapping(String entityKey, Map<String, Object> body) {
        String mappingKey = body.containsKey("mappingKey")
                ? (String) body.get("mappingKey")
                : Keys.uniqueKey("map");
        EntityDataMapping mapping = new EntityDataMapping(
                mappingKey, entityKey,
                (String) body.get("objectKey"),
                (String) body.get("fieldMappings"),
                (String) body.get("identityColumns"),
                Boolean.parseBoolean(String.valueOf(body.getOrDefault("isPrimary", "false"))),
                Instant.now());
        repository.saveMapping(mapping);
        return mapping;
    }
}
