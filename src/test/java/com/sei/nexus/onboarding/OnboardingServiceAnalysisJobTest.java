package com.sei.nexus.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.reasoning.ReasoningEventBus;
import com.sei.nexus.semantic.EntityCandidateService;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Foundation hardening — async onboarding analysis job (V040), now with
 * batched AI calls (one call per GROUP of tables, not per table — see
 * {@code OnboardingService.analyzeTableBatch}). Hand-rolled fakes, no DB, no
 * Mockito, matching this package's existing convention.
 *
 * <p>Covers: TenantContext propagates correctly onto every concurrent batch
 * worker thread; batch concurrency never exceeds the configured bound; a
 * whole-batch AI failure degrades every table in that batch gracefully
 * (never fails the job); a partial/malformed response degrades only the
 * table missing from it; batching actually reduces AI call count; and the
 * double-submit guard (unaffected by batching — it runs before any AI call).
 */
class OnboardingServiceAnalysisJobTest {

    private static final String TENANT_SCHEMA = "tenant_test_corp";
    private static final Pattern TABLE_LINE = Pattern.compile("Table: (\\S+)");

    // ── fakes ────────────────────────────────────────────────────────────────

    static class FakeDynamicSqlService extends DynamicSqlService {
        /** Business Object Semantic Grounding Improvement: when set, returned verbatim instead of
         *  the default columns-only description — lets a test supply a table/column comment. */
        DynamicSqlService.TableDescription commentOverride;

        FakeDynamicSqlService() { super(null); }
        @Override
        public List<Map<String, Object>> describeTable(String connectionKey, String schemaName, String tableName) {
            return List.of(Map.of("column_name", "id", "data_type", "integer"));
        }
        @Override
        public DynamicSqlService.TableDescription describeTableWithComments(String connectionKey, String schemaName, String tableName) {
            return commentOverride != null ? commentOverride
                    : super.describeTableWithComments(connectionKey, schemaName, tableName);
        }
    }

    static class FakeEntityCandidateService extends EntityCandidateService {
        FakeEntityCandidateService() { super(null); }
        @Override public List<Candidate> retrieve(String domainKey, String tableName) { return List.of(); }
    }

    /**
     * Parses which tables are in the incoming batch prompt (from its
     * "Table: X" lines) and returns a {@code {"tables":[...]}} response with
     * one entry per table — the batch-response shape {@code analyzeTableBatch}
     * expects. Records calling-thread tenant + concurrency for assertions.
     */
    static class FakeAiClient extends AzureOpenAiClient {
        volatile String lastUserMessage;
        final List<String> capturedTenantSchemas = new CopyOnWriteArrayList<>();
        final AtomicIntWrapper liveCalls = new AtomicIntWrapper();
        final AtomicIntWrapper peakCalls = new AtomicIntWrapper();
        final java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        /** Any batch containing one of these tables fails as a whole (simulates a real AI-call exception). */
        final Set<String> failingTables = ConcurrentHashMap.newKeySet();
        /** Tables silently omitted from an otherwise-successful batch response (partial/malformed response). */
        final Set<String> omitFromResponse = ConcurrentHashMap.newKeySet();
        /** Grouping Foundation Fix: per-table category the fake AI emits; absent ⇒ field omitted entirely
         *  (simulates a model that doesn't comply with the category rule, exercising the safe default). */
        final Map<String, String> categoryByTable = new ConcurrentHashMap<>();
        final long perCallDelayMs;

        FakeAiClient(long perCallDelayMs) {
            super(new ObjectMapper(), null);
            this.perCallDelayMs = perCallDelayMs;
        }

