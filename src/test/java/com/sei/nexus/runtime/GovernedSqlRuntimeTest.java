package com.sei.nexus.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.ExecutionBindings;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.SemanticView;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.connection.NexusConnection;
import com.sei.nexus.governance.ContractResult;
import com.sei.nexus.governance.GovernanceAuditService;
import com.sei.nexus.governance.GovernanceOutcome;
import com.sei.nexus.governance.MaskResult;
import com.sei.nexus.governance.RlsResult;
import com.sei.nexus.governance.SqlGovernancePipeline;
import com.sei.nexus.query.QueryExecutionRepository;
import com.sei.nexus.reasoning.LiteralValidator;
import com.sei.nexus.semanticmodel.ColumnValueDomain;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified Answer Engine, Phase 1 — the single deterministic execution path.
 *
 * <p>Verifies that {@link GovernedSqlRuntime} owns, in order, business-object validation,
 * literal validation, governance, execution, and audit; and that the two execution policies
 * (agent / conversational planner) each reproduce their pre-Phase-1 behaviour exactly.
 * Hand-rolled fakes; no database.
 */
class GovernedSqlRuntimeTest {

    private static final String CONN = "conn-1";
    private static final SqlTableReferenceExtractor EXTRACTOR = new SqlTableReferenceExtractor();

    // ── fakes ─────────────────────────────────────────────────────────────────

    static class FakePipeline extends SqlGovernancePipeline {
        GovernanceOutcome outcome;
        boolean invoked = false;
        String seenObjectKeys;
        boolean seenForceAsync;
        FakePipeline() { super(null, null, null, null, null); }
        @Override public GovernanceOutcome governSql(String runKey, int stepNo, String connectionKey,
                String objectKeys, String sql, String userEmail, boolean forceAsync) {
            invoked = true;
            seenObjectKeys = objectKeys;
            seenForceAsync = forceAsync;
            return outcome;
        }
    }

    static class FakeAudit extends GovernanceAuditService {
        record Call(boolean blocked, Integer rowCount, Integer ms, List<String> objectKeys) {}
        final List<Call> calls = new ArrayList<>();
        FakeAudit() { super(null, null, null); }
        @Override public void recordOutcome(GovernanceOutcome outcome, String userEmail, String runKey,
                String connectionKey, List<String> objectKeys, Integer rowCount,
                Integer executionMs, boolean blocked) {
            calls.add(new Call(blocked, rowCount, executionMs, objectKeys));
        }
    }

    static class FakeDynamicSql extends DynamicSqlService {
        record Call(String sql, int maxRows, boolean readOnly) {}
        final List<Call> calls = new ArrayList<>();
        RuntimeException failWith;
        FakeDynamicSql() { super(null); }
        @Override public List<Map<String, Object>> executeQuery(String connectionKey,
                String approvedSql, int maxRows, boolean readOnly) {
            calls.add(new Call(approvedSql, maxRows, readOnly));
            if (failWith != null) throw failWith;
            return List.of(Map.of("id", 1));
        }
    }

    static class FakeExecutionRepo extends QueryExecutionRepository {
        final List<String> statuses = new ArrayList<>();
        FakeExecutionRepo() { super(null); }
        @Override public void updateStatus(String executionKey, String status, Instant startedAt,
                                           Instant completedAt, String errorMessage) {
            statuses.add(status);
        }
        @Override public void updateResult(String executionKey, String resultJson,
                                           String status, Instant completedAt) {
            statuses.add("RESULT:" + status);
        }
    }

    /** Connections exist unless explicitly declared missing. */
    static class FakeConnectionRepo extends ConnectionRepository {
        boolean exists = true;
        FakeConnectionRepo() { super(null); }
        @Override public Optional<NexusConnection> findByKeyOrName(String value) {
            if (!exists) return Optional.empty();
            return Optional.of(new NexusConnection(CONN, "conn", "POSTGRES", "desc",
                    "jdbc:postgresql://h/db", null, "u", "s", "public", null,
                    true, "SUCCESS", null, null, "ACTIVE", null, null));
        }
    }

    private FakePipeline       pipeline;
    private FakeAudit          audit;
    private FakeDynamicSql     dynamicSql;
    private FakeExecutionRepo  execRepo;
    private FakeConnectionRepo connectionRepo;
    private GovernedSqlRuntime runtime;

