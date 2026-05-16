package com.sei.nexus.semantic;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.common.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SemanticService {

    private static final Logger log = LoggerFactory.getLogger(SemanticService.class);

    private static final String FIND_ENTITIES =
            "SELECT entity_key, entity_name, entity_type, description, domain_key, " +
            "canonical_identifiers, synonyms, status " +
            "FROM nexus_business_entity WHERE domain_key = ANY(?::text[]) AND status = 'ACTIVE' LIMIT 50";

    private static final String FIND_VOCABULARY =
            "SELECT term, definition, synonyms, domain_key " +
            "FROM nexus_operational_vocabulary WHERE domain_key = ANY(?::text[]) LIMIT 30";

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
                        String name = rs.getString("entity_name");
                        String type = rs.getString("entity_type");
                        String desc = rs.getString("description");
                        String synonyms = rs.getString("synonyms");
                        String id = rs.getString("canonical_identifiers");
                        StringBuilder row = new StringBuilder(name).append(" (").append(type).append(")");
                        if (desc != null && !desc.isBlank()) row.append(": ").append(desc);
                        if (synonyms != null && !synonyms.isBlank()) row.append(" [also: ").append(synonyms).append("]");
                        if (id != null && !id.isBlank()) row.append(" [id: ").append(id).append("]");
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
                        String def = rs.getString("definition");
                        String synonyms = rs.getString("synonyms");
                        String row = term + ": " + def;
                        return (synonyms != null && !synonyms.isBlank()) ? row + " [synonyms: " + synonyms + "]" : row;
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
        String entityKey = body.containsKey("entityKey")
                ? (String) body.get("entityKey")
                : Keys.uniqueKey("entity");
        Instant now = Instant.now();
        BusinessEntity entity = new BusinessEntity(
                entityKey,
                (String) body.get("domainKey"),
                (String) body.get("entityName"),
                (String) body.get("description"),
                (String) body.get("primaryObjectKey"),
                (String) body.get("operationalMeaning"),
                (String) body.get("investigationHints"),
                (String) body.getOrDefault("status", "ACTIVE"),
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
        String termKey = body.containsKey("termKey")
                ? (String) body.get("termKey")
                : Keys.uniqueKey("term");
        Instant now = Instant.now();
        OperationalVocabulary term = new OperationalVocabulary(
                termKey,
                (String) body.get("domainKey"),
                (String) body.get("entityKey"),
                (String) body.get("term"),
                (String) body.get("definition"),
                (String) body.get("sqlEquivalent"),
                (String) body.get("examples"),
                (String) body.getOrDefault("status", "ACTIVE"),
                now, now);
        repository.saveTerm(term);
        return term;
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
