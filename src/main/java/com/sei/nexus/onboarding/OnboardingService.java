package com.sei.nexus.onboarding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.enterprise.EnterpriseMapService;
import com.sei.nexus.semantic.SemanticService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.sei.nexus.sql.DynamicSqlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates the self-serve onboarding flow:
 * scan → AI analysis → bulk apply → mark complete.
 */
@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    private static final String KEY_COMPLETED  = "onboarding_completed";
    private static final String KEY_QUESTIONS  = "onboarding_suggested_questions";

    private final TenantSettingsRepository settings;
    private final ConnectionRepository     connectionRepository;
    private final DynamicSqlService        dynamicSqlService;
    private final EnterpriseMapService     enterpriseMapService;
    private final SemanticService          semanticService;
    private final AzureOpenAiClient        aiClient;
    private final ObjectMapper             objectMapper;
    private final JdbcTemplate             jdbc;

    public OnboardingService(TenantSettingsRepository settings,
                              ConnectionRepository connectionRepository,
                              DynamicSqlService dynamicSqlService,
                              EnterpriseMapService enterpriseMapService,
                              SemanticService semanticService,
                              AzureOpenAiClient aiClient,
                              ObjectMapper objectMapper,
                              JdbcTemplate jdbc) {
        this.settings             = settings;
        this.connectionRepository = connectionRepository;
        this.dynamicSqlService    = dynamicSqlService;
        this.enterpriseMapService = enterpriseMapService;
        this.semanticService      = semanticService;
        this.aiClient             = aiClient;
        this.objectMapper         = objectMapper;
        this.jdbc                 = jdbc;
    }

    // ── Status ────────────────────────────────────────────────────────────────

    /**
     * Derives the current onboarding step from data in the tenant schema.
     * Returns a map with: complete, step, connection_count, data_object_count,
     * entity_count, suggested_questions.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. Explicit completion flag set by the wizard
        boolean complete = settings.isTrue(KEY_COMPLETED);

        // 2. Auto-complete: if the tenant already has business entities configured
        //    (e.g. the default tenant seeded by V007, or any tenant that was set up
        //    outside the wizard), skip onboarding entirely.
        if (!complete) {
            try {
                Integer entityCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM nexus_business_entity WHERE status != 'ARCHIVED'",
                        Integer.class);
                if (entityCount != null && entityCount > 0) {
                    settings.set(KEY_COMPLETED, "true");
                    complete = true;
                }
            } catch (Exception ignored) {
                // Table may not exist yet on very first startup — fail open
            }
        }

        result.put("complete", complete);
        long connCount = connectionRepository.findAll().size();
        result.put("connection_count", connCount);

        if (complete) {
            result.put("step", "COMPLETE");
            settings.get(KEY_QUESTIONS).ifPresent(q -> {
                try {
                    result.put("suggested_questions",
                            objectMapper.readValue(q, new TypeReference<List<String>>() {}));
                } catch (Exception ignored) {
                    result.put("suggested_questions", List.of());
                }
            });
            if (!result.containsKey("suggested_questions")) {
                result.put("suggested_questions", List.of());
            }
            return result;
        }

        result.put("step", connCount == 0 ? "CONNECT_DATABASE" : "SELECT_TABLES");
        result.put("suggested_questions", List.of());
        return result;
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    /**
     * Lists all tables in the given schema of the given connection.
     * Each entry contains table_name and column_count.
     */
    public List<Map<String, Object>> scanTables(String connectionKey, String schemaName) {
        List<Map<String, Object>> rawTables =
                dynamicSqlService.listTables(connectionKey, schemaName, "");

        return rawTables.stream().map(row -> {
            String tableName = (String) row.getOrDefault("table_name",
                               row.values().stream().findFirst().orElse("unknown"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("table_name", tableName);
            // Column count — do a lightweight describe
            try {
                int cols = dynamicSqlService.describeTable(
                        connectionKey, schemaName, tableName).size();
                entry.put("column_count", cols);
            } catch (Exception e) {
                entry.put("column_count", 0);
            }
            return entry;
        }).collect(Collectors.toList());
    }

    // ── Analyse ───────────────────────────────────────────────────────────────

    /**
     * For each selected table, reads the live schema and asks the AI to
     * produce: entity name, purpose, investigation hints, vocabulary terms,
     * and 3 suggested investigative questions a business analyst might ask.
     */
    public List<Map<String, Object>> analyzeTables(String connectionKey,
                                                     String schemaName,
                                                     String domainKey,
                                                     List<String> tableNames) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (String tableName : tableNames) {
            try {
                List<Map<String, Object>> columns =
                        dynamicSqlService.describeTable(connectionKey, schemaName, tableName);

                String schemaText = buildSchemaText(schemaName, tableName, columns);

                String systemPrompt = """
                        You are an enterprise data analyst onboarding a new database into an
                        operational intelligence platform. Analyse the table schema and respond
                        with valid JSON only — no prose, no markdown fences.

                        Required JSON structure:
                        {
                          "entityName": "Human-readable singular noun, e.g. Order",
                          "purpose": "One sentence describing what this table stores",
                          "operationalMeaning": "Two sentences on how this table is used operationally",
                          "investigationHints": "SQL hint a business analyst would use, e.g. SELECT ... FROM ... WHERE status='X'",
                          "vocabularySuggestions": [
                            { "term": "business term", "definition": "plain-English definition", "sqlEquivalent": "WHERE clause or expression" }
                          ],
                          "suggestedQuestions": [
                            "Plain-English question a manager might ask about this data",
                            "Another operational question",
                            "A third question focused on anomalies or performance"
                          ],
                          "readinessScore": 0.0
                        }

                        Rules:
                        - suggestedQuestions must be 3 natural-language questions, industry-agnostic.
                        - vocabularySuggestions: 2-4 key business terms from this table.
                        - readinessScore: 0.0-1.0 reflecting how well the schema reveals intent.
                        """;

                String userMessage = "Domain: " + domainKey + "\n" + schemaText;
                String analysisJson = aiClient.chatWithJson(
                        List.of(ChatMessage.user(userMessage)), systemPrompt);

                Map<String, Object> analysis = parseJson(analysisJson);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("table_name",   tableName);
                entry.put("schema_name",  schemaName);
                entry.put("connection_key", connectionKey);
                entry.put("domain_key",   domainKey);
                entry.put("columns",      columns);
                entry.put("entity_key",   slugify(
                        (String) analysis.getOrDefault("entityName", tableName)));
                entry.putAll(analysis);
                results.add(entry);

            } catch (Exception e) {
                log.warn("Analysis failed for table {}: {}", tableName, e.getMessage());
                Map<String, Object> errorEntry = new LinkedHashMap<>();
                errorEntry.put("table_name",  tableName);
                errorEntry.put("error",        e.getMessage());
                errorEntry.put("entity_key",   slugify(tableName));
                errorEntry.put("entityName",   toTitleCase(tableName));
                errorEntry.put("purpose",      "");
                errorEntry.put("suggestedQuestions", List.of());
                errorEntry.put("vocabularySuggestions", List.of());
                results.add(errorEntry);
            }
        }

        return results;
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    /**
     * Bulk-saves the approved entities from the review step:
     * <ol>
     *   <li>Creates a data object (+ scans columns) for each approved entity.</li>
     *   <li>Creates the business entity in the semantic layer.</li>
     *   <li>Creates approved vocabulary terms.</li>
     *   <li>Stores suggested questions in tenant settings.</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> applySelections(Map<String, Object> request, String userEmail) {
        String connectionKey = (String) request.get("connectionKey");
        String schemaName    = (String) request.get("schemaName");
        String domainKey     = (String) request.get("domainKey");
        List<Map<String, Object>> entities =
                (List<Map<String, Object>>) request.getOrDefault("entities", List.of());

        int entitiesCreated = 0;
        int vocabCreated    = 0;
        int objectsCreated  = 0;
        List<String> allQuestions = new ArrayList<>();

        for (Map<String, Object> entity : entities) {
            if (!Boolean.TRUE.equals(entity.get("approved"))) continue;

            String tableName  = (String) entity.get("tableName");
            String entityKey  = (String) entity.getOrDefault("entityKey", slugify(tableName));
            String entityName = (String) entity.getOrDefault("entityName", toTitleCase(tableName));
            String purpose    = (String) entity.getOrDefault("purpose", "");
            String opMeaning  = (String) entity.getOrDefault("operationalMeaning", "");
            String hints      = (String) entity.getOrDefault("investigationHints", "");

            // 1. Create enterprise map data object (scans columns automatically)
            try {
                Map<String, Object> objBody = new LinkedHashMap<>();
                objBody.put("domainKey",     domainKey);
                objBody.put("connectionKey", connectionKey);
                objBody.put("schemaName",    schemaName);
                objBody.put("tableName",     tableName);
                objBody.put("entityName",    entityName);
                objBody.put("businessName",  entityName + "s");
                objBody.put("purpose",       purpose);
                enterpriseMapService.createOrUpdateObject(objBody, userEmail);
                objectsCreated++;
            } catch (Exception e) {
                log.warn("Failed to create data object for {}: {}", tableName, e.getMessage());
            }

            // 2. Create semantic business entity
            try {
                Map<String, Object> entityBody = new LinkedHashMap<>();
                entityBody.put("entity_key",          entityKey);
                entityBody.put("entity_name",         entityName);
                entityBody.put("description",         purpose);
                entityBody.put("operational_meaning", opMeaning);
                entityBody.put("investigation_hints", hints);
                entityBody.put("domain_key",          domainKey);
                entityBody.put("node_type",           "ENTITY");
                entityBody.put("status",              "ACTIVE");
                semanticService.createOrUpdateEntity(entityBody, userEmail);
                entitiesCreated++;
            } catch (Exception e) {
                log.warn("Failed to create entity {}: {}", entityKey, e.getMessage());
            }

            // 3. Create approved vocabulary terms
            List<Map<String, Object>> vocab =
                    (List<Map<String, Object>>) entity.getOrDefault("vocabulary", List.of());
            for (Map<String, Object> term : vocab) {
                if (!Boolean.TRUE.equals(term.get("approved"))) continue;
                try {
                    Map<String, Object> termBody = new LinkedHashMap<>();
                    termBody.put("term_key",       slugify((String) term.get("term")) + "-" + entityKey);
                    termBody.put("term",           term.get("term"));
                    termBody.put("definition",     term.get("definition"));
                    termBody.put("sql_equivalent", term.getOrDefault("sqlEquivalent", ""));
                    termBody.put("domain_key",     domainKey);
                    termBody.put("status",         "ACTIVE");
                    semanticService.createTerm(termBody);
                    vocabCreated++;
                } catch (Exception e) {
                    log.warn("Failed to create vocab term: {}", e.getMessage());
                }
            }

            // Collect suggested questions
            List<String> qs = (List<String>) entity.getOrDefault("suggestedQuestions", List.of());
            allQuestions.addAll(qs);
        }

        // 4. Persist the best 3 suggested questions
        List<String> topQuestions = allQuestions.stream().distinct().limit(3)
                .collect(Collectors.toList());
        try {
            settings.set(KEY_QUESTIONS, objectMapper.writeValueAsString(topQuestions));
        } catch (Exception e) {
            log.warn("Failed to store suggested questions: {}", e.getMessage());
        }

        return Map.of(
                "entities_created",      entitiesCreated,
                "vocab_terms_created",   vocabCreated,
                "data_objects_created",  objectsCreated,
                "suggested_questions",   topQuestions
        );
    }

    // ── Complete ──────────────────────────────────────────────────────────────

    /**
     * Marks onboarding as complete. Returns the stored suggested questions.
     */
    public Map<String, Object> complete() {
        settings.set(KEY_COMPLETED, "true");
        List<String> questions = settings.get(KEY_QUESTIONS)
                .map(q -> {
                    try {
                        return objectMapper.<List<String>>readValue(
                                q, new TypeReference<>() {});
                    } catch (Exception e) {
                        return List.<String>of();
                    }
                })
                .orElse(List.of());
        return Map.of("status", "COMPLETE", "suggested_questions", questions);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildSchemaText(String schema, String table,
                                    List<Map<String, Object>> columns) {
        StringBuilder sb = new StringBuilder();
        sb.append("Schema: ").append(schema).append(", Table: ").append(table).append("\n");
        sb.append("Columns:\n");
        for (Map<String, Object> col : columns) {
            Object nullableVal = col.getOrDefault("is_nullable", col.get("isNullable"));
            boolean nullable   = "YES".equalsIgnoreCase(String.valueOf(nullableVal))
                               || Boolean.TRUE.equals(nullableVal);
            sb.append("  - ")
              .append(col.getOrDefault("column_name", col.get("columnName")))
              .append(" ")
              .append(col.getOrDefault("data_type",   col.get("dataType")))
              .append(nullable ? " NULL" : " NOT NULL")
              .append("\n");
        }
        return sb.toString();
    }

    private Map<String, Object> parseJson(String json) {
        try {
            String extracted = extractJson(json);
            return objectMapper.readValue(extracted, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse AI response: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : "{}";
    }

    private String slugify(String input) {
        if (input == null) return "entity";
        return input.toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "")
                    .substring(0, Math.min(input.length(), 80));
    }

    private String toTitleCase(String input) {
        if (input == null || input.isBlank()) return "Entity";
        String[] words = input.replace('_', ' ').replace('-', ' ').split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) sb.append(w.substring(1).toLowerCase());
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }
}
