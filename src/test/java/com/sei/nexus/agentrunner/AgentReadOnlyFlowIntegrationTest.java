package com.sei.nexus.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.AgentBrain;
import com.sei.nexus.agentbrain.ExecutionContractBuilder;
import com.sei.nexus.agentbrain.PromptAssembler;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.ResolvedBusinessModel;
import com.sei.nexus.ai.AgentMessage;
import com.sei.nexus.ai.AgentToolResponse;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.governance.ContractResult;
import com.sei.nexus.governance.GovernanceAuditService;
import com.sei.nexus.governance.GovernanceOutcome;
import com.sei.nexus.governance.MaskResult;
import com.sei.nexus.governance.RlsResult;
import com.sei.nexus.governance.SqlGovernancePipeline;
import com.sei.nexus.run.NexusRun;
import com.sei.nexus.run.RunRepository;
import com.sei.nexus.semanticmodel.AttributeRole;
import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0003 Story A1 — end-to-end agent execution flow with SQL safety and the
 * read-only execution path.
 *
 * <p>Drives the real {@link AgentRunner} ReAct loop through the real
 * {@link AgentToolRegistry} and the real {@link SqlSafetyService}. Only the
 * boundaries are faked (scripted LLM, recording {@link DynamicSqlService},
 * in-memory session store) so the test observes exactly what production wires
 * together: a rejected write becomes a first-class {@code TOOL_CALL} observation
 * in the persisted trace, the loop re-plans and completes, and only a safe
 * SELECT reaches the database — in read-only mode.
 *
 * <p>Hand-rolled fakes only; no database, no network, no Mockito. Database-level
 * enforcement (a SELECT that calls a writing function being rejected by the
 * connection's read-only flag) remains a manual verification — the fake JDBC
 * layer cannot exercise a real driver.
 */
class AgentReadOnlyFlowIntegrationTest {

    private static final String CONN = "conn-1";

    // ── scripted LLM ──────────────────────────────────────────────────────────

    /** Returns pre-scripted tool/final responses in order, one per ReAct iteration. */
    static class ScriptedOpenAi extends AzureOpenAiClient {
        private final Deque<AgentToolResponse> script = new ArrayDeque<>();
        ScriptedOpenAi() { super(new ObjectMapper(), null); }
        ScriptedOpenAi then(AgentToolResponse r) { script.addLast(r); return this; }

        @Override public AgentToolResponse chatWithTools(List<AgentMessage> messages,
                                                         String systemPrompt,
                                                         List<Map<String, Object>> tools) {
            if (script.isEmpty()) throw new IllegalStateException("LLM script exhausted");
            return script.removeFirst();
        }
    }

    // ── recording SQL execution seam ──────────────────────────────────────────

    static class RecordingDynamicSql extends DynamicSqlService {
        record Call(String sql, int maxRows, boolean readOnly) {}
        final List<Call> calls = new ArrayList<>();

        RecordingDynamicSql() { super(null); }

        @Override public List<Map<String, Object>> executeQuery(String connectionKey,
                                                                String approvedSql,
                                                                int maxRows,
                                                                boolean readOnly) {
            calls.add(new Call(approvedSql, maxRows, readOnly));
            return List.of(Map.of("id", 5, "status", "open"));
        }

        // Keep schema-context assembly empty so the run stays on the tool path only
        // (no status-value sampling), isolating what A1 governs.
        @Override public List<Map<String, Object>> listTablesWithColumnCounts(String c, String s) {
            return List.of();
        }
    }

    // ── governance pipeline fake: SELECT → EXECUTE (passthrough), else → BLOCKED ─
    //     Records the runKey of every call so the test can assert one shared run.

    static class FakeGovPipeline extends SqlGovernancePipeline {
        final List<String> runKeys = new ArrayList<>();
        FakeGovPipeline() { super(null, null, null, null, null); }
        @Override public GovernanceOutcome governSql(String runKey, int stepNo, String connKey,
                String objectKeys, String sql, String userEmail, boolean forceAsync) {
            runKeys.add(runKey);
            if (sql.strip().toUpperCase().startsWith("SELECT")) {
                return new GovernanceOutcome(GovernanceOutcome.Verdict.EXECUTE, null, sql, sql,
                        "BOUNDED_LIST", "EXECUTE_SYNC", 100, "ek", "analyst",
                        ContractResult.passed(List.of()), RlsResult.passThrough(sql),
                        MaskResult.passThrough(sql));
            }
            return new GovernanceOutcome(GovernanceOutcome.Verdict.BLOCKED,
                    "Only SELECT statements are allowed", null, sql, "BLOCKED", "BLOCK",
                    100, "ek", "analyst", null, null, null);
        }
    }

    static class NoopAudit extends GovernanceAuditService {
        NoopAudit() { super(null, null, null); }
        @Override public void recordOutcome(GovernanceOutcome outcome, String userEmail, String runKey,
                String connectionKey, List<String> objectKeys, Integer rowCount,
                Integer executionMs, boolean blocked) { /* audited elsewhere */ }
    }

    // ── capturing governance run store ────────────────────────────────────────

    static class CapturingRunRepository extends RunRepository {
        NexusRun saved;
        int saveCount = 0;
        CapturingRunRepository() { super(null); }
        @Override public void save(NexusRun run) { this.saved = run; this.saveCount++; }
    }

    // ── Agent Brain fake: resolves a fixed approved business-object surface ─────
    //     "orders" only — so a query against "invoices" is an unapproved (invented) object.

    static class FakeAgentBrain extends AgentBrain {
        FakeAgentBrain() { super(null, null); }
        @Override public ResolvedBusinessModel resolve(ZevraAgent agent, String question) {
            BusinessObject orders = new BusinessObject("obj-orders", "Orders", "Order records",
                    List.of(new BusinessAttribute("c-id",     "Id",     AttributeRole.IDENTIFIER),
                            new BusinessAttribute("c-status", "Status", AttributeRole.DIMENSION)),
                    List.of());
            return new ResolvedBusinessModel(agent.id(), agent.connectionKeys(), question,
                    List.of(orders),
                    Map.of("obj-orders", new PhysicalTable(CONN, "public", "orders")),
                    Map.of("c-id",     new PhysicalColumn(CONN, "public", "orders", "id"),
                           "c-status", new PhysicalColumn(CONN, "public", "orders", "status")));
        }
    }

    // ── in-memory session store ───────────────────────────────────────────────

    static class InMemoryRepository extends ZevraAgentRepository {
        private ZevraSession session;
        InMemoryRepository() { super(null); }

        @Override public void insertSession(ZevraSession s) { this.session = s; }

        @Override public void completeSession(String sessionId, String status, String stepsJson,
                                              String finalOutput, String errorMessage, int iterations) {
            this.session = new ZevraSession(sessionId, session.agentId(), session.tenantSchema(),
                    session.inputMessage(), status, stepsJson, finalOutput, errorMessage,
                    iterations, session.startedAt(), session.completedAt());
        }

        @Override public Optional<ZevraSession> findSessionById(String id, String tenantSchema) {
            return Optional.ofNullable(session);
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private RecordingDynamicSql    dynamicSql;
    private InMemoryRepository     repository;
    private CapturingRunRepository runRepo;
    private FakeGovPipeline        govPipeline;
    private final ObjectMapper     mapper = new ObjectMapper();

    private static ZevraAgent agent() {
        return new ZevraAgent("agent-1", "public", "Ops Analyst", "ops-analyst",
                "desc", "You are an analyst.", "Answer questions.",
                List.of(CONN), 5, "ACTIVE", "user@x.com", null, null);
    }

    private AgentRunner runnerWith(ScriptedOpenAi openAi) {
        dynamicSql  = new RecordingDynamicSql();
        repository  = new InMemoryRepository();
        runRepo     = new CapturingRunRepository();
        govPipeline = new FakeGovPipeline();
        SqlTableReferenceExtractor extractor = new SqlTableReferenceExtractor();
        // Phase 1: the agent path executes through the shared deterministic runtime.
        com.sei.nexus.runtime.GovernedSqlRuntime runtime =
                new com.sei.nexus.runtime.GovernedSqlRuntime(govPipeline, dynamicSql,
                        new NoopAudit(), extractor, null, null, mapper);
        AgentToolRegistry registry = new AgentToolRegistry(openAi, mapper, runtime);
        return new AgentRunner(openAi, registry, repository, mapper, runRepo,
                new FakeAgentBrain(), new ExecutionContractBuilder(extractor),
                new PromptContextBuilder(), new PromptAssembler());
    }

    private static AgentToolResponse query(String sql) {
        return AgentToolResponse.ofToolCall("query_database", "call-" + sql.hashCode(),
                Map.of("connection_key", CONN, "sql", sql));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps(ZevraSession s) throws Exception {
        return mapper.readValue(s.stepsJson(), List.class);
    }

    @BeforeEach
    void reset() { /* fixtures built per-runner */ }

    // ── the complete flow: reject → re-plan → read-only execute → complete ────

    @Test
    void rejectedSqlBecomesToolObservationThenLoopCompletesViaReadOnlySelect() throws Exception {
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(query("UPDATE orders SET status = 'x' RETURNING id"))   // 1: rejected write
                .then(query("SELECT id, status FROM orders WHERE id = 5"))    // 2: safe, executes
                .then(AgentToolResponse.ofFinal("Order 5 is open."));         // 3: terminal
        AgentRunner runner = runnerWith(openAi);

        ZevraSession session = runner.run(agent(), "What is the status of order 5?", "u@x.com", null);

        // Terminal state reached
        assertEquals("COMPLETED", session.status(), "the loop reaches a completed terminal state");
        assertEquals("Order 5 is open.", session.finalOutput());

        // A governance run was created for the session, carrying the caller identity
        // (ADR-0003 A2, Commit 3) — the FK parent for governed query executions.
        assertNotNull(runRepo.saved, "a governance nexus_run is created per agent session");
        assertEquals("u@x.com", runRepo.saved.userEmail(), "caller identity threaded onto the run");
        assertEquals("RUNNING", runRepo.saved.status());

        // Exactly one governance run per session, and every SQL tool execution in the
        // session routed through governance with that same runKey (A2, Commit 4).
        assertEquals(1, runRepo.saveCount, "exactly one governance run per agent session");
        assertEquals(2, govPipeline.runKeys.size(), "both tool calls were governed");
        assertTrue(govPipeline.runKeys.stream().allMatch(k -> k.equals(runRepo.saved.runKey())),
                "every governed SQL execution shares the session's runKey");

        List<Map<String, Object>> steps = steps(session);
        List<Map<String, Object>> toolCalls = steps.stream()
                .filter(s -> "TOOL_CALL".equals(s.get("type"))).toList();
        assertEquals(2, toolCalls.size(), "both the rejected and the accepted call are traced");

        // The rejection is a FIRST-CLASS TOOL_CALL observation in the persisted trace
        Map<String, Object> rejectedStep = toolCalls.get(0);
        assertEquals("query_database", rejectedStep.get("tool"));
        Object output = rejectedStep.get("output");
        assertTrue(output instanceof Map, "rejection surfaces as a structured tool observation");
        String error = String.valueOf(((Map<?, ?>) output).get("error"));
        assertTrue(error.startsWith("Query rejected:"),
                "the rejection reason is recorded in the trace: " + error);

        // The loop CONTINUED past the rejection and terminated with a final answer
        assertTrue(steps.stream().anyMatch(s -> "FINAL_ANSWER".equals(s.get("type"))),
                "a final answer step is recorded after the rejection");

        // Only the safe SELECT reached the database, and it ran read-only
        assertEquals(1, dynamicSql.calls.size(),
                "the rejected write never reached executeQuery; only the SELECT did");
        RecordingDynamicSql.Call call = dynamicSql.calls.get(0);
        assertEquals("SELECT id, status FROM orders WHERE id = 5", call.sql());
        assertTrue(call.readOnly(), "the agent SELECT executes in read-only mode (readOnly=true)");
    }

    // ── valid SELECT alone reaches the read-only path ─────────────────────────

    @Test
    void validSelectRunReachesReadOnlyExecutionAndCompletes() {
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(query("SELECT id FROM orders"))
                .then(AgentToolResponse.ofFinal("There is one order."));
        AgentRunner runner = runnerWith(openAi);

        ZevraSession session = runner.run(agent(), "How many orders?", "u@x.com", null);

        assertEquals("COMPLETED", session.status());
        assertEquals(1, dynamicSql.calls.size());
        assertTrue(dynamicSql.calls.get(0).readOnly(),
                "readOnly=true reaches DynamicSqlService on the agent path");
    }

    // ── NexusRun lifecycle regression (duplicate-run bug fix) ──────────────────

    @Test
    void autonomousRunCreatesExactlyOneGovernanceRun() {
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(query("SELECT id FROM orders"))
                .then(AgentToolResponse.ofFinal("done"));
        AgentRunner runner = runnerWith(openAi);

        runner.run(agent(), "q", "u@x.com", /* existingRunKey */ null);

        assertEquals(1, runRepo.saveCount,
                "autonomous execution (direct agent chat / brief) creates exactly one governance run");
        assertFalse(govPipeline.runKeys.isEmpty());
        assertTrue(govPipeline.runKeys.stream().allMatch(k -> k.equals(runRepo.saved.runKey())),
                "governed queries attach to the created governance run");
    }

    @Test
    void routedRunReusesCallerRunKeyAndSavesNoGovernanceRun() {
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(query("SELECT id FROM orders"))
                .then(AgentToolResponse.ofFinal("done"));
        AgentRunner runner = runnerWith(openAi);

        runner.run(agent(), "q", "u@x.com", /* existingRunKey */ "caller-run-1");

        assertEquals(0, runRepo.saveCount,
                "routed chat reuses the caller's run — the runtime inserts no second nexus_run");
        assertFalse(govPipeline.runKeys.isEmpty());
        assertTrue(govPipeline.runKeys.stream().allMatch(k -> k.equals("caller-run-1")),
                "the agent's governed queries attach to the caller's runKey");
    }

    // ── ADR-0003 A15: invented business object is gated, never executed ─────────

    @Test
    void unknownBusinessObjectIsGatedAndResolvedAsAReasoningOutcome() throws Exception {
        // The agent's approved surface is "orders" only (FakeAgentBrain). The model asks
        // about invoices — a business object that does not exist.
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(query("SELECT COUNT(*) FROM invoices"))
                .then(AgentToolResponse.ofFinal(
                        "There is no Invoice business object available for this agent."));
        AgentRunner runner = runnerWith(openAi);

        ZevraSession session = runner.run(agent(), "How many invoices exist?", "u@x.com", null);

        assertEquals("COMPLETED", session.status(), "the loop terminates with a reasoning outcome");
        assertEquals("There is no Invoice business object available for this agent.", session.finalOutput());

        // The invented table never reached governance or the database.
        assertTrue(govPipeline.runKeys.isEmpty(),
                "SqlGovernancePipeline is never invoked for an unapproved business object");
        assertTrue(dynamicSql.calls.isEmpty(), "no SQL executes for the invented table");

        // The rejection is a first-class, deterministic TOOL_CALL observation.
        List<Map<String, Object>> steps = steps(session);
        Map<String, Object> toolCall = steps.stream()
                .filter(s -> "TOOL_CALL".equals(s.get("type"))).findFirst().orElseThrow();
        String error = String.valueOf(((Map<?, ?>) toolCall.get("output")).get("error"));
        assertTrue(error.contains("not in the approved execution contract"),
                "deterministic runtime observation, got: " + error);

        // The run was still grounded by a compiled ExecutionContract (traceable).
        Map<String, Object> resolve = steps.stream()
                .filter(s -> "CONTEXT_RESOLVE".equals(s.get("type"))).findFirst().orElseThrow();
        assertNotNull(resolve.get("contractId"), "contractId is recorded in the trace");
    }
}
