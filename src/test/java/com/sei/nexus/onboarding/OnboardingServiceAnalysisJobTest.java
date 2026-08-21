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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Foundation hardening — async onboarding analysis job (V040). Hand-rolled
 * fakes, no DB, no Mockito, matching this package's existing convention
 * (see {@code MetadataRegistrationServiceTest}).
 *
 * <p>Covers exactly what the plan called out as the correctness-critical
 * pieces: TenantContext propagates correctly onto every concurrent worker
 * thread (not just the dispatching thread), per-job concurrency never exceeds
 * the configured bound, a failing table degrades gracefully into the job
 * instead of aborting it, and a double-submitted identical request reattaches
 * to the existing job instead of starting a second one.
 */
class OnboardingServiceAnalysisJobTest {

    private static final String TENANT_SCHEMA = "tenant_test_corp";

    // ── fakes ────────────────────────────────────────────────────────────────

    static class FakeDynamicSqlService extends DynamicSqlService {
        FakeDynamicSqlService() { super(null); }
        @Override
        public List<Map<String, Object>> describeTable(String connectionKey, String schemaName, String tableName) {
            return List.of(Map.of("column_name", "id", "data_type", "integer"));
        }
    }

    static class FakeEntityCandidateService extends EntityCandidateService {
        FakeEntityCandidateService() { super(null); }
        @Override public List<Candidate> retrieve(String domainKey, String tableName) { return List.of(); }
    }

    /**
     * Records, per call: the calling thread name and {@link TenantContext#getSchema()}
     * observed at call time — the direct assertion that every worker thread set its
     * own tenant context correctly, not just the job dispatcher thread. Tracks live/
     * peak concurrency to prove the per-job Semaphore bound is respected. One
     * designated table throws, to prove graceful degradation is preserved.
     */
    static class FakeAiClient extends AzureOpenAiClient {
        final List<String> capturedTenantSchemas = new CopyOnWriteArrayList<>();
        final AtomicInteger liveCalls = new AtomicInteger(0);
        final AtomicInteger peakCalls = new AtomicInteger(0);
        final Set<String> failingTables = ConcurrentHashMap.newKeySet();
        final long perCallDelayMs;

        FakeAiClient(long perCallDelayMs) {
            super(new ObjectMapper(), null);
            this.perCallDelayMs = perCallDelayMs;
        }

        @Override
        public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
            capturedTenantSchemas.add(TenantContext.getSchema());
            int live = liveCalls.incrementAndGet();
            peakCalls.updateAndGet(prev -> Math.max(prev, live));
            try {
                if (perCallDelayMs > 0) Thread.sleep(perCallDelayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                liveCalls.decrementAndGet();
            }

            String userMessage = messages.get(0).content();
            for (String failing : failingTables) {
                if (userMessage.contains(failing)) {
                    throw new RuntimeException("simulated AI failure for " + failing);
                }
            }
            return "{\"entityName\":\"Thing\",\"purpose\":\"p\",\"operationalMeaning\":\"m\","
                    + "\"investigationHints\":\"h\",\"vocabularySuggestions\":[],"
                    + "\"suggestedQuestions\":[],\"readinessScore\":0.9}";
        }
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

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_SCHEMA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (realExecutor != null) realExecutor.shutdownNow();
    }

    private void build(long perCallDelayMs, int tableConcurrency) throws Exception {
        aiClient = new FakeAiClient(perCallDelayMs);
        jobRepository = new FakeJobRepository();
        realExecutor = Executors.newFixedThreadPool(8);

        service = new OnboardingService(null, null, new FakeDynamicSqlService(), null, null,
                aiClient, new ObjectMapper(), null, null, null, null,
                new FakeEntityCandidateService(), null, jobRepository,
                new ReasoningEventBus(new ObjectMapper()), realExecutor);

        Field concurrencyField = OnboardingService.class.getDeclaredField("tableConcurrencyPerJob");
        concurrencyField.setAccessible(true);
        concurrencyField.set(service, tableConcurrency);
    }

    private static List<String> tables(int n) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) names.add("table_" + i);
        return names;
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void everyWorkerThreadSetsTenantContextCorrectly() throws Exception {
        build(50, 3);
        String jobId = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(6));
        waitForCompletion(jobId);

        assertFalse(aiClient.capturedTenantSchemas.isEmpty());
        for (String schema : aiClient.capturedTenantSchemas) {
            assertEquals(TENANT_SCHEMA, schema,
                    "every AI call — regardless of which worker thread made it — must see the job's tenant schema");
        }
    }

    @Test
    void peakConcurrencyNeverExceedsPerJobLimit() throws Exception {
        build(100, 3);
        String jobId = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(9));
        waitForCompletion(jobId);

        assertTrue(aiClient.peakCalls.get() <= 3,
                "peak concurrent table analyses (" + aiClient.peakCalls.get() + ") must not exceed the configured limit (3)");
        assertEquals(3, aiClient.peakCalls.get(),
                "with 9 tables and a limit of 3, peak concurrency should reach exactly the limit");
    }

    @Test
    void failingTableDegradesGracefullyInsteadOfFailingTheJob() throws Exception {
        build(20, 2);
        aiClient.failingTables.add("table_2");

        String jobId = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(4));
        OnboardingAnalysisJob job = waitForCompletion(jobId);

        assertEquals("COMPLETE", job.status(), "one failing table must not fail the whole job");
        assertEquals(4, job.tablesDone());

        @SuppressWarnings("unchecked")
        Map<String, Object> results = new ObjectMapper().readValue(job.resultsJson(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> failedEntry = (Map<String, Object>) results.get("table_2");
        assertNotNull(failedEntry.get("error"), "the failing table's stub entry must carry the error");
        assertNotNull(results.get("table_0"), "other tables must still have completed normally");
    }

    @Test
    void doubleSubmitReattachesToTheSameJobInsteadOfCreatingASecondOne() throws Exception {
        build(500, 2); // slow enough that the first job is still RUNNING when we submit again
        List<String> tableNames = tables(3);

        String jobId1 = service.startAnalysisJob("conn-1", "public", "PLATFORM", tableNames);
        String jobId2 = service.startAnalysisJob("conn-1", "public", "PLATFORM", tableNames);

        assertEquals(jobId1, jobId2, "an identical in-flight request must reattach, not start a second job");
        assertEquals(1, jobRepository.jobs.size(), "only one job row should exist");
    }

    @Test
    void differentTableSelectionsGetDifferentJobs() throws Exception {
        build(10, 2);
        String jobId1 = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(2));
        String jobId2 = service.startAnalysisJob("conn-1", "public", "PLATFORM", tables(3));

        assertNotEquals(jobId1, jobId2, "a genuinely different table selection must get its own job");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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
