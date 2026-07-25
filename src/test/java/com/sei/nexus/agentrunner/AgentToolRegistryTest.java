package com.sei.nexus.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.ExecutionBindings;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.SemanticView;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.governance.ContractResult;
import com.sei.nexus.governance.GovernanceAuditService;
import com.sei.nexus.governance.GovernanceOutcome;
import com.sei.nexus.governance.MaskResult;
import com.sei.nexus.governance.RlsResult;
import com.sei.nexus.governance.SqlGovernancePipeline;
import com.sei.nexus.query.QueryExecutionRepository;
import com.sei.nexus.runtime.GovernedSqlRuntime;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0003 A14 — the runtime enforcement gate + governance routing on the agent
 * {@code query_database} path.
 *
 * <p>The runtime consumes a pre-compiled {@link ExecutionContract} and rejects any table
 * outside its approved surface BEFORE {@link SqlGovernancePipeline}; approved SQL then
 * flows through governance exactly as ADR-0003 A2. Hand-rolled fakes; no database.
 */
class AgentToolRegistryTest {

    private static final String CONN = "conn-1";
    private static final List<String> ALLOWED = List.of(CONN);
    private static final SqlTableReferenceExtractor EXTRACTOR = new SqlTableReferenceExtractor();

    static class FakePipeline extends SqlGovernancePipeline {
        GovernanceOutcome outcome;
        boolean invoked = false;
        FakePipeline() { super(null, null, null, null, null); }
        @Override public GovernanceOutcome governSql(String runKey, int stepNo, String connectionKey,
                String objectKeys, String sql, String userEmail, boolean forceAsync) {
            invoked = true;
            return outcome;
        }
    }

    static class FakeAudit extends GovernanceAuditService {
        record Call(boolean blocked, Integer rowCount) {}
        final List<Call> calls = new ArrayList<>();
        FakeAudit() { super(null, null, null); }
        @Override public void recordOutcome(GovernanceOutcome outcome, String userEmail, String runKey,
                String connectionKey, List<String> objectKeys, Integer rowCount,
                Integer executionMs, boolean blocked) {
            calls.add(new Call(blocked, rowCount));
        }
    }

    static class FakeDynamicSql extends DynamicSqlService {
        record Call(String sql, int maxRows, boolean readOnly) {}
        final List<Call> calls = new ArrayList<>();
        FakeDynamicSql() { super(null); }
        @Override public List<Map<String, Object>> executeQuery(String connectionKey,
                String approvedSql, int maxRows, boolean readOnly) {
            calls.add(new Call(approvedSql, maxRows, readOnly));
            return List.of(Map.of("id", 1));
        }
    }

    private FakePipeline      pipeline;
    private FakeAudit         audit;
    private FakeDynamicSql    dynamicSql;
    private AgentToolRegistry registry;

    /**
     * Agent policy never tracks the {@code nexus_query_execution} record — any write here is a
     * behaviour-parity break against the pre-Phase-1 agent path.
     */
    static class ForbiddenExecutionRepo extends QueryExecutionRepository {
        ForbiddenExecutionRepo() { super(null); }
        @Override public void updateStatus(String executionKey, String status, Instant startedAt,
                                           Instant completedAt, String errorMessage) {
            throw new AssertionError("agent path must not write execution records");
        }
        @Override public void updateResult(String executionKey, String resultJson,
                                           String status, Instant completedAt) {
            throw new AssertionError("agent path must not write execution records");
        }
    }

    @BeforeEach
    void setUp() {
        pipeline   = new FakePipeline();
        audit      = new FakeAudit();
        dynamicSql = new FakeDynamicSql();
        // connectionRepository is null: the agent policy never requests an existence check
        // (its allow-list check happens in AgentToolRegistry), so it must never be touched.
        GovernedSqlRuntime runtime = new GovernedSqlRuntime(pipeline, dynamicSql, audit,
                EXTRACTOR, new ForbiddenExecutionRepo(), null, new ObjectMapper());
        registry = new AgentToolRegistry(/* openAi */ null, new ObjectMapper(), runtime);
    }

    /** An ExecutionContract approving the given physical tables on CONN. */
    private static ExecutionContract contractApproving(String... tables) {
        Set<ExecutionBindings.ApprovedAsset> assets = new HashSet<>();
        Map<String, ExecutionBindings.ExecutionTarget> objectBindings = new LinkedHashMap<>();
        for (String t : tables) {
            assets.add(new ExecutionBindings.ApprovedAsset(CONN, EXTRACTOR.canonical(t)));
            objectBindings.put(t, new ExecutionBindings.ExecutionTarget("SQL", CONN, "public", t, null));
        }
        ExecutionBindings bindings = new ExecutionBindings(objectBindings, Map.of(), assets);
        SemanticView view = new SemanticView(List.of());
        return new ExecutionContract("ctr-test", Instant.now(), "agent-1",
                List.of(CONN), "hash", view, bindings);
    }

