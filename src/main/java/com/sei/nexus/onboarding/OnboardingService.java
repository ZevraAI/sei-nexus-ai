package com.sei.nexus.onboarding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agent.AgentRepository;
import com.sei.nexus.agent.AgentService;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.common.Keys;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.enterprise.EnterpriseMapService;
import com.sei.nexus.reasoning.ReasoningEventBus;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import com.sei.nexus.sql.DynamicSqlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
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
    private final MetadataRegistrationService metadataRegistration;
    private final com.sei.nexus.semantic.EntityCandidateService entityCandidates;
    private final com.sei.nexus.pack.IndustryPackService industryPackService;
    private final OnboardingAnalysisJobRepository jobRepository;
    private final ReasoningEventBus        eventBus;
    private final Executor                 onboardingJobExecutor;
    // Multi-Table Analysis Hardening: the shared batched Business Object analysis mechanism,
    // also used by EnterpriseMapService.analyzeForOnboarding() (Discover from DB) — neither
    // service calls the other; both call this.
    private final com.sei.nexus.prompt.BusinessObjectBatchAnalyzer batchAnalyzer;

    // How many tables go into ONE AI call — the dominant lever against rate
    // limits, since a 15-17 table onboarding otherwise makes 15-17 separate
    // calls (see analyzeTableBatch). Mirrors recommendTables()'s existing
    // "many tables, one call" shape.
    @Value("${nexus.onboarding.batch-size:4}")
    private int batchSize;

    // How many BATCHES run concurrently per job — lower than the old
    // per-table concurrency default, since each unit of work is heavier now.
    @Value("${nexus.onboarding.batch-concurrency:2}")
    private int batchConcurrency;

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
                              MetadataRegistrationService metadataRegistration,
                              com.sei.nexus.semantic.EntityCandidateService entityCandidates,
                              com.sei.nexus.pack.IndustryPackService industryPackService,
                              OnboardingAnalysisJobRepository jobRepository,
                              ReasoningEventBus eventBus,
                              @Qualifier("onboardingJobExecutor") Executor onboardingJobExecutor,
                              com.sei.nexus.prompt.BusinessObjectBatchAnalyzer batchAnalyzer) {
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
        this.metadataRegistration  = metadataRegistration;
        this.entityCandidates      = entityCandidates;
        this.industryPackService   = industryPackService;
        this.jobRepository         = jobRepository;
        this.eventBus              = eventBus;
        this.onboardingJobExecutor = onboardingJobExecutor;
        this.batchAnalyzer         = batchAnalyzer;
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
    // Coalesces concurrent identical recommend calls onto one real AI call — the
    // cache below is only populated *after* the call completes, so without this,
    // two requests for the same connection/schema arriving close together (a
    // frontend double-fire, a double-click, two open tabs) both miss the cache
    // and both hit the AI. One lock object per distinct (connectionKey,
    // schemaName) pair; the set of these is small and long-lived in practice.
    private final java.util.concurrent.ConcurrentHashMap<String, Object> recommendLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public Map<String, Object> recommendTables(String connectionKey, String schemaName) {
        String cacheKey = "onboarding_recommend_" + connectionKey + "_" + schemaName;
        Object lock = recommendLocks.computeIfAbsent(cacheKey, k -> new Object());
        synchronized (lock) {
            return recommendTablesLocked(connectionKey, schemaName, cacheKey);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recommendTablesLocked(String connectionKey, String schemaName, String cacheKey) {
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
            // Cost baseline instrumentation (measurement-only): tag this call so its LLM_METRIC
            // line and nexus_usage_event row attribute to onboarding instead of the default "chat"
            // feature bucket. No effect on the call itself.
            com.sei.nexus.ai.LlmCallTag.set("ONBOARDING_RECOMMEND");
            com.sei.nexus.usage.UsageContext.set("onboarding", null);
            com.sei.nexus.ai.OperationCorrelationId.set("ONBOARDING_RECOMMEND:" + cacheKey);
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
        } finally {
            // OperationCorrelationId (unlike LlmCallTag) is not auto-cleared by AzureOpenAiClient —
            // it's meant to span multiple calls within one operation, so this call site owns
            // clearing it once the operation it labeled is actually finished.
            com.sei.nexus.ai.OperationCorrelationId.clear();
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
     * Synchronous, sequential analysis of a table set — kept for anything that
     * still wants the plain blocking shape (e.g. tests). {@link #startAnalysisJob}
     * is the path the wizard actually uses; it runs the same batched logic
     * ({@link #analyzeTableBatch}) with bounded concurrency instead of this loop.
     */
    public List<Map<String, Object>> analyzeTables(String connectionKey,
                                                     String schemaName,
                                                     String domainKey,
                                                     List<String> tableNames) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (List<String> batch : partition(tableNames, Math.max(1, batchSize))) {
            Map<String, Map<String, Object>> byTable =
                    analyzeTableBatch(connectionKey, schemaName, domainKey, batch);
            for (String tableName : batch) results.add(byTable.get(tableName));
        }
        return results;
    }

    /**
     * Reads a GROUP of tables' live schemas and asks the AI to analyse all of
     * them in a single call — the same "many tables, one call" shape
     * {@link #recommendTables} already uses, applied here to cut a 15-17 table
     * onboarding run from 15-17 separate AI calls down to a handful, which is
     * the dominant lever against provider rate limits (a Semaphore only caps
     * concurrency, not call volume). On a whole-call failure (including
     * exhausted 429 retries), every table in the batch degrades to the same
     * stub shape the old single-table path used — a batch must never abort
     * the job, and a batch failure must never look different from a table
     * failure to the frontend. On a partial/malformed response, only the
     * missing table(s) get stubbed.
     */
    // Onboarding's one caller-specific addition to the shared contract — wizard-bootstrap-only,
    // not a canonical Business Object field (see BusinessObjectAnalysisContract's own javadoc).
    private static final String SUGGESTED_QUESTIONS_FIELD_SCHEMA = """
              "suggestedQuestions": [
                "Plain-English question a manager might ask about this data",
                "Another operational question",
                "A third question focused on anomalies or performance"
              ]""";
    private static final String SUGGESTED_QUESTIONS_RULE =
            "- suggestedQuestions must be 3 natural-language questions, industry-agnostic.";

    /**
     * Reads a GROUP of tables' live schemas and asks the AI to analyse all of
     * them in a single call — via the shared {@link com.sei.nexus.prompt.BusinessObjectBatchAnalyzer},
     * also used by {@code EnterpriseMapService.analyzeForOnboarding()} (Discover from DB) — the
     * same "many tables, one call" shape {@link #recommendTables} already uses, applied here to
     * cut a 15-17 table onboarding run from 15-17 separate AI calls down to a handful, which is
     * the dominant lever against provider rate limits (a Semaphore only caps
     * concurrency, not call volume). On a whole-call failure (including
     * exhausted 429 retries), every table in the batch degrades to the same
     * stub shape the old single-table path used — a batch must never abort
     * the job, and a batch failure must never look different from a table
     * failure to the frontend. On a partial/malformed response, only the
     * missing table(s) get stubbed.
     */
    private Map<String, Map<String, Object>> analyzeTableBatch(String connectionKey, String schemaName,
                                                                 String domainKey, List<String> tableNames) {
        // Cost baseline instrumentation (measurement-only): tag this call so its LLM_METRIC line
        // and nexus_usage_event row attribute to onboarding instead of the default "chat" feature
        // bucket. No effect on the call itself — BusinessObjectBatchAnalyzer.analyzeBatch() is
        // shared with Discover/Industry Pack, which set their own tag/feature at their call sites.
        com.sei.nexus.ai.LlmCallTag.set("ONBOARDING_BATCH_ANALYSIS");
        com.sei.nexus.usage.UsageContext.set("onboarding", null);
        Map<String, Map<String, Object>> analyzed = batchAnalyzer.analyzeBatch(
                connectionKey, schemaName, domainKey, tableNames,
                SUGGESTED_QUESTIONS_FIELD_SCHEMA, SUGGESTED_QUESTIONS_RULE);

        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        for (String tableName : tableNames) {
            Map<String, Object> analysis = analyzed.get(tableName);
            // Onboarding's own wrapper fields — its historical snake_case shape, plus
            // entity_key and the suggestedQuestions default. Not part of the shared contract.
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("table_name",     tableName);
            entry.put("schema_name",    schemaName);
            entry.put("connection_key", connectionKey);
            entry.put("domain_key",     domainKey);
            entry.put("entity_key",     slugify(
                    (String) analysis.getOrDefault("entityName", tableName)));
            entry.putAll(analysis);
            if (!(entry.get("suggestedQuestions") instanceof List)) {
                entry.put("suggestedQuestions", List.of()); // Onboarding-only field, not canonical
            }
            results.put(tableName, entry);
        }
        return results;
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            batches.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return batches;
    }

    // ── Analyse — async job (V040) ──────────────────────────────────────────────

    // Serializes the check-then-insert in startAnalysisJob (below) so two
    // requests arriving milliseconds apart can't both see "no recent job" and
    // both start a real job — see that method's javadoc for the incident this
    // closes. One lock object for the whole JVM: job starts are rare (once per
    // onboarding attempt) and the guarded section is a single SELECT + INSERT,
    // so cross-tenant contention is not a practical concern.
    private final Object jobStartLock = new Object();

    /**
     * Starts an async analysis job and returns its id immediately — the actual
     * per-table AI calls run in the background with bounded concurrency (see
     * {@link #runAnalysisJob}). A double-submit of the identical request within
     * 10 minutes reattaches to the existing job instead of starting a new one.
     *
     * <p>The reattach check and the job insert are deliberately serialized
     * (below): without it, two requests for the identical params arriving
     * close together — a frontend double-fire, a double-click, two open tabs —
     * can both pass "no recent job found" before either has inserted its row,
     * each then starting its own real job. Confirmed live: two full analysis
     * jobs ran concurrently against the same table set, doubling AI-call
     * volume and triggering cascading OpenAI rate-limit failures across both.
     */
    public String startAnalysisJob(String connectionKey, String schemaName,
                                    String domainKey, List<String> tableNames) {
        // Multi-Table Analysis Hardening: the backend is the authority on the selection limit —
        // frontend UI enforcement (StepSelectTables' Browse All) is a UX convenience only and
        // must never be the only guard, since a request can always bypass client-side checks.
        if (tableNames.size() > com.sei.nexus.prompt.BusinessObjectAnalysisContract.MAX_SELECTED_TABLES) {
            throw new com.sei.nexus.common.NexusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Too many tables selected: " + tableNames.size() + " (maximum "
                            + com.sei.nexus.prompt.BusinessObjectAnalysisContract.MAX_SELECTED_TABLES + ")");
        }
        String tenantSchema = TenantContext.getSchema();
        String requestHash  = hashRequest(connectionKey, schemaName, domainKey, tableNames);

        String jobId;
        synchronized (jobStartLock) {
            Optional<OnboardingAnalysisJob> recent =
                    jobRepository.findRecentByHash(tenantSchema, requestHash, Duration.ofMinutes(10));
            if (recent.isPresent()) {
                return recent.get().id();
            }

            jobId = Keys.uniqueKey("onbjob");
            jobRepository.insertJob(new OnboardingAnalysisJob(
                    jobId, tenantSchema, connectionKey, schemaName, domainKey, tableNames,
                    "RUNNING", "{}", 0, tableNames.size(), requestHash, null, null, null));
        }

        CompletableFuture.runAsync(() -> runAnalysisJob(
                jobId, tenantSchema, connectionKey, schemaName, domainKey, tableNames),
                onboardingJobExecutor);

        return jobId;
    }

    public Optional<OnboardingAnalysisJob> getJob(String jobId) {
        return jobRepository.findById(jobId);
    }

    /** The poll/reattach view: job status plus every table result written so far. */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getJobView(String jobId) {
        return jobRepository.findById(jobId).map(job -> {
            List<Object> tables;
            try {
                Map<String, Object> resultsByTable =
                        objectMapper.readValue(job.resultsJson(), new TypeReference<>() {});
                tables = new ArrayList<>(resultsByTable.values());
            } catch (Exception e) {
                tables = List.of();
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("jobId",       job.id());
            view.put("status",      job.status());
            view.put("tablesDone",  job.tablesDone());
            view.put("tablesTotal", job.tablesTotal());
            view.put("tables",      tables);
            return view;
        });
    }

    /**
     * Runs every table in {@code tableNames} through {@link #analyzeTableBatch},
     * grouped into batches of {@code batchSize} tables per AI call, bounded to
     * {@code batchConcurrency} concurrent batches, publishing progress events
     * via {@link ReasoningEventBus} (using {@code jobId} as the run key — the
     * bus is generic, not chat-specific) and writing each table's result into
     * the job row as soon as its batch finishes. The DB schema, SSE event
     * vocabulary, and per-table result shape are all identical to the old
     * one-call-per-table version — only the AI-call granularity underneath
     * changed, so nothing downstream (job polling, the frontend) needed to change.
     *
     * <p>{@link TenantContext} is a plain {@code ThreadLocal} and does not
     * propagate across thread boundaries — every worker thread below sets and
     * clears it independently, not just the dispatching thread, or every DB
     * call a batch's analysis makes (schema describe, candidate lookup, the
     * job-result writes) would silently run against the wrong tenant schema.
     */
    private void runAnalysisJob(String jobId, String tenantSchema, String connectionKey,
                                 String schemaName, String domainKey, List<String> tableNames) {
        long analyzeStart = System.currentTimeMillis();
        TenantContext.set(tenantSchema);
        try {
            eventBus.publish(jobId, "job_started", Map.of("tablesTotal", tableNames.size()));

            Semaphore laneLimit = new Semaphore(Math.max(1, batchConcurrency));
            List<CompletableFuture<Void>> futures = partition(tableNames, Math.max(1, batchSize)).stream()
                    .map(batch -> CompletableFuture.runAsync(() -> {
                        try {
                            laneLimit.acquire();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        TenantContext.set(tenantSchema);
                        // Cost baseline instrumentation (measurement-only): re-set on this
                        // worker-pool thread (a fresh thread per CompletableFuture.runAsync task,
                        // same reason TenantContext is re-set just above) so every LLM_METRIC line
                        // this batch produces can be grouped back to this one onboarding job.
                        com.sei.nexus.ai.OperationCorrelationId.set("ONBOARDING:" + jobId);
                        try {
                            for (String tableName : batch) {
                                eventBus.publish(jobId, "table_started", Map.of("table", tableName));
                            }
                            Map<String, Map<String, Object>> results =
                                    analyzeTableBatch(connectionKey, schemaName, domainKey, batch);
                            for (String tableName : batch) {
                                Map<String, Object> result = results.get(tableName);
                                jobRepository.updateTableResult(jobId, tableName, result);
                                eventBus.publish(jobId,
                                        result.containsKey("error") ? "table_failed" : "table_completed",
                                        Map.of("table", tableName));
                            }
                        } finally {
                            TenantContext.clear();
                            com.sei.nexus.ai.OperationCorrelationId.clear();
                            laneLimit.release();
                        }
                    }, onboardingJobExecutor))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            jobRepository.markComplete(jobId);
            eventBus.publish(jobId, "job_complete", Map.of());
            eventBus.complete(jobId);
        } catch (Exception e) {
            log.error("Onboarding analysis job '{}' failed: {}", jobId, e.getMessage());
            jobRepository.markFailed(jobId);
            eventBus.publish(jobId, "job_failed", Map.of("error", String.valueOf(e.getMessage())));
            eventBus.complete(jobId);
        } finally {
            TenantContext.clear();
            log.info("onboarding.performance stage=analyze elapsedMs={}",
                    System.currentTimeMillis() - analyzeStart);
        }
    }

    private static String hashRequest(String connectionKey, String schemaName,
                                       String domainKey, List<String> tableNames) {
        List<String> sorted = tableNames.stream().sorted().collect(Collectors.toList());
        String raw = connectionKey + "|" + schemaName + "|" + domainKey + "|" + String.join(",", sorted);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    /**
     * Wizard apply = the canonical Metadata Registration Pipeline
     * ({@link MetadataRegistrationService#register}) followed by the wizard-only
     * bootstrap tail (suggested questions, default agent). The registration core
     * is shared with every other onboarding entry point (PRO-21) — only the
     * bootstrap steps below are first-run-specific.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> applySelections(Map<String, Object> request, String userEmail) {
        String connectionKey = (String) request.get("connectionKey");
        String domainKey     = (String) request.get("domainKey");
        List<Map<String, Object>> entities =
                (List<Map<String, Object>>) request.getOrDefault("entities", List.of());

        // 1. Canonical metadata registration — identical for every entry point:
        //    data objects (+ columns + value domains + version snapshots), linked
        //    entities, linked vocabulary, then batch relationship discovery.
        MetadataRegistrationService.RegistrationResult reg =
                metadataRegistration.register(request, userEmail);

        // ── Bootstrap tail — wizard-only from here on ─────────────────────────

        // 2. Persist the best 3 suggested questions from the approved drafts
        List<String> allQuestions = new ArrayList<>();
        for (Map<String, Object> entity : entities) {
            if (!Boolean.TRUE.equals(entity.get("approved"))) continue;
            List<String> qs = (List<String>) entity.getOrDefault("suggestedQuestions", List.of());
            allQuestions.addAll(qs);
        }
        List<String> topQuestions = allQuestions.stream().distinct().limit(3)
                .collect(Collectors.toList());
        try {
            settings.set(KEY_QUESTIONS, objectMapper.writeValueAsString(topQuestions));
        } catch (Exception e) {
            log.warn("Failed to store suggested questions: {}", e.getMessage());
        }

        // 3. Auto-create a default Data Analyst agent if no active agents exist yet.
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
                "entities_created",      reg.entitiesCreated(),
                "vocab_terms_created",   reg.vocabCreated(),
                "data_objects_created",  reg.objectsCreated(),
                "suggested_questions",   topQuestions,
                "failures",              reg.failures()
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

    // Entity/term identity derivation lives with the canonical registration
    // pipeline so every entry point produces identical keys.
    private String slugify(String input) {
        return MetadataRegistrationService.slugify(input);
    }

    private String toTitleCase(String input) {
        return MetadataRegistrationService.toTitleCase(input);
    }
}
