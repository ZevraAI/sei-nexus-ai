package com.sei.nexus.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.ExecutionContractBuilder;
import com.sei.nexus.agentbrain.PromptAssembler;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentmemory.BusinessWorldToolAdapter;
import com.sei.nexus.agentmemory.ConversationMemoryService;
import com.sei.nexus.agentmemory.RelationshipExplorationAdapter;
import com.sei.nexus.ai.AgentMessage;
import com.sei.nexus.ai.AgentToolResponse;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.runtime.ExecutionReferenceRepository;
import com.sei.nexus.runtime.GovernedSqlRuntime;
import com.sei.nexus.sql.SqlColumnReferenceExtractor;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9B — behavioral baseline for {@link AgentRunner}'s CURRENT Chat-Completions-based
 * ReAct loop, ahead of a future OpenAI Responses API migration (see
 * {@code docs/architecture/phase-9-agent-runner-responses-investigation.md}).
 *
 * <p>These tests describe exactly how {@link AgentRunner} behaves TODAY — including behavior
 * that is surprising, asymmetric, or that a later phase may intentionally change (e.g. only the
 * first of several tool calls in one model response is ever executed). None of these tests
 * "fix" or "improve" anything; they pin the current, observed behavior so a future migration can
 * be checked against a known baseline. No production code is modified by this phase.
 *
 * <p>Reuses {@link AgentReadOnlyFlowIntegrationTest}'s hand-rolled fakes (package-visible nested
 * classes) exactly as-is — same convention as that test, no Mockito, no database, no network.
 */
class AgentRunnerBehaviorBaselineTest {

    private static final String CONN = "conn-1";
    private final ObjectMapper mapper = new ObjectMapper();

    /** Same scripted-response convention as {@link AgentReadOnlyFlowIntegrationTest.ScriptedOpenAi},
     *  duplicated locally so this file has no execution-order dependency on that class. */
    static class ScriptedOpenAi extends AzureOpenAiClient {
        private final Deque<AgentToolResponse> script = new ArrayDeque<>();
        ScriptedOpenAi() { super(new ObjectMapper(), null); }
        ScriptedOpenAi then(AgentToolResponse r) { script.addLast(r); return this; }
        // Phase 9C: AgentRunner now calls respondWithTools (Responses API).
        @Override public AgentToolResponse respondWithTools(List<AgentMessage> messages,
                                                         String systemPrompt,
                                                         List<Map<String, Object>> tools) {
            if (script.isEmpty()) throw new IllegalStateException("LLM script exhausted");
            return script.removeFirst();
        }
    }

    /** As {@link ScriptedOpenAi}, but additionally records the exact {@code messages} list passed
     *  on every {@code respondWithTools} invocation — the only externally-observable way to verify
     *  {@code AgentRunner#pruneHistory}'s effect, since that method is private. */
    static class HistoryCapturingOpenAi extends AzureOpenAiClient {
        private final Deque<AgentToolResponse> script = new ArrayDeque<>();
        final List<List<AgentMessage>> capturedMessages = new ArrayList<>();
        HistoryCapturingOpenAi() { super(new ObjectMapper(), null); }
        HistoryCapturingOpenAi then(AgentToolResponse r) { script.addLast(r); return this; }
        @Override public AgentToolResponse respondWithTools(List<AgentMessage> messages,
                                                         String systemPrompt,
                                                         List<Map<String, Object>> tools) {
            capturedMessages.add(List.copyOf(messages));
            if (script.isEmpty()) throw new IllegalStateException("LLM script exhausted");
            return script.removeFirst();
        }
    }

    private static ZevraAgent agent(int maxIterations) {
        return agent(maxIterations, List.of(CONN));
    }

    private static ZevraAgent agent(int maxIterations, List<String> connectionKeys) {
        return new ZevraAgent("agent-1", "public", "Ops Analyst", "ops-analyst",
                "desc", "You are an analyst.", "Answer questions.",
                connectionKeys, maxIterations, "ACTIVE", "user@x.com", null, null);
    }

