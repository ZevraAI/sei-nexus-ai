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
     * rendered entity block in EnterpriseMapService.operationalContext.
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
                now, now);
        repository.saveEntity(entity);
        return repository.findEntityByKey(entityKey).orElseThrow();
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