        @Override
        public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
            capturedTenantSchemas.add(TenantContext.getSchema());
            callCount.incrementAndGet();
            int live = liveCalls.incrementAndGet();
            peakCalls.updateMax(live);
            try {
                if (perCallDelayMs > 0) Thread.sleep(perCallDelayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                liveCalls.decrementAndGet();
            }

            String userMessage = messages.get(0).content();
            lastUserMessage = userMessage;
            List<String> tablesInBatch = new ArrayList<>();
            Matcher m = TABLE_LINE.matcher(userMessage);
            while (m.find()) tablesInBatch.add(m.group(1));

            for (String failing : failingTables) {
                if (tablesInBatch.contains(failing)) {
                    throw new RuntimeException("simulated AI failure for batch containing " + failing);
                }
            }

            String entries = tablesInBatch.stream()
                    .filter(t -> !omitFromResponse.contains(t))
                    .map(t -> {
                        String category = categoryByTable.get(t);
                        String categoryField = category != null ? "\"category\":\"" + category + "\"," : "";
                        return "{\"table_name\":\"" + t + "\"," + categoryField + "\"entityName\":\"Thing\",\"purpose\":\"p\","
                                + "\"operationalMeaning\":\"m\",\"investigationHints\":\"h\","
                                + "\"vocabularySuggestions\":[],\"suggestedQuestions\":[],\"readinessScore\":0.9}";
                    })
                    .collect(Collectors.joining(","));
            return "{\"tables\":[" + entries + "]}";
        }
    }

    /** Plain mutable int wrapper — avoids AtomicInteger's lack of a max-update helper pre-Java 9 idioms. */
    static class AtomicIntWrapper {
        private final java.util.concurrent.atomic.AtomicInteger value = new java.util.concurrent.atomic.AtomicInteger(0);
        int incrementAndGet() { return value.incrementAndGet(); }
        int decrementAndGet() { return value.decrementAndGet(); }
        void updateMax(int candidate) { value.updateAndGet(prev -> Math.max(prev, candidate)); }
        int get() { return value.get(); }
    }

    /** In-memory job store — mirrors OnboardingAnalysisJobRepository's contract exactly. */
    static class FakeJobRepository extends OnboardingAnalysisJobRepository {
        final Map<String, OnboardingAnalysisJob> jobs = new ConcurrentHashMap<>();
        final ObjectMapper mapper = new ObjectMapper();

        FakeJobRepository() { super(null, new ObjectMapper()); }

        @Override
        public void insertJob(OnboardingAnalysisJob job) {
            jobs.put(job.id(), job);
        }

