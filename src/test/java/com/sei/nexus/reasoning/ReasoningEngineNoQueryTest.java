package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.runtime.GovernedSqlRuntime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReasoningEngine#reason}'s handling of a planner step that took NO action while no evidence
 * existed ({@link ReasoningPlanner.StepPlan#isNoQuery()}). The engine records the plain fact that
 * nothing was queried, with the planner's own reason, and stops — it decides nothing and never
 * presents the outcome as an empty query result. Same hand-rolled-fakes convention as {@link
 * ReasoningEngineClarificationTest}.
 */
class ReasoningEngineNoQueryTest {

    @Test
    void aNoQueryStepIsRecordedAsAFactNeverExecutedNeverEvaluatedAndStopsTheLoop() {
        AtomicInteger plannerCalls = new AtomicInteger(0);
        AtomicBoolean runtimeCalled = new AtomicBoolean(false);

        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                plannerCalls.incrementAndGet();
                return StepPlan.noQuery("No business object was selected for this question.");
            }
        };
        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override
            public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                fail("nothing executed, so there is nothing for the evaluator to judge");
                return null;
            }
        };
        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override
            public Outcome execute(Request r) {
                runtimeCalled.set(true);
                fail("no SQL may reach GovernedSqlRuntime when the planner took no action");
                return null;
            }
        };
        ReasoningRepository fakeRepository = new ReasoningRepository(null) {
            @Override public void saveSession(ReasoningSession s) { /* no-op */ }
            @Override public void updateSessionStatus(String sessionKey, String status,
                    String conclusion, Double confidence, Instant concludedAt) { /* no-op */ }
            @Override public void saveStep(ReasoningStep step) { /* no-op */ }
        };
        ReasoningEngine engine = new ReasoningEngine(fakePlanner, fakeEvaluator,
                new ReasoningEventBus(new ObjectMapper()), fakeRepository, fakeRuntime, new ObjectMapper(),
                new ColumnMetadataRequestHandler(new com.sei.nexus.agentbrain.PromptContextBuilder(),
                        new com.sei.nexus.agentbrain.PromptAssembler(),
                        new com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler(null)));

        String question = "some question";
        ReasoningEngine.ReasoningResult result = engine.reason(
                question, question, "rsession-test", "schema context", "run-test", "user@test.com",
                false, null, null, false, "conv-test", null, null, null);

        assertEquals(1, plannerCalls.get(), "the loop stops — Java does not re-plan on its own");
        assertFalse(runtimeCalled.get());
        assertTrue(result.queryData().isEmpty());

        List<EvidenceStore.StepEvidence> steps = result.evidence().getSteps();
        assertEquals(1, steps.size());
        assertEquals(EvidenceStore.OUTCOME_NO_QUERY_EXECUTED, steps.get(0).outcome());
        assertNotEquals(EvidenceStore.OUTCOME_QUERY_SUCCEEDED, steps.get(0).outcome(),
                "must never look like an executed query that returned zero rows");
        assertNull(steps.get(0).evaluatorDecision());
        assertEquals("No business object was selected for this question.", steps.get(0).evaluatorRationale(),
                "the planner's own reason is preserved verbatim for the composer");
        assertTrue(steps.get(0).sql() == null || steps.get(0).sql().isBlank());
        assertTrue(steps.get(0).rows().isEmpty());
    }
}
