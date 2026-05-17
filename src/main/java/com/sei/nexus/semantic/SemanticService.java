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
            "operational_meaning, investigation_hints, status " +
            "FROM nexus_business_entity WHERE domain_key = ANY(?::text[]) AND status = 'ACTIVE' LIMIT 50";

    private static final String FIND_VOCABULARY =
            "SELECT term, definition, sql_equivalent " +
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
     * Builds a semantic context string for a given question within the specified domains.
     * Returns relevant entity definitions and vocabulary that help answer the question.
     */
    public String buildSemanticContext(List<String> domainKeys, String question) {
        if (domainKeys == null || domainKeys.isEmpty()) {
            return "";
        }
        try {
            StringBuilder sb = new StringBuilder();

            // Load business entities
            String[] domainArray = domainKeys.toArray(new String[0]);
            List<String> entityRows = jdbc.query(FIND_ENTITIES,
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", domainArray)),
                    (rs, rowNum) -> {
                        String name    = rs.getString("entity_name");
                        String type    = rs.getString("node_type");
                        String desc    = rs.getString("description");
                        String meaning = rs.getString("operational_meaning");
                        String hints   = rs.getString("investigation_hints");
                        StringBuilder row = new StringBuilder(name);
                        if (type != null && !type.isBlank()) row.append(" (").append(type).append(")");
                        if (desc != null && !desc.isBlank()) row.append(": ").append(desc);
                        if (meaning != null && !meaning.isBlank()) row.append(" | ").append(meaning);
                        if (hints != null && !hints.isBlank()) row.append(" | Hint: ").append(hints);
                        return row.toString();
                    });

            if (!entityRows.isEmpty()) {
                sb.append("=== Business Entities ===\n");
                entityRows.forEach(e -> sb.append("- ").append(e).append("\n"));
                sb.append("\n");
            }

            // Load vocabulary
            List<String> vocabRows = jdbc.query(FIND_VOCABULARY,
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", domainArray)),
                    (rs, rowNum) -> {
                        String term = rs.getString("term");
                        String def  = rs.getString("definition");
                        String sql  = rs.getString("sql_equivalent");
                        String row  = term + ": " + def;
                        return (sql != null && !sql.isBlank()) ? row + " [SQL: " + sql + "]" : row;
                    });

            if (!vocabRows.isEmpty()) {
                sb.append("=== Operational Vocabulary ===\n");
                vocabRows.forEach(v -> sb.append("- ").append(v).append("\n"));
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to build semantic context for domains {}: {}", domainKeys, e.getMessage());
            return "";
        }
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