        @Override
        public synchronized void updateTableResult(String jobId, String tableName, Map<String, Object> tableResult) {
            OnboardingAnalysisJob job = jobs.get(jobId);
            try {
                Map<String, Object> results = new LinkedHashMap<>(
                        mapper.readValue(job.resultsJson(), Map.class));
                results.put(tableName, tableResult);
                jobs.put(jobId, new OnboardingAnalysisJob(
                        job.id(), job.tenantSchema(), job.connectionKey(), job.schemaName(), job.domainKey(),
                        job.tableNames(), job.status(), mapper.writeValueAsString(results),
                        job.tablesDone() + 1, job.tablesTotal(), job.requestHash(),
                        job.createdAt(), job.updatedAt(), job.completedAt()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void markComplete(String jobId) {
            OnboardingAnalysisJob job = jobs.get(jobId);
            jobs.put(jobId, withStatus(job, "COMPLETE"));
        }

        @Override
        public void markFailed(String jobId) {
            OnboardingAnalysisJob job = jobs.get(jobId);
            jobs.put(jobId, withStatus(job, "FAILED"));
        }

        @Override
        public Optional<OnboardingAnalysisJob> findById(String jobId) {
            return Optional.ofNullable(jobs.get(jobId));
        }

        @Override
        public Optional<OnboardingAnalysisJob> findRecentByHash(String tenantSchema, String requestHash, Duration window) {
            return jobs.values().stream()
                    .filter(j -> j.tenantSchema().equals(tenantSchema)
                            && j.requestHash().equals(requestHash)
                            && !"FAILED".equals(j.status()))
                    .findFirst();
        }

        private OnboardingAnalysisJob withStatus(OnboardingAnalysisJob job, String status) {
            return new OnboardingAnalysisJob(job.id(), job.tenantSchema(), job.connectionKey(),
                    job.schemaName(), job.domainKey(), job.tableNames(), status, job.resultsJson(),
                    job.tablesDone(), job.tablesTotal(), job.requestHash(),
                    job.createdAt(), job.updatedAt(), job.completedAt());
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private FakeAiClient aiClient;
    private FakeJobRepository jobRepository;
    private ExecutorService realExecutor;
    private OnboardingService service;
    private FakeDynamicSqlService dynamicSqlService;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_SCHEMA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (realExecutor != null) realExecutor.shutdownNow();
    }

    private void build(long perCallDelayMs, int batchSize, int batchConcurrency) throws Exception {
        aiClient = new FakeAiClient(perCallDelayMs);
        jobRepository = new FakeJobRepository();
        realExecutor = Executors.newFixedThreadPool(8);
        dynamicSqlService = new FakeDynamicSqlService();

        service = new OnboardingService(null, null, dynamicSqlService, null, null,
                aiClient, new ObjectMapper(), null, null, null, null,
                new FakeEntityCandidateService(), null, jobRepository,
                new ReasoningEventBus(new ObjectMapper()), realExecutor,
                new com.sei.nexus.prompt.BusinessObjectBatchAnalyzer(aiClient,
                        dynamicSqlService, new FakeEntityCandidateService(), new ObjectMapper()));

        setField("batchSize", batchSize);
        setField("batchConcurrency", batchConcurrency);
    }

    private void setField(String name, int value) throws Exception {
        Field field = OnboardingService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private static List<String> tables(int n) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) names.add("table_" + i);
        return names;
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void everyWorkerThreadSetsTenantContextCorrectly() throws Exception {
        build(50, 3, 3);
        String jobId = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(6));
        waitForCompletion(jobId);

        assertFalse(aiClient.capturedTenantSchemas.isEmpty());
        for (String schema : aiClient.capturedTenantSchemas) {
            assertEquals(TENANT_SCHEMA, schema,
                    "every AI call — regardless of which worker thread made it — must see the job's tenant schema");
        }
    }

    @Test
    void peakConcurrencyNeverExceedsBatchConcurrencyLimit() throws Exception {
        // 9 tables, batch size 3 => 3 batches; batch concurrency capped at 2.
        build(100, 3, 2);
        String jobId = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(9));
        waitForCompletion(jobId);

        assertTrue(aiClient.peakCalls.get() <= 2,
                "peak concurrent batch calls (" + aiClient.peakCalls.get() + ") must not exceed the configured limit (2)");
        assertEquals(2, aiClient.peakCalls.get(),
                "with 3 batches and a concurrency limit of 2, peak concurrency should reach exactly the limit");
    }

    @Test
    void batchingActuallyReducesAiCallCount() throws Exception {
        // 9 tables at batch size 3 => 3 AI calls, not 9 — the whole point of this change.
        build(5, 3, 3);
        String jobId = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(9));
        waitForCompletion(jobId);

        assertEquals(3, aiClient.callCount.get(),
                "9 tables at batch size 3 must produce exactly 3 AI calls, not one per table");
    }

    // ── Multi-Table Analysis Hardening: backend-enforced selection limit ───────
    // The limit must protect the backend, not merely the UI — this test calls
    // startAnalysisJob directly (as if the frontend cap were bypassed entirely)
    // and confirms the backend rejects it on its own, before any AI call is made.

    @Test
    void requestingMoreThanTheMaximumSelectedTablesIsRejectedByTheBackend() throws Exception {
        build(5, 3, 3);
        List<String> tooMany = tables(
                com.sei.nexus.prompt.BusinessObjectAnalysisContract.MAX_SELECTED_TABLES + 1);

        assertThrows(com.sei.nexus.common.NexusException.class,
                () -> service.startAnalysisJob("conn-1", "public", "PLATFORM", tooMany),
                "a selection exceeding the maximum must be rejected outright, exactly as if the "
                        + "frontend's own cap had been bypassed — the backend is the one enforcing it");
        assertEquals(0, aiClient.callCount.get(),
                "an over-limit request must be rejected before any AI call is attempted");
    }

    @Test
    void exactlyTheMaximumSelectedTablesIsAccepted() throws Exception {
        build(1, 10, 4);
        List<String> exactlyMax = tables(com.sei.nexus.prompt.BusinessObjectAnalysisContract.MAX_SELECTED_TABLES);

        String jobId = service.startAnalysisJob("conn-1", "public", "PLATFORM", exactlyMax);
        waitForCompletion(jobId);

        assertTrue(aiClient.callCount.get() > 0, "a selection exactly at the limit must proceed normally");
    }

    // ── Business Object Semantic Grounding Improvement ──────────────────────────

    @Test
    void onboardingReceivesTheSameSourceCommentEnrichedContextAsDiscover() throws Exception {
        build(5, 3, 3);
        dynamicSqlService.commentOverride = new com.sei.nexus.sql.DynamicSqlService.TableDescription(
                List.of(Map.of("column_name", "id", "data_type", "integer")),
                "Records inventory quantity adjustments resulting from cycle counts and reconciliation.");

        String jobId = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(1));
        waitForCompletion(jobId);

        assertTrue(aiClient.lastUserMessage.contains(
                        "Source DB description: Records inventory quantity adjustments resulting from cycle counts and reconciliation."),
                "Onboarding must receive the same source-comment-enriched context as Discover — "
                        + "both delegate to the same BusinessObjectBatchAnalyzer");
    }

