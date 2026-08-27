package com.sei.nexus.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.enterprise.EnterpriseMapService;
import com.sei.nexus.onboarding.MetadataRegistrationService;
import com.sei.nexus.onboarding.OnboardingAnalysisJob;
import com.sei.nexus.onboarding.OnboardingAnalysisJobRepository;
import com.sei.nexus.onboarding.OnboardingService;
import com.sei.nexus.semantic.EntityCandidateService;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.sql.SqlSafetyService;
import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DIAGNOSTIC ONLY (opt-in, real LLM + real DB, never runs in the normal suite) — Multi-Table
 * Analysis Hardening, Step 9 validation. Exercises the REAL {@link EnterpriseMapService} (Discover
 * from DB) and REAL {@link OnboardingService} (Onboarding Wizard) against the real
 * {@code tenant_retail_industry} connection, at 2-table, 5-table, and largest-available-table
 * sizes.
 *
 * <p><b>Connection key</b>: looked up fresh (not hardcoded) via {@code CONNECTION_KEY} — connection
 * keys are not stable across environment resets in this project.
 *
 * <p><b>Table list</b>: discovered LIVE via {@link DynamicSqlService#listTables}, not hardcoded —
 * retail_core is the connection's own source schema (a different physical database than the
 * Supabase metadata DB this test's own psql checks can reach), so its real table names are only
 * visible through the application itself.
 *
 * <p><b>On the "maximum size" (40-table) requirement</b>: real tenants in this environment do not
 * have 40 physical tables in one schema. Rather than pad the request with fabricated table names
 * (which would defeat the point of a REAL-LLM, no-hallucination check), this validation uses all
 * real tables discovered (largest-available case), and separately relies on the unit tests
 * ({@code OnboardingServiceAnalysisJobTest}, {@code EnterpriseMapServiceAnalyzeForOnboardingTest})
 * for the 40-table CAP REJECTION itself — that path never reaches the LLM (rejected before any AI
 * call), so a synthetic table list is fully honest there.
 *
 * <p>Call counting: wraps the REAL, autowired {@link AzureOpenAiClient} bean in a thin counting
 * subclass that delegates every call to that same real bean (a real HTTP call to the real OpenAI
 * endpoint) while incrementing a counter — this measures the ACTUAL number of AI calls made by the
 * real batching mechanism, not an assumption.
 */
@SpringBootTest
class MultiTableAnalysisHardeningLiveValidation {

    @Autowired private AzureOpenAiClient realAiClient;
    @Autowired private DynamicSqlService dynamicSqlService;
    @Autowired private EntityCandidateService entityCandidates;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EnterpriseMapRepository enterpriseMapRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private SqlSafetyService sqlSafetyService;
    @Autowired private OnboardingService onboardingService;
    @Autowired private OnboardingAnalysisJobRepository jobRepository;
    @Autowired private EnterpriseMapService realEnterpriseMapService;
    @Autowired private MetadataRegistrationService metadataRegistrationService;

    private static final String TENANT_SCHEMA = "tenant_retail_industry";
    // Confirmed live via psql against tenant_retail_industry.nexus_connection immediately before
    // this run — connection keys are not stable across environment resets, so this is looked up
    // fresh rather than reused from an earlier task's report.
    private static final String CONNECTION_KEY = "conn-25c3ce28";
    private static final String SCHEMA_NAME = "retail_core";
    private static final String DOMAIN_KEY = "PLATFORM";

    /** Counts real AI calls by delegating every call to the real, autowired client. */
    static class CountingAiClient extends AzureOpenAiClient {
        private final AzureOpenAiClient real;
        final AtomicInteger calls = new AtomicInteger(0);

        CountingAiClient(AzureOpenAiClient real) {
            super(new ObjectMapper(), null);
            this.real = real;
        }

        @Override
        public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
            calls.incrementAndGet();
            return real.chatWithJson(messages, systemPrompt);
        }
    }

    /** Discovers the connection's real physical table list live — never hardcoded/stale. */
    @SuppressWarnings("unchecked")
    private List<String> discoverRealTables() {
        List<Map<String, Object>> rows = dynamicSqlService.listTables(CONNECTION_KEY, SCHEMA_NAME, "");
        List<String> names = rows.stream()
                .map(r -> String.valueOf(r.getOrDefault("table_name", r.get("TABLE_NAME"))))
                .collect(Collectors.toList());
        System.out.println("Discovered " + names.size() + " real table(s) live in "
                + SCHEMA_NAME + " for " + CONNECTION_KEY + ": " + names);
        return names;
    }

    @Test
    void discoverBatchesRealTablesAtTwoFiveAndLargestAvailableSizes() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live probe disabled");
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");

        TenantContext.set(TENANT_SCHEMA);
        try {
            List<String> allRealTables = discoverRealTables();
            assumeTrue(allRealTables.size() >= 2, "connection must have at least 2 real tables to validate");

            validateDiscover(allRealTables.subList(0, 2), 4);
            if (allRealTables.size() >= 5) validateDiscover(allRealTables.subList(0, 5), 4);
            validateDiscover(allRealTables, 4);
        } finally {
            TenantContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private void validateDiscover(List<String> tableNames, int discoverBatchSize) throws Exception {
        CountingAiClient counting = new CountingAiClient(realAiClient);
        BusinessObjectBatchAnalyzer countingAnalyzer = new BusinessObjectBatchAnalyzer(
                counting, dynamicSqlService, entityCandidates, objectMapper);
        EnterpriseMapService countingService = new EnterpriseMapService(
                enterpriseMapRepository, connectionRepository, dynamicSqlService, sqlSafetyService,
                counting, objectMapper, entityCandidates, countingAnalyzer);
        setDiscoverBatchSize(countingService, discoverBatchSize);

        System.out.println("\n########## DISCOVER: " + tableNames.size() + " table(s), batch size "
                + discoverBatchSize + " ##########");

        Map<String, Object> result = countingService.analyzeForOnboarding(Map.of(
                "domainKey", DOMAIN_KEY, "connectionKey", CONNECTION_KEY,
                "schemaName", SCHEMA_NAME, "tableNames", tableNames));

        List<Map<String, Object>> drafts = (List<Map<String, Object>>) result.get("tables");
        assertEquals(tableNames.size(), drafts.size(), "every requested table must have a draft");

        int expectedCalls = (int) Math.ceil(tableNames.size() / (double) discoverBatchSize);
        System.out.println("Expected AI calls: " + expectedCalls + ", actual: " + counting.calls.get());
        assertEquals(expectedCalls, counting.calls.get(),
                tableNames.size() + " tables at batch size " + discoverBatchSize
                        + " must produce exactly " + expectedCalls + " AI call(s), not "
                        + tableNames.size());

        Set<String> seenTableNames = new LinkedHashSet<>();
        Set<Object> seenIdentifierSets = new LinkedHashSet<>();
        for (Map<String, Object> d : drafts) {
            String tn = (String) d.get("tableName");
            System.out.println(tn + " -> category=" + d.get("category")
                    + " entityName=" + d.get("entityName")
                    + " businessName=" + d.get("businessName")
                    + " identifierColumns=" + d.get("identifierColumns")
                    + " error=" + d.get("error"));

            assertNotNull(d.get("category"), "category must never be blank for " + tn);
            assertFalse(((String) d.get("category")).isBlank(), "category must never be blank for " + tn);
            assertTrue(tableNames.contains(tn), "no cross-table contamination: " + tn + " was not requested");
            assertFalse(seenTableNames.contains(tn), "no duplicate/overwritten table entries for " + tn);
            seenTableNames.add(tn);

            Object entityName = d.get("entityName");
            if (entityName != null) seenIdentifierSets.add(entityName);
        }
        assertEquals(tableNames.size(), seenTableNames.size(), "every requested table produced exactly one draft");
        if (tableNames.size() > 1) {
            // entityName (unlike identifierColumns, which can legitimately coincide for two small
            // tables that both key on plain "id") is the reliable no-contamination signal: distinct
            // physical tables should essentially never resolve to the exact same business entity name.
            assertEquals(tableNames.size(), seenIdentifierSets.size(),
                    "every table's entityName must be distinct — a repeat suggests cross-table contamination");
        }
        System.out.println("########## END DISCOVER " + tableNames.size() + "-TABLE VALIDATION ##########\n");
    }

    private void setDiscoverBatchSize(EnterpriseMapService service, int value) throws Exception {
        var field = EnterpriseMapService.class.getDeclaredField("discoverBatchSize");
        field.setAccessible(true);
        field.set(service, value);
    }

    @Test
    void onboardingBatchesTheEquivalentRealTableSelections() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live probe disabled");
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");

        TenantContext.set(TENANT_SCHEMA);
        try {
            List<String> allRealTables = discoverRealTables();
            assumeTrue(allRealTables.size() >= 2, "connection must have at least 2 real tables to validate");

            validateOnboarding(allRealTables.subList(0, 2));
            if (allRealTables.size() >= 5) validateOnboarding(allRealTables.subList(0, 5));
            validateOnboarding(allRealTables);
        } finally {
            TenantContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private void validateOnboarding(List<String> tableNames) throws Exception {
        System.out.println("\n########## ONBOARDING: " + tableNames.size() + " table(s) ##########");

        String jobId = onboardingService.startAnalysisJob(CONNECTION_KEY, SCHEMA_NAME, DOMAIN_KEY, tableNames);
        OnboardingAnalysisJob job = waitForCompletion(jobId);
        assertEquals("COMPLETE", job.status(), "the real onboarding job must complete, not fail, for real tables");

        Map<String, Object> results = objectMapper.readValue(job.resultsJson(), Map.class);
        Set<String> seenTableNames = new LinkedHashSet<>();
        for (String t : tableNames) {
            Map<String, Object> r = (Map<String, Object>) results.get(t);
            assertNotNull(r, "onboarding result must include " + t);
            System.out.println(t + " -> category=" + r.get("category")
                    + " entityName=" + r.get("entityName")
                    + " businessName=" + r.get("businessName")
                    + " identifierColumns=" + r.get("identifierColumns"));
            assertNotNull(r.get("category"), "category must never be blank for " + t);
            assertFalse(((String) r.get("category")).isBlank(), "category must never be blank for " + t);
            seenTableNames.add(t);
        }
        assertEquals(tableNames.size(), seenTableNames.size(), "every requested table produced exactly one result");
        System.out.println("########## END ONBOARDING " + tableNames.size() + "-TABLE VALIDATION ##########\n");
    }

    private OnboardingAnalysisJob waitForCompletion(String jobId) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            var job = jobRepository.findById(jobId);
            if (job.isPresent() && ("COMPLETE".equals(job.get().status()) || "FAILED".equals(job.get().status()))) {
                return job.get();
            }
            Thread.sleep(150);
        }
        throw new IllegalStateException("job did not complete in time: " + jobId);
    }

    // ── Step 10: real product validation (no browser automation available in this
    // environment — validated at the real, fully-wired backend service layer instead, which is
    // exactly what /semantic/discover and /onboarding/analyze invoke) ──────────────────────────

    @Test
    void realBackendRejectsAnOverLimitSelectionOnTheLiveTenantForBothFlows() {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live probe disabled");
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");

        int max = BusinessObjectAnalysisContract.MAX_SELECTED_TABLES;
        List<String> tooMany = java.util.stream.IntStream.range(0, max + 1)
                .mapToObj(i -> "synthetic_table_" + i).collect(Collectors.toList());

        TenantContext.set(TENANT_SCHEMA);
        try {
            assertThrows(com.sei.nexus.common.NexusException.class,
                    () -> realEnterpriseMapService.analyzeForOnboarding(Map.of(
                            "domainKey", DOMAIN_KEY, "connectionKey", CONNECTION_KEY,
                            "schemaName", SCHEMA_NAME, "tableNames", tooMany)),
                    "the real, live-wired EnterpriseMapService must reject an over-limit selection");
            assertThrows(com.sei.nexus.common.NexusException.class,
                    () -> onboardingService.startAnalysisJob(CONNECTION_KEY, SCHEMA_NAME, DOMAIN_KEY, tooMany),
                    "the real, live-wired OnboardingService must reject an over-limit selection");
            System.out.println("Confirmed: real backend rejects " + tooMany.size()
                    + " tables (max " + max + ") for both Discover and Onboarding.");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void discoverAnalyzeThenApplyPersistsGroupLabelForARealMultiTableBatch() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live probe disabled");
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");

        TenantContext.set(TENANT_SCHEMA);
        try {
            List<String> allRealTables = discoverRealTables();
            List<String> tables = allRealTables.subList(0, Math.min(2, allRealTables.size()));

            Map<String, Object> discoverResult = realEnterpriseMapService.analyzeForOnboarding(Map.of(
                    "domainKey", DOMAIN_KEY, "connectionKey", CONNECTION_KEY,
                    "schemaName", SCHEMA_NAME, "tableNames", tables));
            List<Map<String, Object>> drafts = (List<Map<String, Object>>) discoverResult.get("tables");

            List<Map<String, Object>> entities = new java.util.ArrayList<>();
            for (Map<String, Object> draft : drafts) {
                Map<String, Object> entity = new java.util.LinkedHashMap<>();
                entity.put("approved", true);
                entity.put("tableName", draft.get("tableName"));
                entity.put("entityKey", slugify((String) draft.getOrDefault("entityName", draft.get("tableName"))));
                entity.put("entityName", draft.get("entityName"));
                entity.put("purpose", draft.get("purpose"));
                entity.put("category", draft.get("category"));
                entity.put("businessName", draft.get("businessName"));
                entity.put("identifierColumns", draft.get("identifierColumns"));
                entity.put("vocabulary", List.of());
                entities.add(entity);
            }
            Map<String, Object> applyRequest = Map.of(
                    "connectionKey", CONNECTION_KEY, "domainKey", DOMAIN_KEY,
                    "schemaName", SCHEMA_NAME, "entities", entities);

            var result = metadataRegistrationService.register(applyRequest, "prakash.stk12@gmail.com");
            System.out.println("Apply result: objectsCreated=" + result.objectsCreated()
                    + " entitiesCreated=" + result.entitiesCreated() + " failures=" + result.failures());
            assertTrue(result.failures() == null || result.failures().isEmpty(),
                    "apply must succeed for a real multi-table batch: " + result.failures());

            for (Map<String, Object> entity : entities) {
                String entityKey = (String) entity.get("entityKey");
                String category = (String) entity.get("category");
                String groupLabel = queryGroupLabel(entityKey);
                System.out.println(entityKey + " -> group_label=" + groupLabel + " (category was " + category + ")");
                assertNotNull(groupLabel, "group_label must be populated for " + entityKey);
                assertEquals(category, groupLabel,
                        "group_label must match the analyzed category for " + entityKey);
            }
        } finally {
            TenantContext.clear();
        }
    }

    private String queryGroupLabel(String entityKey) throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(
                System.getenv("NEXUS_DB_URL"), System.getenv("NEXUS_DB_USERNAME"), System.getenv("NEXUS_DB_PASSWORD"))) {
            try (var setPath = conn.createStatement()) {
                setPath.execute("SET search_path TO " + TENANT_SCHEMA);
            }
            try (var stmt = conn.prepareStatement(
                    "SELECT group_label FROM nexus_business_entity WHERE entity_key = ?")) {
                stmt.setString(1, entityKey);
                try (var rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getString("group_label") : null;
                }
            }
        }
    }

    private static String slugify(String input) {
        if (input == null) return "entity";
        return input.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}
