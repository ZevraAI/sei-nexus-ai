package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.runtime.GovernedSqlRuntime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Semantic Reasoning Over Authoritative Value Domains — {@link ReasoningEngine#reason}'s handling
 * of a planner step that declines to generate SQL ({@link ReasoningPlanner.StepPlan#isClarification()}).
 * Same hand-rolled-fakes convention as {@link ReasoningEngineQueryDataSelectionTest}.
 */
class ReasoningEngineClarificationTest {

    @Test
    void aClarificationStepNeverReachesGovernedSqlRuntimeAndStopsTheLoop() {
        AtomicInteger plannerCalls = new AtomicInteger(0);
        AtomicBoolean runtimeCalled = new AtomicBoolean(false);

        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                plannerCalls.incrementAndGet();
                return new StepPlan("Status term needs clarification", null, null, "",
                        "no legal value or business definition matched 'open'", List.of(),
                        "'open' is not one of purchase_orders.status's legal values "
                                + "(draft, submitted, acknowledged, partially_received, received, cancelled, closed). "
                                + "Which one did you mean?");
            }
        };

        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override
            public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                fail("the evaluator must never be consulted for a step that executed no SQL");
                return null;
            }
        };

        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null) {
            @Override
            public Outcome execute(Request r) {
                runtimeCalled.set(true);
                fail("no SQL may ever reach GovernedSqlRuntime for a clarification step");
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
                new ReasoningEventBus(new ObjectMapper()), fakeRepository, fakeRuntime, new ObjectMapper());

        String question = "show purchase orders with status open";
        ReasoningEngine.ReasoningResult result = engine.reason(
                question, question, "rsession-test", "schema context", "run-test", "user@test.com",
                false, null, null, false, "conv-test", null, null);

        assertEquals(1, plannerCalls.get(), "the loop must stop after the first clarification — never re-plan on its own");
        assertFalse(runtimeCalled.get());
        assertTrue(result.queryData().isEmpty(), "a clarification step produces no rows");

        List<EvidenceStore.StepEvidence> steps = result.evidence().getSteps();
        assertEquals(1, steps.size());
        assertEquals("CLARIFICATION_NEEDED", steps.get(0).evaluatorDecision());
        assertTrue(steps.get(0).evaluatorRationale().contains("draft"),
                "the actual legal values must be present in the recorded evidence, unmodified");
        assertTrue(steps.get(0).sql() == null || steps.get(0).sql().isBlank());
    }

    @Test
    void aClarificationMidInvestigationPreservesEarlierStepsEvidence() {
        AtomicInteger plannerCalls = new AtomicInteger(0);

        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                int call = plannerCalls.incrementAndGet();
                if (call == 1) {
                    return new StepPlan("Look up supplier", "SELECT id FROM retail_core.suppliers LIMIT 1",
                            "conn-1", null, "resolve supplier first");
                }
                return new StepPlan("Status term needs clarification", null, null, "",
                        "no match", List.of(), "'open' is not a legal purchase_orders.status value.");
            }
        };

        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override
            public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                return new EvaluationResult("NEED_MORE_DATA", "keep going");
            }
        };

        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null) {
            @Override
            public Outcome execute(Request r) {
                return new Outcome(Status.EXECUTED, null, null,
                        List.of(Map.of("id", "s1")), "[{\"id\":\"s1\"}]", 5L, List.of(), null, null, null);
            }
        };

        ReasoningRepository fakeRepository = new ReasoningRepository(null) {
            @Override public void saveSession(ReasoningSession s) { /* no-op */ }
            @Override public void updateSessionStatus(String sessionKey, String status,
                    String conclusion, Double confidence, Instant concludedAt) { /* no-op */ }
            @Override public void saveStep(ReasoningStep step) { /* no-op */ }
        };

        ReasoningEngine engine = new ReasoningEngine(fakePlanner, fakeEvaluator,
                new ReasoningEventBus(new ObjectMapper()), fakeRepository, fakeRuntime, new ObjectMapper());

        String question = "show open purchase orders for our main supplier";
        ReasoningEngine.ReasoningResult result = engine.reason(
                question, question, "rsession-test", "schema context", "run-test", "user@test.com",
                false, null, null, false, "conv-test", null, null);

        assertEquals(2, plannerCalls.get());
        List<EvidenceStore.StepEvidence> steps = result.evidence().getSteps();
        assertEquals(2, steps.size(), "the earlier successful step's evidence must survive the later clarification");
        assertEquals("s1", steps.get(0).rows().get(0).get("id"));
        assertEquals("CLARIFICATION_NEEDED", steps.get(1).evaluatorDecision());
    }
}