    private static AgentToolResponse query(String sql, String callId) {
        return AgentToolResponse.ofToolCall("query_database", callId,
                Map.of("connection_key", CONN, "sql", sql));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps(ZevraSession s) throws Exception {
        return mapper.readValue(s.stepsJson(), List.class);
    }

    /** Builds a fully-wired {@link AgentRunner} using {@link AgentReadOnlyFlowIntegrationTest}'s
     *  package-visible fakes — same governed-SQL wiring as that test's own {@code runnerWith}. */
    private static class Harness {
        final AgentReadOnlyFlowIntegrationTest.RecordingDynamicSql dynamicSql =
                new AgentReadOnlyFlowIntegrationTest.RecordingDynamicSql();
        final AgentReadOnlyFlowIntegrationTest.InMemoryRepository repository =
                new AgentReadOnlyFlowIntegrationTest.InMemoryRepository();
        final AgentReadOnlyFlowIntegrationTest.CapturingRunRepository runRepo =
                new AgentReadOnlyFlowIntegrationTest.CapturingRunRepository();
        final AgentReadOnlyFlowIntegrationTest.FakeGovPipeline govPipeline =
                new AgentReadOnlyFlowIntegrationTest.FakeGovPipeline();
        final ObjectMapper mapper = new ObjectMapper();

        AgentRunner runner(AzureOpenAiClient openAi) {
            SqlTableReferenceExtractor extractor = new SqlTableReferenceExtractor();
            com.sei.nexus.runtime.ExecutionReferenceRepository refRepo =
                    new com.sei.nexus.runtime.ExecutionReferenceRepository(null, mapper) {
                        @Override public void save(com.sei.nexus.runtime.ExecutionReference r) { /* no-op */ }
                    };
            GovernedSqlRuntime runtime = new GovernedSqlRuntime(govPipeline, dynamicSql,
                    new AgentReadOnlyFlowIntegrationTest.NoopAudit(), extractor,
                    new SqlColumnReferenceExtractor(), null, null, refRepo, mapper);
            AgentToolRegistry registry = new AgentToolRegistry(openAi, mapper, runtime);
            return new AgentRunner(openAi, registry, repository, mapper, runRepo,
                    new AgentReadOnlyFlowIntegrationTest.FakeAgentBrain(),
                    new ExecutionContractBuilder(extractor),
                    new PromptContextBuilder(), new PromptAssembler());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // F. Iteration limit / salvage
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    void iterationLimitReachedThenSalvageProducesAFinalAnswer_completesWithForcedFlag() throws Exception {
        // Every regular iteration is rejected (non-SELECT), so the loop never terminates on
        // its own and always runs out the full maxIterations budget.
        Harness h = new Harness();
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(query("UPDATE orders SET status='x'", "call-1"))
                .then(query("UPDATE orders SET status='x'", "call-2"))
                .then(AgentToolResponse.ofFinal("Best effort answer from salvage."));
        AgentRunner runner = h.runner(openAi);

        ZevraSession session = runner.run(agent(2), "q", "u@x.com", null);

        assertEquals("COMPLETED", session.status(),
                "current behavior: a successful salvage call completes the session, not MAX_ITER");
        assertEquals("Best effort answer from salvage.", session.finalOutput());
        Map<String, Object> finalStep = steps(session).stream()
                .filter(s -> "FINAL_ANSWER".equals(s.get("type"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, finalStep.get("forcedAtMaxIterations"),
                "current behavior: a salvage-produced answer is flagged forcedAtMaxIterations");
    }

    @Test
    void iterationLimitReachedThenSalvageReturnsAnotherToolCall_discardedAsMaxIter() {
        // Salvage is called with `tools` still attached — current behavior does not prevent
        // the model from calling a tool again; when it does, that call is discarded (never
        // executed) and the session terminates as MAX_ITER, not as a further tool iteration.
        Harness h = new Harness();
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(query("UPDATE orders SET status='x'", "call-1"))
                .then(query("UPDATE orders SET status='x'", "call-2"))
                .then(query("UPDATE orders SET status='x'", "call-salvage"));
        AgentRunner runner = h.runner(openAi);

        ZevraSession session = runner.run(agent(2), "q", "u@x.com", null);

        assertEquals("MAX_ITER", session.status(),
                "current behavior: a non-final-answer tool call from salvage is discarded, never executed");
        assertTrue(session.finalOutput().contains("couldn't complete this request"));
        assertEquals("Maximum iterations reached without a final answer", session.errorMessage());
        assertTrue(h.dynamicSql.calls.isEmpty(),
                "none of the rejected UPDATE calls (including the discarded salvage one) ever reach execution");
    }

    @Test
    void iterationLimitReachedThenSalvageCallItselfThrows_treatedAsMaxIterNotFailed() {
        // The script has exactly `maxIterations` entries — the (maxIterations+1)th call (the
        // salvage call) finds an empty script and respondWithTools throws. salvageAnswer's own
        // local try/catch swallows this, current behavior treats it identically to a
        // non-answering salvage response: MAX_ITER, never FAILED.
        Harness h = new Harness();
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(query("UPDATE orders SET status='x'", "call-1"))
                .then(query("UPDATE orders SET status='x'", "call-2"));
        AgentRunner runner = h.runner(openAi);

        ZevraSession session = runner.run(agent(2), "q", "u@x.com", null);

        assertEquals("MAX_ITER", session.status(),
                "current behavior: an exception inside the salvage call is swallowed locally, "
                        + "producing MAX_ITER rather than a FAILED run");
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // G. Exception asymmetry: NexusException aborts, generic exception is recoverable
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    void nexusExceptionFromToolExecutionAbortsTheWholeRunAsFailed() {
        // query_database on a connection outside the agent's allow-list throws NexusException
        // (FORBIDDEN) — this is re-thrown uncaught by AgentToolRegistry, propagates through
        // AgentRunner's loop, and aborts the entire run.
        Harness h = new Harness();
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(AgentToolResponse.ofToolCall("query_database", "call-1",
                        Map.of("connection_key", "conn-not-allowed", "sql", "SELECT 1")));
        AgentRunner runner = h.runner(openAi);

        NexusException thrown = assertThrows(NexusException.class,
                () -> runner.run(agent(5), "q", "u@x.com", null));
        assertTrue(thrown.getMessage().contains("Agent execution failed"),
                "current behavior: run() itself throws after marking the session FAILED");

        ZevraSession session = h.repository.findSessionById("ignored", "public").orElseThrow();
        assertEquals("FAILED", session.status());
        assertTrue(h.dynamicSql.calls.isEmpty(), "the forbidden call must never reach execution");
        assertTrue(h.govPipeline.runKeys.isEmpty(), "the forbidden call must never reach governance");
    }

    @Test
    void genericExceptionFromToolExecutionIsRecoveredAsAToolObservation_runContinues() throws Exception {
        // A non-NexusException thrown by a tool's own collaborator (here: ConversationMemoryService
        // .isMember) is caught inside AgentToolRegistry.executeWithReference and converted into a
        // recoverable {"error": ...} observation fed back to the model — the run is NOT aborted.
        // This is the deliberate asymmetry documented in the Phase 9A investigation: whether a
        // failure aborts the run depends on exception type, not on which tool failed.
        Harness h = new Harness();
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(AgentToolResponse.ofToolCall("memory_get", "call-1",
                        Map.of("entity_key", "ek-1")))
                .then(AgentToolResponse.ofFinal("Recovered and answered anyway."));

        ConversationMemoryService throwingMemory = new ConversationMemoryService(null) {
            @Override public boolean isMember(String conversationId, String entityKey) {
                throw new RuntimeException("simulated boom");
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(openAi, mapper,
                new GovernedSqlRuntime(h.govPipeline, h.dynamicSql,
                        new AgentReadOnlyFlowIntegrationTest.NoopAudit(),
                        new SqlTableReferenceExtractor(), new SqlColumnReferenceExtractor(),
                        null, null,
                        new ExecutionReferenceRepository(null, mapper) {
                            @Override public void save(com.sei.nexus.runtime.ExecutionReference r) { }
                        }, mapper),
                throwingMemory, new BusinessWorldToolAdapter(null),
                new RelationshipExplorationAdapter(null),
                new ExecutionReferenceRepository(null, mapper), /* memoryCapabilitiesEnabled */ true);

        AgentRunner runner = new AgentRunner(openAi, registry, h.repository, mapper, h.runRepo,
                new AgentReadOnlyFlowIntegrationTest.FakeAgentBrain(),
                new ExecutionContractBuilder(new SqlTableReferenceExtractor()),
                new PromptContextBuilder(), new PromptAssembler());

        ZevraSession session = runner.run(agent(5), "q", "u@x.com", null);

        assertEquals("COMPLETED", session.status(),
                "current behavior: a generic exception never aborts the run");
        assertEquals("Recovered and answered anyway.", session.finalOutput());
        Map<String, Object> toolCall = steps(session).stream()
                .filter(s -> "TOOL_CALL".equals(s.get("type"))).findFirst().orElseThrow();
        Object output = toolCall.get("output");
        assertTrue(String.valueOf(output).contains("simulated boom"),
                "current behavior: the generic exception's message is relayed to the model verbatim, got: " + output);
    }

    @Test
    void unknownToolRequestedAbortsTheWholeRunAsFailed() {
        Harness h = new Harness();
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(AgentToolResponse.ofToolCall("no_such_tool", "call-1", Map.of()));
        AgentRunner runner = h.runner(openAi);

        assertThrows(NexusException.class, () -> runner.run(agent(5), "q", "u@x.com", null),
                "current behavior: an unknown tool name throws NexusException(BAD_REQUEST), "
                        + "aborting the run — identical to a disabled/never-registered tool");
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // H. Exact-repeat tool-call cache
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    void exactRepeatToolCallIsServedFromCacheNotReExecuted() throws Exception {
        Harness h = new Harness();
        String sql = "SELECT id FROM orders WHERE id = 5";
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(query(sql, "call-1"))
                .then(query(sql, "call-2"))   // identical tool + identical args
                .then(AgentToolResponse.ofFinal("done"));
        AgentRunner runner = h.runner(openAi);

        ZevraSession session = runner.run(agent(5), "q", "u@x.com", null);

        assertEquals("COMPLETED", session.status());
        assertEquals(1, h.dynamicSql.calls.size(),
                "current behavior: the second identical tool call is served from the in-memory "
                        + "cache and never re-executed against the database");

        List<Map<String, Object>> toolCalls = steps(session).stream()
                .filter(s -> "TOOL_CALL".equals(s.get("type"))).toList();
        assertEquals(2, toolCalls.size(), "both calls are still traced as separate steps");
        assertNull(toolCalls.get(0).get("cached"), "the first (executing) call is not marked cached");
        assertEquals(Boolean.TRUE, toolCalls.get(1).get("cached"),
                "current behavior: the cache-hit step is explicitly marked cached=true in the trace");
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // I. History pruning
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    void historyExceedingSixPairsIsPrunedKeepingTheFirstMessageAndOnlyTheLastSixPairs() {
        Harness h = new Harness();
        HistoryCapturingOpenAi openAi = new HistoryCapturingOpenAi();
        // 8 distinct tool-call iterations (exceeds HISTORY_KEEP_PAIRS=6), then a 9th call that
        // returns the final answer — maxIterations=9 so the loop never hits salvage.
        for (int i = 1; i <= 8; i++) {
            openAi.then(query("SELECT " + i + " FROM orders", "call-" + i));
        }
        openAi.then(AgentToolResponse.ofFinal("done"));
        AgentRunner runner = h.runner(openAi);

        ZevraSession session = runner.run(agent(9), "the original question", "u@x.com", null);

        assertEquals("COMPLETED", session.status());
        assertEquals(9, openAi.capturedMessages.size(), "9 respondWithTools calls were made");

        // The 9th call's messages reflect pruning applied to the 8 pairs accumulated from the
        // first 8 iterations: messages[0] (the original question) + the LAST 6 pairs (12 msgs).
        List<AgentMessage> sentForFinalCall = openAi.capturedMessages.get(8);
        assertEquals(13, sentForFinalCall.size(),
                "current behavior: messages[0] + last 6 tool-call/result pairs (12 messages) = 13");
        assertEquals("user", sentForFinalCall.get(0).role());
        assertTrue(sentForFinalCall.get(0).content().contains("the original question"),
                "current behavior: the original user message is never pruned, regardless of iteration count");

        // The surviving pairs must be exactly iterations 3..8 (the LAST six) — iterations 1 and 2
        // must have been dropped.
        List<String> survivingCallIds = new ArrayList<>();
        for (int i = 1; i < sentForFinalCall.size(); i += 2) {
            List<Map<String, Object>> toolCalls = sentForFinalCall.get(i).toolCalls();
            survivingCallIds.add(String.valueOf(toolCalls.get(0).get("id")));
        }
        assertEquals(List.of("call-3", "call-4", "call-5", "call-6", "call-7", "call-8"), survivingCallIds,
                "current behavior: only the LAST six tool-call pairs survive pruning, oldest-first order preserved");
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // L. Empty / no-content model response
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    void emptyFinalAnswerIsTreatedAsAValidSilentSuccess() {
        Harness h = new Harness();
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(AgentToolResponse.ofFinal(""));
        AgentRunner runner = h.runner(openAi);

        ZevraSession session = runner.run(agent(5), "q", "u@x.com", null);

        assertEquals("COMPLETED", session.status(),
                "current behavior: an empty-string final answer is a valid, silent success — "
                        + "not an error, not a special-cased empty-response failure");
        assertEquals("", session.finalOutput());
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // M. Tenant/connection isolation
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    void agentCannotQueryAConnectionOutsideItsOwnAllowedConnectionKeys() {
        // Simulates "tenant B's connection": the agent's own connectionKeys() is the only
        // allow-list AgentToolRegistry ever checks — a connection key belonging to another
        // tenant is rejected before governance or the database are ever reached.
        Harness h = new Harness();
        ScriptedOpenAi openAi = new ScriptedOpenAi()
                .then(AgentToolResponse.ofToolCall("query_database", "call-1",
                        Map.of("connection_key", "conn-tenant-b", "sql", "SELECT 1")));
        AgentRunner runner = h.runner(openAi);

        assertThrows(NexusException.class,
                () -> runner.run(agent(5, List.of("conn-1")), "q", "u@x.com", null),
                "current behavior: a connection key outside the agent's own connectionKeys() is "
                        + "rejected via NexusException(FORBIDDEN), aborting the run");
        assertTrue(h.dynamicSql.calls.isEmpty(),
                "the cross-connection query must never reach the database");
        assertTrue(h.govPipeline.runKeys.isEmpty(),
                "the cross-connection query must never reach governance");
    }
}
