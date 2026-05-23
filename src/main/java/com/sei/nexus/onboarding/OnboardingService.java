package com.sei.nexus.onboarding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agent.AgentRepository;
import com.sei.nexus.agent.AgentService;
import com.sei.nexus.semantic.RelationshipDiscoveryService;
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
import java.util.Optional;
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
    private final AgentService                agentService;
    private final AgentRepository             agentRepository;
    private final RelationshipDiscoveryService relationshipDiscovery;
    private final com.sei.nexus.pack.IndustryPackService industryPackService;

    public OnboardingService(TenantSettingsRepository settings,
                              ConnectionRepository connectionRepository,
                              DynamicSqlService dynamicSqlService,
                              EnterpriseMapService enterpriseMapService,
                              SemanticService semanticService,
                              AzureOpenAiClient aiClient,
                              ObjectMapper objectMapper,
                              JdbcTemplate jdbc,
                              AgentService agentService,
                              AgentRepository agentRepository,
                              RelationshipDiscoveryService relationshipDiscovery,
                              com.sei.nexus.pack.IndustryPackService industryPackService) {
        this.settings              = settings;
        this.connectionRepository  = connectionRepository;
        this.dynamicSqlService     = dynamicSqlService;
        this.enterpriseMapService  = enterpriseMapService;
        this.semanticService       = semanticService;
        this.aiClient              = aiClient;
        this.objectMapper          = objectMapper;
        this.jdbc                  = jdbc;
        this.agentService          = agentService;
        this.agentRepository       = agentRepository;
        this.relationshipDiscovery = relationshipDiscovery;
        this.industryPackService   = industryPackService;
    }

    // ── Status ────────────────────────────────────────────────────────────────

    /**
     * Derives the current onboarding step from data in the tenant schema.
     * Returns a map with: complete, step, connection_count, data_object_count,
     * entity_count, suggested_questions.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. Explicit completion flag set by the wizard.
        //    Wrapped in try-catch: if nexus_tenant_settings doesn't exist yet
        //    (tenant schema not fully migrated), treat as "not complete" rather
        //    than throwing a 500 that silently skips the wizard in the frontend.
        boolean complete;
        try {
            complete = settings.isTrue(KEY_COMPLETED);
        } catch (Exception e) {
            log.warn("Could not read onboarding_completed setting (schema may be incomplete): {}", e.getMessage());
            complete = false;
        }

        // 2. Auto-complete for tenants configured outside the wizard.
        //
        //    V007 migration seeds logistics demo entities with created_by = 'system'.
        //    User-created entities (via wizard or Semantic Layer) carry the user's
        //    email in created_by. Filtering on created_by != 'system' means system
        //    seed data never suppresses the wizard for genuine new customers.
        if (!complete) {
            try {
                Integer userEntityCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM nexus_business_entity " +
                        "WHERE status != 'ARCHIVED' AND created_by != 'system'",
                        Integer.class);
                if (userEntityCount != null && userEntityCount > 0) {
                    settings.set(KEY_COMPLETED, "true");
                    complete = true;
                }
            } catch (Exception ignored) {
                // Fail open — table may not exist on very first startup
            }
        }

        result.put("complete", complete);

        long connCount;
        try {
            connCount = connectionRepository.findAll().size();
        } catch (Exception e) {
            log.warn("Could not count connections: {}", e.getMessage());
            connCount = 0;
        }
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

            // ── Phase 4: include pack recommendation ───────────────────────────
            // Only recommend if no pack has been applied yet
            try {
                List<com.sei.nexus.pack.TenantPack> appliedPacks = industryPackService.listAppliedPacks();
                result.put("applied_packs", appliedPacks.stream()
                        .map(tp -> java.util.Map.of(
                                "pack_key",      tp.packKey(),
                                "display_name",  tp.displayName() != null ? tp.displayName() : "",
                                "coverage_score", tp.coverageScore() != null ? tp.coverageScore() : 0.0))
                        .toList());

                if (appliedPacks.isEmpty()) {
                    industryPackService.recommendForCurrentTenant("PLATFORM").ifPresent(rec -> {
                        java.util.Map<String, Object> packRec = new java.util.LinkedHashMap<>();
                        packRec.put("pack_key",       rec.packKey());
                        packRec.put("display_name",   rec.displayName());
                        packRec.put("coverage_score", rec.coverageScore());
                        packRec.put("matched_tables", rec.matchedTables());
                        result.put("recommended_pack", packRec);
                    });
                }
            } catch (Exception e) {
                log.debug("Pack recommendation failed during status check: {}", e.getMessage());
            }

            return result;
        }

        result.put("step", connCount == 0 ? "CONNECT_DATABASE" : "SELECT_TABLES");
        result.put("suggested_questions", List.of());
        return result;
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    /**
     * Lists all tables in the given schema with their column counts.
     *
     * <p><strong>Scalability:</strong> Uses a single SQL query against
     * {@code information_schema} to fetch table names and column counts for
     * ALL tables in one round-trip — O(1) database calls regardless of table
     * count. The previous implementation made one {@code describeTable()} call
     * per table (N+1 pattern), which would take 500+ seconds for a large schema.
     */
    public List<Map<String, Object>> scanTables(String connectionKey, String schemaName) {
        return dynamicSqlService.listTablesWithColumnCounts(connectionKey, schemaName);
    }

    // ── Recommend ─────────────────────────────────────────────────────────────

    /**
     * AI-powered table recommendation for large databases.
     *
     * <p><strong>Scalability design:</strong>
     * <ul>
     *   <li>One SQL query fetches ALL table metadata (names + column counts +
     *       column name list) regardless of schema size — O(1) DB round-trips.</li>
     *   <li>One AI call analyses the entire schema at once and returns the top 15
     *       recommended tables — O(1) API calls regardless of table count.</li>
     *   <li>Result is cached in {@code nexus_tenant_settings} keyed by
     *       connection+schema so repeat calls (browser refresh, re-render) are free.</li>
     * </ul>
     *
     * @return map containing {@code recommended} list, {@code total_tables} count,
     *         and {@code cached} flag indicating whether the result came from cache.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> recommendTables(String connectionKey, String schemaName) {
        String cacheKey = "onboarding_recommend_" + connectionKey + "_" + schemaName;

        // Return cached result if available — avoids re-running the AI call on
        // every wizard render. Cache is invalidated when a new connection is added.
        Optional<String> cached = settings.get(cacheKey);
        if (cached.isPresent()) {
            try {
                Map<String, Object> result = objectMapper.readValue(
                        cached.get(), new TypeReference<>() {});
                result.put("cached", true);
                return result;
            } catch (Exception ignored) {
                // Corrupt cache entry — fall through to regenerate
            }
        }

        // 1. Single query: all tables with column counts and column names
        List<Map<String, Object>> allTables =
                dynamicSqlService.listTablesWithColumnCounts(connectionKey, schemaName);

        if (allTables.isEmpty()) {
            return Map.of("recommended", List.of(), "total_tables", 0, "cached", false);
        }

        // 2. Build a compact representation for the AI prompt.
        //    Format: "table_name (N cols): col1, col2, col3..."
        //    Truncate column list to first 10 names to keep the prompt concise.
        StringBuilder tableList = new StringBuilder();
        for (Map<String, Object> t : allTables) {
            String name    = String.valueOf(t.getOrDefault("table_name", ""));
            Object colCnt  = t.getOrDefault("column_count", 0);
            String colNames= String.valueOf(t.getOrDefault("column_names", ""));
            // Truncate long column lists to keep prompt size manageable
            String truncated = truncateColumnList(colNames, 10);
            tableList.append(name).append(" (").append(colCnt).append(" cols): ")
                     .append(truncated).append("\n");
        }

        // 3. Single AI call — analyse the entire schema in one prompt
        String systemPrompt = """
                You are an enterprise data analyst helping onboard a large database into an
                operational intelligence platform. The database has many tables.
                Identify the 10-15 most important BUSINESS tables for initial setup.

                Prioritise tables that:
                - Represent core business entities (customers, transactions, products, employees, contracts, locations, events, assets, appointments, reservations)
                - Are frequently referenced by other tables (indicated by "id" columns matching other table names)
                - Have meaningful business column names (not just technical fields)

                Exclude system/audit tables: audit_*, *_log, *_logs, *_history, tmp_*, staging_*,
                flyway_*, pg_*, _*, *_archive, *_backup.

                Respond with valid JSON only:
                {
                  "recommended": [
                    {
                      "table_name": "exact_table_name",
                      "reason": "one sentence explaining why this is important",
                      "category": "Customers|Transactions|Finance|Operations|Products|HR|Other",
                      "priority": 1
                    }
                  ]
                }
                Order by priority (1 = most important). Return 10-15 items maximum.
                """;

        String userMessage = "Schema: " + schemaName + "\nTotal tables: " + allTables.size()
                + "\n\nTables (name, column count, column names):\n" + tableList;

        List<Map<String, Object>> recommended;
        try {
            String aiResponse = aiClient.chatWithJson(
                    List.of(ChatMessage.user(userMessage)), systemPrompt);
            Map<String, Object> parsed = parseJson(aiResponse);
            recommended = (List<Map<String, Object>>) parsed.getOrDefault(
                    "recommended", List.of());
        } catch (Exception e) {
            log.warn("AI recommendation failed for {}/{}: {}", connectionKey, schemaName, e.getMessage());
            // Graceful degradation: return first 15 tables alphabetically
            recommended = allTables.stream()
                    .limit(15)
                    .map(t -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("table_name", t.get("table_name"));
                        entry.put("reason",     "Suggested based on schema position");
                        entry.put("category",   "Other");
                        entry.put("priority",   allTables.indexOf(t) + 1);
                        return entry;
                    })
                    .collect(Collectors.toList());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recommended",   recommended);
        result.put("total_tables",  allTables.size());
        result.put("cached",        false);

        // 4. Cache result — expires with next tenant settings reset
        try {
            settings.set(cacheKey, objectMapper.writeValueAsString(result));
        } catch (Exception ignored) {}

        return result;
    }

    private String truncateColumnList(String columnNames, int maxColumns) {
        if (columnNames == null || columnNames.isBlank()) return "";
        String[] parts = columnNames.split(",\\s*");
        if (parts.length <= maxColumns) return columnNames;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxColumns; i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts[i].trim());
        }
        sb.append(" … +").append(parts.length - maxColumns).append(" more");
        return sb.toString();
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

            // 1. Create enterprise map data object — capture its key for the entity link
            String objectKey = null;
            try {
                Map<String, Object> objBody = new LinkedHashMap<>();
                objBody.put("domainKey",     domainKey);
                objBody.put("connectionKey", connectionKey);
                objBody.put("schemaName",    schemaName);
                objBody.put("tableName",     tableName);
                objBody.put("entityName",    entityName);
                objBody.put("businessName",  entityName + "s");
                objBody.put("purpose",       purpose);
                var dataObj = enterpriseMapService.createOrUpdateObject(objBody, userEmail);
                objectKey = dataObj.objectKey();
                objectsCreated++;
            } catch (Exception e) {
                log.warn("Failed to create data object for {}: {}", tableName, e.getMessage());
            }

            // 2. Create semantic business entity — link to the data object via primaryObjectKey
            try {
                Map<String, Object> entityBody = new LinkedHashMap<>();
                entityBody.put("entityKey",          entityKey);
                entityBody.put("entityName",         entityName);
                entityBody.put("description",        purpose);
                entityBody.put("operationalMeaning", opMeaning);
                entityBody.put("investigationHints", hints);
                entityBody.put("domainKey",          domainKey);
                entityBody.put("status",             "ACTIVE");
                if (objectKey != null) {
                    entityBody.put("primaryObjectKey", objectKey);
                }
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
                    termBody.put("termKey",      slugify((String) term.get("term")) + "-" + entityKey);
                    termBody.put("term",         term.get("term"));
                    termBody.put("definition",   term.get("definition"));
                    termBody.put("sqlEquivalent", term.getOrDefault("sqlEquivalent", ""));
                    termBody.put("domainKey",    domainKey);
                    termBody.put("entityKey",    entityKey);
                    termBody.put("status",       "ACTIVE");
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

        // 5. Auto-discover entity relationships from FK constraints and column-name heuristics.
        //    Runs after all entities are created so the table→entity index is populated.
        if (connectionKey != null && !connectionKey.isBlank()) {
            try {
                String schema = request.containsKey("schemaName")
                        ? (String) request.get("schemaName") : "public";
                int rels = relationshipDiscovery.discoverAndPersist(connectionKey, schema, domainKey);
                log.info("Auto-discovered {} relationships for domain '{}'", rels, domainKey);
            } catch (Exception e) {
                log.warn("Relationship auto-discovery failed (non-fatal): {}", e.getMessage());
            }
        }

        // 6. Auto-create a default Data Analyst agent if no active agents exist yet.
        //    Only runs once per tenant — skipped if the user has already configured agents.
        try {
            if (agentRepository.findActive().isEmpty()) {
                Map<String, Object> agentBody = new LinkedHashMap<>();
                agentBody.put("agentKey",       "data-analyst");
                agentBody.put("name",           "Data Analyst");
                agentBody.put("purpose",        "Answers operational intelligence questions using your enterprise data");
                agentBody.put("domainKeys",     domainKey != null ? domainKey : "");
                agentBody.put("connectionKeys", connectionKey != null ? connectionKey : "");
                agentBody.put("actionScope",    "READ_ONLY");
                agentBody.put("restApiEnabled", false);
                agentService.createOrUpdate(agentBody, userEmail);
                log.info("Auto-created default Data Analyst agent for domain {}", domainKey);
            }
        } catch (Exception e) {
            log.warn("Failed to auto-create default agent: {}", e.getMessage());
        }

        return Map.of(
                "entities_created",      entitiesCreated,
                "vocab_terms_created",   vocabCreated,
                "data_objects_created",  objectsCreated,
                "suggested_questions",   topQuestions
        );
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    /**
     * Wipes all onboarding-generated data so the wizard can be re-run.
     * Deletes: settings flags, user-created entities + vocab + data objects,
     * entity relationships, and the auto-created default agent.
     * System-seeded rows (created_by = 'system') are preserved.
     */
    public Map<String, Object> reset() {
        int deletedSettings  = 0;
        int deletedEntities  = 0;
        int deletedVocab     = 0;
        int deletedObjects   = 0;
        int deletedAgents    = 0;

        try {
            // Agent children first (FK order)
            jdbc.update("DELETE FROM nexus_agent_kpi      WHERE agent_key = 'data-analyst'");
            jdbc.update("DELETE FROM nexus_agent_playbook WHERE agent_key = 'data-analyst'");
            jdbc.update("DELETE FROM nexus_agent_version  WHERE agent_key = 'data-analyst'");
            deletedAgents = jdbc.update("DELETE FROM nexus_agent WHERE agent_key = 'data-analyst'");
        } catch (Exception e) {
            log.warn("Reset: agent cleanup failed: {}", e.getMessage());
        }

        try {
            // Semantic entity children
            jdbc.update("""
                DELETE FROM nexus_entity_lifecycle_state
                WHERE entity_key IN (SELECT entity_key FROM nexus_business_entity WHERE created_by != 'system')
                """);
            jdbc.update("""
                DELETE FROM nexus_entity_data_mapping
                WHERE entity_key IN (SELECT entity_key FROM nexus_business_entity WHERE created_by != 'system')
                """);
            jdbc.update("""
                DELETE FROM nexus_entity_relationship
                WHERE source_entity_key IN (SELECT entity_key FROM nexus_business_entity WHERE created_by != 'system')
                   OR target_entity_key IN (SELECT entity_key FROM nexus_business_entity WHERE created_by != 'system')
                """);
            deletedVocab    = jdbc.update("DELETE FROM nexus_operational_vocabulary WHERE created_by != 'system'");
            deletedEntities = jdbc.update("DELETE FROM nexus_business_entity        WHERE created_by != 'system'");
        } catch (Exception e) {
            log.warn("Reset: entity cleanup failed: {}", e.getMessage());
        }

        try {
            // Enterprise map objects
            jdbc.update("""
                DELETE FROM nexus_data_column
                WHERE object_key IN (SELECT object_key FROM nexus_data_object WHERE created_by != 'system')
                """);
            jdbc.update("""
                DELETE FROM nexus_data_object_version
                WHERE object_key IN (SELECT object_key FROM nexus_data_object WHERE created_by != 'system')
                """);
            deletedObjects = jdbc.update("DELETE FROM nexus_data_object WHERE created_by != 'system'");
        } catch (Exception e) {
            log.warn("Reset: data object cleanup failed: {}", e.getMessage());
        }

        try {
            deletedSettings = jdbc.update(
                    "DELETE FROM nexus_tenant_settings WHERE setting_key LIKE 'onboarding%'");
        } catch (Exception e) {
            log.warn("Reset: settings cleanup failed: {}", e.getMessage());
        }

        log.info("Onboarding reset: {} settings, {} entities, {} vocab, {} objects, {} agents deleted",
                deletedSettings, deletedEntities, deletedVocab, deletedObjects, deletedAgents);

        return Map.of(
                "settings_deleted",       deletedSettings,
                "entities_deleted",       deletedEntities,
                "vocab_deleted",          deletedVocab,
                "data_objects_deleted",   deletedObjects,
                "agents_deleted",         deletedAgents,
                "status",                 "RESET"
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