    private String runQuery(String sql, ExecutionContract contract) {
        return registry.execute("query_database",
                Map.of("connection_key", CONN, "sql", sql), ALLOWED, "u@x.com", "run-1", 3, contract);
    }

    private static GovernanceOutcome execOutcome(String governedSql, int rowLimit) {
        return new GovernanceOutcome(GovernanceOutcome.Verdict.EXECUTE, null, governedSql,
                "SELECT approved", "BOUNDED_LIST", "EXECUTE_SYNC", rowLimit, "ek", "analyst",
                ContractResult.passed(List.of()), RlsResult.passThrough(governedSql),
                MaskResult.passThrough(governedSql));
    }

    // ── the enforcement gate: unapproved table rejected before governance ───────

    @Test
    void unapprovedTableIsRejectedBeforeGovernanceAndNeverExecutes() {
        String result = runQuery("SELECT COUNT(*) FROM invoices", contractApproving("orders"));

        assertTrue(result.contains("not in the approved execution contract"),
                "the runtime rejects the invented table deterministically: " + result);
        assertTrue(result.contains("invoices".toUpperCase()) || result.contains("INVOICES"),
                "the unauthorized table is named");
        assertFalse(pipeline.invoked, "SqlGovernancePipeline is NOT invoked for an unapproved table");
        assertTrue(dynamicSql.calls.isEmpty(), "an unapproved table never reaches the database");
        assertTrue(audit.calls.isEmpty());
    }

    // ── approved table flows into governance exactly as A2 ──────────────────────

    @Test
    void approvedTableFlowsIntoGovernanceAndExecutesReadOnly() {
        pipeline.outcome = execOutcome("SELECT id FROM orders WHERE region = 'X'", 50);

        String result = runQuery("SELECT id FROM orders", contractApproving("orders"));

        assertTrue(pipeline.invoked, "an approved table reaches governance");
        assertEquals(1, dynamicSql.calls.size());
        FakeDynamicSql.Call call = dynamicSql.calls.get(0);
        assertEquals("SELECT id FROM orders WHERE region = 'X'", call.sql());
        assertEquals(50, call.maxRows());
        assertTrue(call.readOnly(), "agent SQL executes read-only (A2 preserved)");
        assertTrue(result.contains("\"id\":1"));
        assertEquals(1, audit.calls.size());
        assertFalse(audit.calls.get(0).blocked());
    }

    // ── governance verdicts still surface (gate passes, pipeline decides) ───────

    @Test
    void governanceBlockStillSurfacesForApprovedTable() {
        pipeline.outcome = new GovernanceOutcome(GovernanceOutcome.Verdict.BLOCKED,
                "Only SELECT statements are allowed", null, "SELECT id FROM orders",
                "BLOCKED", "BLOCK", 100, "ek", "analyst", null, null, null);

        String result = runQuery("SELECT id FROM orders", contractApproving("orders"));

        assertTrue(pipeline.invoked);
        assertTrue(result.contains("Query rejected: Only SELECT statements are allowed"));
        assertTrue(dynamicSql.calls.isEmpty());
    }

    // ── allow-list is enforced before the contract gate ─────────────────────────

    @Test
    void disallowedConnectionIsForbiddenBeforeGateAndGovernance() {
        NexusException ex = assertThrows(NexusException.class, () -> registry.execute(
                "query_database",
                Map.of("connection_key", "conn-other", "sql", "SELECT id FROM orders"),
                ALLOWED, "u@x.com", "run-1", 1, contractApproving("orders")));

        assertTrue(ex.getMessage().contains("conn-other"));
        assertFalse(pipeline.invoked);
        assertTrue(dynamicSql.calls.isEmpty());
    }

    // ── describe_schema is scoped to the contract (no raw catalog) ──────────────

    @Test
    void describeSchemaReturnsOnlyApprovedObjects() throws Exception {
        String result = registry.execute("describe_schema",
                Map.of("connection_key", CONN), ALLOWED, "u@x.com", "run-1", 1,
                contractApproving("orders", "shipments"));

        assertTrue(result.contains("orders"));
        assertTrue(result.contains("shipments"));
        assertFalse(result.contains("nexus_"), "no raw catalog tables are exposed");
    }
}