    @BeforeEach
    void setUp() {
        pipeline       = new FakePipeline();
        audit          = new FakeAudit();
        dynamicSql     = new FakeDynamicSql();
        execRepo       = new FakeExecutionRepo();
        connectionRepo = new FakeConnectionRepo();
        runtime = new GovernedSqlRuntime(pipeline, dynamicSql, audit, EXTRACTOR,
                execRepo, connectionRepo, new ObjectMapper());
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static ExecutionContract contractApproving(String... tables) {
        Set<ExecutionBindings.ApprovedAsset> assets = new HashSet<>();
        Map<String, ExecutionBindings.ExecutionTarget> objectBindings = new LinkedHashMap<>();
        for (String t : tables) {
            assets.add(new ExecutionBindings.ApprovedAsset(CONN, EXTRACTOR.canonical(t)));
            objectBindings.put(t, new ExecutionBindings.ExecutionTarget("SQL", CONN, "public", t, null));
        }
        return new ExecutionContract("ctr-1", Instant.now(), "agent-1", List.of(CONN), "hash",
                new SemanticView(List.of()), new ExecutionBindings(objectBindings, Map.of(), assets));
    }

    private static GovernanceOutcome executeOutcome(String governedSql, int rowLimit) {
        return new GovernanceOutcome(GovernanceOutcome.Verdict.EXECUTE, null, governedSql,
                "SELECT approved", "BOUNDED_LIST", "EXECUTE_SYNC", rowLimit, "ek", "analyst",
                ContractResult.passed(List.of()), RlsResult.passThrough(governedSql),
                MaskResult.passThrough(governedSql));
    }

    private static GovernanceOutcome governBlocked(String reason) {
        return new GovernanceOutcome(GovernanceOutcome.Verdict.BLOCKED, reason, null,
                "SELECT x", "BLOCKED", "BLOCK", 100, "ek", "analyst", null, null, null);
    }

    private static GovernanceOutcome contractBlocked(String reason) {
        return new GovernanceOutcome(GovernanceOutcome.Verdict.BLOCKED, reason, null,
                "SELECT x", "BLOCKED", "BLOCK", 100, "ek", "analyst",
                ContractResult.passed(List.of()), null, null);
    }

    private static GovernanceOutcome asyncOutcome() {
        return new GovernanceOutcome(GovernanceOutcome.Verdict.ASYNC, null, null,
                "SELECT x", "FULL_SCAN", "ASYNC", 100, "ek", "analyst", null, null, null);
    }

    private GovernedSqlRuntime.Outcome runAgent(String sql, ExecutionContract contract) {
        return runtime.execute(GovernedSqlRuntime.Request.forAgent(
                "run-1", 1, CONN, sql, "u@x.com", contract));
    }

    private GovernedSqlRuntime.Outcome runPlanner(String sql, String objectKeys,
            Map<String, ColumnValueDomain> scope, Set<String> rejections) {
        return runtime.execute(GovernedSqlRuntime.Request.forPlanner(
                "run-1", 1, CONN, objectKeys, sql, "u@x.com", false,
                scope, List.of(), "the question", rejections, null, false));
    }

    /** Planner policy with the business-object gate engaged in the given migration mode. */
    private GovernedSqlRuntime.Outcome runPlannerGated(String sql, ExecutionContract contract,
                                                       boolean enforce) {
        return runtime.execute(GovernedSqlRuntime.Request.forPlanner(
                "run-1", 1, CONN, "obj-1", sql, "u@x.com", false,
                Map.of(), List.of(), "the question", new HashSet<>(), contract, enforce));
    }

    // ── Phase 3 Step 2: the business-object gate on the conversational policy ──

    /** Gate off (no contract): behaves exactly as before Phase 3. */
    @Test
    void plannerWithoutContractIsUngated() {
        pipeline.outcome = executeOutcome("SELECT id FROM invoices", 10);

        GovernedSqlRuntime.Outcome o = runPlanner("SELECT id FROM invoices", "obj-1",
                Map.of(), new HashSet<>());

        assertEquals(GovernedSqlRuntime.Status.EXECUTED, o.status());
        assertTrue(o.unapprovedTables().isEmpty(), "no contract ⇒ nothing to report");
    }

    /** Shadow mode: the verdict is recorded but the statement still executes. */
    @Test
    void shadowModeRecordsTheVerdictWithoutEnforcing() {
        pipeline.outcome = executeOutcome("SELECT id FROM invoices", 10);

        GovernedSqlRuntime.Outcome o = runPlannerGated(
                "SELECT id FROM invoices", contractApproving("orders"), false);

        assertEquals(GovernedSqlRuntime.Status.EXECUTED, o.status(),
                "shadow mode must not change behaviour");
        assertTrue(pipeline.invoked, "the statement still reaches governance");
        assertEquals(1, dynamicSql.calls.size(), "the statement still executes");
        assertTrue(o.unapprovedTables().contains("INVOICES"),
                "the would-be rejection is reported for measurement");
        assertEquals("orders", o.approvedTables());
    }

    /** Shadow mode on an approved table reports nothing. */
    @Test
    void shadowModeReportsNothingForAnApprovedTable() {
        pipeline.outcome = executeOutcome("SELECT id FROM orders", 10);

        GovernedSqlRuntime.Outcome o = runPlannerGated(
                "SELECT id FROM orders", contractApproving("orders"), false);

        assertEquals(GovernedSqlRuntime.Status.EXECUTED, o.status());
        assertTrue(o.unapprovedTables().isEmpty());
    }

    /** Enforce mode: identical verdict to the agent policy — rejected before governance. */
    @Test
    void enforceModeRejectsBeforeGovernance() {
        GovernedSqlRuntime.Outcome o = runPlannerGated(
                "SELECT id FROM invoices", contractApproving("orders"), true);

        assertEquals(GovernedSqlRuntime.Status.UNAPPROVED_OBJECTS, o.status());
        assertTrue(o.unapprovedTables().contains("INVOICES"));
        assertFalse(pipeline.invoked, "an unapproved table never reaches governance");
        assertTrue(dynamicSql.calls.isEmpty(), "an unapproved table never reaches the database");
        assertTrue(audit.calls.isEmpty());
    }

    // ── 1. business-object validation (agent policy) ──────────────────────────

    @Test
    void unapprovedTableIsRejectedBeforeGovernanceAndNeverExecutes() {
        GovernedSqlRuntime.Outcome o = runAgent("SELECT COUNT(*) FROM invoices", contractApproving("orders"));

        assertEquals(GovernedSqlRuntime.Status.UNAPPROVED_OBJECTS, o.status());
        assertTrue(o.unapprovedTables().contains("INVOICES"), o.unapprovedTables().toString());
        assertEquals("orders", o.approvedTables());
        assertFalse(pipeline.invoked, "governance is not reached for an unapproved table");
        assertTrue(dynamicSql.calls.isEmpty(), "an unapproved table never reaches the database");
        assertTrue(audit.calls.isEmpty(), "a gate rejection is not audited");
    }

    @Test
    void approvedTableReachesGovernanceAndExecutesReadOnly() {
        pipeline.outcome = executeOutcome("SELECT id FROM orders WHERE r='X'", 50);

        GovernedSqlRuntime.Outcome o = runAgent("SELECT id FROM orders", contractApproving("orders"));

        assertEquals(GovernedSqlRuntime.Status.EXECUTED, o.status());
        assertTrue(pipeline.invoked);
        assertEquals(1, dynamicSql.calls.size());
        assertEquals("SELECT id FROM orders WHERE r='X'", dynamicSql.calls.get(0).sql());
        assertEquals(50, dynamicSql.calls.get(0).maxRows());
        assertTrue(dynamicSql.calls.get(0).readOnly(), "agent SQL executes read-only (ADR-0003 A1)");
        assertTrue(o.rowsJson().contains("\"id\":1"));
    }

    /** Agent parity: no execution-record writes, and audit carries an empty object-key list. */
    @Test
    void agentPolicyDoesNotTrackExecutionRecordAndAuditsWithNoObjectKeys() {
        pipeline.outcome = executeOutcome("SELECT id FROM orders", 10);

        runAgent("SELECT id FROM orders", contractApproving("orders"));

        assertTrue(execRepo.statuses.isEmpty(), "agent path writes no execution record");
        assertEquals("", pipeline.seenObjectKeys, "agent passes empty object keys");
        assertEquals(1, audit.calls.size());
        assertEquals(List.of(), audit.calls.get(0).objectKeys());
        assertEquals(1, audit.calls.get(0).rowCount());
        assertFalse(audit.calls.get(0).blocked());
    }

    // ── 2. literal validation (planner policy) ────────────────────────────────

    /** Scope keyed by the bare column, as an unqualified SQL reference resolves it. */
    private static Map<String, ColumnValueDomain> scopeWith(boolean authoritative) {
        return Map.of("state_province", new ColumnValueDomain(
                "stores", "state_province", authoritative, List.of("Texas", "California")));
    }

    @Test
    void invalidLiteralIsRejectedOnceBeforeGovernance() {
        GovernedSqlRuntime.Outcome o = runPlanner(
                "SELECT id FROM stores WHERE state_province = 'TX'", "obj-1",
                scopeWith(true), new HashSet<>());

        assertEquals(GovernedSqlRuntime.Status.LITERAL_REJECTED, o.status());
        assertNotNull(o.message());
        assertFalse(pipeline.invoked, "a rejected literal never reaches governance");
        assertTrue(dynamicSql.calls.isEmpty());
        assertTrue(audit.calls.isEmpty());
    }

    @Test
    void repeatLiteralViolationHardBlocksOnAuthoritativeDomain() {
        Set<String> rejections = new HashSet<>();
        String sql = "SELECT id FROM stores WHERE state_province = 'TX'";

        assertEquals(GovernedSqlRuntime.Status.LITERAL_REJECTED,
                runPlanner(sql, "obj-1", scopeWith(true), rejections).status());
        // the same violation a second time — the retry bound is exhausted
        assertEquals(GovernedSqlRuntime.Status.LITERAL_BLOCKED,
                runPlanner(sql, "obj-1", scopeWith(true), rejections).status());

        assertFalse(pipeline.invoked);
        assertTrue(dynamicSql.calls.isEmpty());
    }

    @Test
    void validLiteralPassesThroughToGovernance() {
        pipeline.outcome = executeOutcome("SELECT id FROM stores", 10);

        GovernedSqlRuntime.Outcome o = runPlanner(
                "SELECT id FROM stores WHERE state_province = 'Texas'", "obj-1",
                scopeWith(true), new HashSet<>());

        assertEquals(GovernedSqlRuntime.Status.EXECUTED, o.status());
        assertTrue(pipeline.invoked);
    }

    @Test
    void noLiteralScopeSkipsValidationEntirely() {
        pipeline.outcome = executeOutcome("SELECT id FROM stores", 10);

        GovernedSqlRuntime.Outcome o = runPlanner(
                "SELECT id FROM stores WHERE state_province = 'TX'", "obj-1", Map.of(), new HashSet<>());

        assertEquals(GovernedSqlRuntime.Status.EXECUTED, o.status(),
                "with no domain scope the gate is inert — pre-DLR behaviour");
    }

    // ── 2b. stage ordering: literal validation precedes the connection check ──

    /**
     * Phase 1 parity: the conversational path validated literals BEFORE checking that the
     * connection exists. With both faulty, the literal verdict must win — a missing connection
     * must not pre-empt the bounded literal re-plan.
     */
    @Test
    void literalValidationRunsBeforeTheConnectionExistenceCheck() {
        connectionRepo.exists = false;

        GovernedSqlRuntime.Outcome o = runPlanner(
                "SELECT id FROM stores WHERE state_province = 'TX'", "obj-1",
                scopeWith(true), new HashSet<>());

        assertEquals(GovernedSqlRuntime.Status.LITERAL_REJECTED, o.status(),
                "literal validation must be evaluated before the connection existence check");
    }

    @Test
    void missingConnectionIsReportedWhenLiteralsAreClean() {
        connectionRepo.exists = false;

        GovernedSqlRuntime.Outcome o = runPlanner("SELECT id FROM orders", "obj-1",
                Map.of(), new HashSet<>());

        assertEquals(GovernedSqlRuntime.Status.CONNECTION_NOT_FOUND, o.status());
        assertEquals("Connection 'conn-1' not found", o.message());
        assertFalse(pipeline.invoked, "a missing connection never reaches governance");
        assertTrue(dynamicSql.calls.isEmpty());
        assertTrue(audit.calls.isEmpty());
    }

    /** The agent policy pre-checks its allow-list itself and must never consult the repository. */
    @Test
    void agentPolicyDoesNotPerformTheConnectionExistenceCheck() {
        connectionRepo.exists = false;
        pipeline.outcome = executeOutcome("SELECT id FROM orders", 10);

        GovernedSqlRuntime.Outcome o = runAgent("SELECT id FROM orders", contractApproving("orders"));

        assertEquals(GovernedSqlRuntime.Status.EXECUTED, o.status(),
                "agent policy is unaffected by connection existence");
    }

    // ── 3. governance verdicts + audit policy ─────────────────────────────────

    @Test
    void governLevelBlockIsNotAudited() {
        pipeline.outcome = governBlocked("Only SELECT statements are allowed");

        GovernedSqlRuntime.Outcome o = runPlanner("DELETE FROM orders", "obj-1", Map.of(), new HashSet<>());

        assertEquals(GovernedSqlRuntime.Status.GOVERNANCE_BLOCKED, o.status());
        assertEquals("Only SELECT statements are allowed", o.message());
        assertTrue(audit.calls.isEmpty(), "safety/allow-list blocks are not audited");
        assertTrue(dynamicSql.calls.isEmpty());
    }

    @Test
    void contractBlockIsAuditedAsBlocked() {
        pipeline.outcome = contractBlocked("Column not permitted by data contract");

        GovernedSqlRuntime.Outcome o = runPlanner("SELECT ssn FROM people", "obj-1", Map.of(), new HashSet<>());

        assertEquals(GovernedSqlRuntime.Status.CONTRACT_BLOCKED, o.status());
        assertEquals(1, audit.calls.size());
        assertTrue(audit.calls.get(0).blocked());
        assertNull(audit.calls.get(0).rowCount());
        assertTrue(dynamicSql.calls.isEmpty());
    }

    @Test
    void asyncQueuesTheExecutionRecordForThePlannerPolicy() {
        pipeline.outcome = asyncOutcome();

        GovernedSqlRuntime.Outcome o = runPlanner("SELECT * FROM huge", "obj-1", Map.of(), new HashSet<>());

        assertEquals(GovernedSqlRuntime.Status.ASYNC, o.status());
        assertEquals(List.of("QUEUED"), execRepo.statuses);
        assertTrue(audit.calls.isEmpty(), "async steps are not audited at plan time");
        assertTrue(dynamicSql.calls.isEmpty());
    }

    // ── 4. execution + audit ──────────────────────────────────────────────────

    @Test
    void plannerPolicyTracksExecutionRecordAndAuditsWithObjectKeys() {
        pipeline.outcome = executeOutcome("SELECT id FROM orders", 25);

        GovernedSqlRuntime.Outcome o = runPlanner("SELECT id FROM orders", "obj-1, obj-2",
                Map.of(), new HashSet<>());

        assertEquals(GovernedSqlRuntime.Status.EXECUTED, o.status());
        assertEquals(List.of("RUNNING", "RESULT:SUCCESS"), execRepo.statuses);
        assertFalse(dynamicSql.calls.get(0).readOnly(), "planner policy preserves non-read-only execution");
        assertEquals(1, audit.calls.size());
        assertEquals(List.of("obj-1", "obj-2"), audit.calls.get(0).objectKeys(),
                "object keys are parsed and forwarded to audit");
        assertEquals(1, audit.calls.get(0).rowCount());
        assertFalse(audit.calls.get(0).blocked());
        assertEquals(1, o.rows().size());
    }

    @Test
    void executionFailureIsAuditedWithNullRowCountAndReturnedNotThrown() {
        pipeline.outcome = executeOutcome("SELECT id FROM orders", 25);
        dynamicSql.failWith = new IllegalStateException("column does not exist");

        GovernedSqlRuntime.Outcome o = runPlanner("SELECT bad FROM orders", "obj-1",
                Map.of(), new HashSet<>());

        assertEquals(GovernedSqlRuntime.Status.FAILED, o.status());
        assertEquals("column does not exist", o.message());
        assertNotNull(o.failure());
        assertEquals(List.of("RUNNING", "FAILED"), execRepo.statuses);
        assertEquals(1, audit.calls.size());
        assertNull(audit.calls.get(0).rowCount(), "a failed execution audits with no row count");
        assertFalse(audit.calls.get(0).blocked());
    }

    @Test
    void forceAsyncFlagIsForwardedToGovernance() {
        pipeline.outcome = executeOutcome("SELECT id FROM orders", 10);

        runtime.execute(GovernedSqlRuntime.Request.forPlanner("run-1", 1, CONN, "obj-1",
                "SELECT id FROM orders", "u@x.com", true, Map.of(), List.of(), "q", new HashSet<>(),
                null, false));

        assertTrue(pipeline.seenForceAsync, "the planner's forceAsync choice reaches governance");
    }
}