    // ── Grouping Foundation Fix ────────────────────────────────────────────────
    // The same analyzeTableBatch() call every selected table goes through — whether
    // AI-recommended or added via Browse All, there is no separate path to test here.

    @Test
    void analyzedTableCarriesTheAiGeneratedCategory() throws Exception {
        build(5, 3, 3);
        aiClient.categoryByTable.put("table_0", "Operations");
        aiClient.categoryByTable.put("table_1", "Procurement");

        String jobId = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(2));
        OnboardingAnalysisJob job = waitForCompletion(jobId);

        @SuppressWarnings("unchecked")
        Map<String, Object> results = new ObjectMapper().readValue(job.resultsJson(), Map.class);
        assertEquals("Operations",  ((Map<?, ?>) results.get("table_0")).get("category"),
                "different tables in the same batch can carry different categories");
        assertEquals("Procurement", ((Map<?, ?>) results.get("table_1")).get("category"));
    }

    @Test
    void missingCategoryFromTheModelDefaultsToOtherRatherThanBeingUngrouped() throws Exception {
        // aiClient never sets categoryByTable for this table — simulates a model
        // response that omits the (instructed-as-required) category field.
        build(5, 3, 3);

        String jobId = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(1));
        OnboardingAnalysisJob job = waitForCompletion(jobId);

