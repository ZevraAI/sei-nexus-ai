package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.runtime.GovernedSqlRuntime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A database execution failure (e.g. a column/literal type mismatch) is a physical observation the
 * planner can learn from — like a gated or rejected step — so it is shown to the planner as
 * evidence and re-planned, bounded by {@link ReasoningEngine#MAX_FAILED_REPLANS}. Java decides
 * nothing about the fix: the (fake) planner stands in for the LLM reading the error. Domain-neutral
 * fixtures.
 */
class ReasoningEngineExecutionFailureReplanTest {

    private static ReasoningRepository noOpRepository() {
        return new ReasoningRepository(null) {
            @Override public void saveSession(ReasoningSession s) { }
            @Override public void updateSessionStatus(String k, String s, String c, Double conf, Instant at) { }
            @Override public void saveStep(ReasoningStep step) { }
        };
    }

    private static ReasoningEvaluator sufficientEvaluator() {
        return new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                return new EvaluationResult("SUFFICIENT", "ok");
            }
        };
    }

    private static ReasoningEngine engine(ReasoningPlanner planner, GovernedSqlRuntime runtime) {
        return new ReasoningEngine(planner, sufficientEvaluator(), new ReasoningEventBus(new ObjectMapper()),
                noOpRepository(), runtime, new ObjectMapper(),
                new ColumnMetadataRequestHandler(new com.sei.nexus.agentbrain.PromptContextBuilder(),
                        new com.sei.nexus.agentbrain.PromptAssembler(),
                        new com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler(null)));
    }

    private static GovernedSqlRuntime.Outcome failed(String msg) {
        return new GovernedSqlRuntime.Outcome(GovernedSqlRuntime.Status.FAILED, msg, null, List.of(), "[]",
                0L, List.of(), null, new RuntimeException(msg), null);
    }

    private static GovernedSqlRuntime.Outcome executed() {
        return new GovernedSqlRuntime.Outcome(GovernedSqlRuntime.Status.EXECUTED, null, null,
                List.of(Map.of("id", "r1")), "[{\"id\":\"r1\"}]", 3L, List.of(), null, null, null);
    }

    @Test
    void aFailedStepIsShownToThePlannerAsEvidenceAndTheCorrectedQuerySucceeds() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger runtimeCalls = new AtomicInteger();
        List<String> evidenceSeenByPlanner = new ArrayList<>();

        ReasoningPlanner planner = new ReasoningPlanner(null, null) {
            @Override public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                evidenceSeenByPlanner.add(evidence.buildContextForLlm());
                int call = plannerCalls.incrementAndGet();
                if (call == 1) return new StepPlan("first try", "SELECT id FROM t WHERE uuid_col = 'X-1'", "conn-1", "", "r");
                if (call == 2) return new StepPlan("corrected", "SELECT id FROM t WHERE code = 'X-1'", "conn-1", "", "r");
                return null;
            }
        };
        GovernedSqlRuntime runtime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) {
                return runtimeCalls.incrementAndGet() == 1
                        ? failed("Query failed: invalid input syntax for type uuid: \"X-1\"")
                        : executed();
            }
        };

        ReasoningEngine.ReasoningResult result = engine(planner, runtime).reason(
                "q", "q", "rsession", "schema", "run", "user@test.com",
                false, null, null, false, "conv", null, null, null);

        assertEquals(2, runtimeCalls.get(), "the corrected query was executed after the failure");
        assertTrue(evidenceSeenByPlanner.get(1).contains("invalid input syntax for type uuid"),
                "the planner is shown the database's own error so it can correct itself");
        List<EvidenceStore.StepEvidence> steps = result.evidence().getSteps();
        assertEquals("ERROR", steps.get(0).outcome());
        assertEquals(EvidenceStore.OUTCOME_QUERY_SUCCEEDED, steps.get(1).outcome());
        assertEquals("r1", result.queryData().get(0).get("id"));
    }

    @Test
    void selfCorrectionIsBoundedSoAnAlwaysFailingQueryCannotLoop() {
        AtomicInteger runtimeCalls = new AtomicInteger();

        ReasoningPlanner planner = new ReasoningPlanner(null, null) {
            @Override public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                return new StepPlan("try", "SELECT broken", "conn-1", "", "r");
            }
        };
        GovernedSqlRuntime runtime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) {
                runtimeCalls.incrementAndGet();
                return failed("Query failed: boom");
            }
        };

        ReasoningEngine.ReasoningResult result = engine(planner, runtime).reason(
                "q", "q", "rsession", "schema", "run", "user@test.com",
                false, null, null, false, "conv", null, null, null);

        assertEquals(ReasoningEngine.MAX_FAILED_REPLANS + 1, runtimeCalls.get(),
                "one attempt plus at most MAX_FAILED_REPLANS corrections");
        assertTrue(result.evidence().getSteps().stream().allMatch(s -> "ERROR".equals(s.outcome())));
        assertTrue(result.queryData().isEmpty());
    }
}