        @SuppressWarnings("unchecked")
        Map<String, Object> results = new ObjectMapper().readValue(job.resultsJson(), Map.class);
        assertEquals("Other", ((Map<?, ?>) results.get("table_0")).get("category"),
                "a table must never end up with no category at all, even when the model omits it");
    }

    @Test
    void wholeBatchFailureDegradesEveryTableInThatBatchGracefully() throws Exception {
        // 4 tables, batch size 2 => batches [table_0,table_1] and [table_2,table_3].
        // table_2 fails => its WHOLE batch (table_2 AND table_3) must stub, not just table_2,
        // because the batch is one AI call — a per-table failure isn't possible mid-batch.
        build(20, 2, 2);
        aiClient.failingTables.add("table_2");

        String jobId = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(4));
        OnboardingAnalysisJob job = waitForCompletion(jobId);

        assertEquals("COMPLETE", job.status(), "one failing batch must not fail the whole job");
        assertEquals(4, job.tablesDone());

        @SuppressWarnings("unchecked")
        Map<String, Object> results = new ObjectMapper().readValue(job.resultsJson(), Map.class);
        assertStubWithError(results, "table_2");
        assertStubWithError(results, "table_3");
        assertNotNull(results.get("table_0"), "the other, unrelated batch must still complete normally");
        assertNull(((Map<?, ?>) results.get("table_0")).get("error"));
        // Grouping Foundation Fix: even a stubbed (whole-batch-failure) table must
        // still carry a category — never left ungrouped just because analysis failed.
        assertEquals("Other", ((Map<?, ?>) results.get("table_2")).get("category"));
    }

    @Test
    void partialResponseDegradesOnlyTheMissingTableWithinAnOtherwiseSuccessfulBatch() throws Exception {
        // Same batch succeeds overall, but the AI's response happens to omit one table's
        // entry — only that table stubs; its batch-mate still gets a real result.
        build(10, 2, 2);
        aiClient.omitFromResponse.add("table_1");

        String jobId = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(2));
        OnboardingAnalysisJob job = waitForCompletion(jobId);

        assertEquals("COMPLETE", job.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> results = new ObjectMapper().readValue(job.resultsJson(), Map.class);
        assertStubWithError(results, "table_1");
        assertNotNull(results.get("table_0"));
        assertNull(((Map<?, ?>) results.get("table_0")).get("error"),
                "table_0's own entry was present in the response — it must not be stubbed just because its batch-mate was missing");
    }

    @Test
    void doubleSubmitReattachesToTheSameJobInsteadOfCreatingASecondOne() throws Exception {
        build(500, 4, 2); // slow enough that the first job is still RUNNING when we submit again
        List<String> tableNames = tables(3);

        String jobId1 = service.startAnalysisJob("conn-1", "public", "PLATFORM", tableNames);
        String jobId2 = service.startAnalysisJob("conn-1", "public", "PLATFORM", tableNames);

        assertEquals(jobId1, jobId2, "an identical in-flight request must reattach, not start a second job");
        assertEquals(1, jobRepository.jobs.size(), "only one job row should exist");
    }

    @Test
    void concurrentIdenticalSubmitsStartExactlyOneJob() throws Exception {
        // The incident this closes: a frontend double-fire (React StrictMode,
        // a double-click, two open tabs) sends two identical requests close
        // enough together that both can observe "no recent job" before either
        // has inserted its row — starting two REAL jobs, each independently
        // running the full AI batch pipeline against the same tables and
        // doubling load on the shared rate limit. startAnalysisJob's
        // check-then-insert must be serialized so this can't happen.
        build(50, 4, 2);
        List<String> tableNames = tables(6);
        int racers = 8;

        ExecutorService pool = Executors.newFixedThreadPool(racers);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(racers);
        java.util.concurrent.CountDownLatch go    = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return service.startAnalysisJob("conn-1", "public", "PLATFORM", tableNames);
            }));
        }
        ready.await();
        go.countDown();

        Set<String> jobIds = new java.util.HashSet<>();
        for (var f : futures) jobIds.add(f.get(10, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, jobIds.size(), "every racer must reattach to the same single job");
        assertEquals(1, jobRepository.jobs.size(), "exactly one job row must exist, not one per racer");
    }

    @Test
    void differentTableSelectionsGetDifferentJobs() throws Exception {
        build(10, 4, 2);
        String jobId1 = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(2));
        String jobId2 = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(3));

        assertNotEquals(jobId1, jobId2, "a genuinely different table selection must get its own job");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void assertStubWithError(Map<String, Object> results, String tableName) {
        Map<String, Object> entry = (Map<String, Object>) results.get(tableName);
        assertNotNull(entry, "expected a stub entry for " + tableName);
        assertNotNull(entry.get("error"), tableName + "'s stub entry must carry the error");
    }

    private OnboardingAnalysisJob waitForCompletion(String jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            OnboardingAnalysisJob job = jobRepository.jobs.get(jobId);
            if (job != null && !"RUNNING".equals(job.status())) return job;
            Thread.sleep(20);
        }
        fail("job '" + jobId + "' did not complete within timeout");
        return null;
    }
}
