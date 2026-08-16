package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.runtime.GovernedSqlRuntime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the "turtleneck open orders" defect: Step 1 resolved a product ID
 * (1 row), Step 2 — the step the evaluator marked SUFFICIENT — returned the actual purchase
 * order (also 1 row). {@code ReasoningEngine.reason()}'s {@code queryData} selection picked
 * "the step with the most rows across all steps"; on a tie, {@code Stream.max()} keeps the
 * first-encountered element, so Step 1's product ID was shown as the answer instead of Step 2's
 * purchase order — even though Step 2 was the step that actually answered the question.
 *
 * <p>These tests exercise the real {@code queryData} selection logic in {@code reason()} with
 * hand-rolled fakes (no LLM, no DB) — same pattern as {@link FollowUpInvestigationContinuationTest}.
 */
class ReasoningEngineQueryDataSelectionTest {

    @Test
    void sufficientStepWinsOverEarlierStepWithEqualRowCount() {
        AtomicInteger plannerCalls = new AtomicInteger(0);

        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                int call = plannerCalls.incrementAndGet();
                if (call == 1) {
                    return new StepPlan("Identify product IDs for turtleneck products",
                            "SELECT id FROM retail_core.products WHERE name ILIKE '%turtleneck%'",
                            "conn-5780d333", null, "Resolve the product reference first");
                }
                if (call == 2) {
                    return new StepPlan("Retrieve open purchase orders for turtleneck products",
                            "SELECT po_number FROM retail_core.purchase_orders WHERE status IN "
                                    + "('draft','submitted','acknowledged','partially_received') "
                                    + "AND id IN (SELECT purchase_order_id FROM retail_core.purchase_order_lines "
                                    + "WHERE product_id = '50000000-0000-0000-0000-000000001101')",
                            "conn-5780d333", null, "Answer the user's question using the resolved product id");
                }
                return null;
            }
        };

        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override
            public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                if (evidence.stepCount() == 1) {
                    return new EvaluationResult("NEED_MORE_DATA", "Product id resolved; orders not yet retrieved");
                }
                return new EvaluationResult("SUFFICIENT", "Purchase order retrieved");
            }
        };

        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null) {
            @Override
            public Outcome execute(Request r) {
                if (r.sql().contains("FROM retail_core.products")) {
                    List<Map<String, Object>> rows = List.of(
                            Map.of("id", "50000000-0000-0000-0000-000000001101"));
                    return new Outcome(Status.EXECUTED, null, null, rows,
                            "[{\"id\":\"50000000-0000-0000-0000-000000001101\"}]", 10L, List.of(), null, null, null);
                }
                List<Map<String, Object>> rows = List.of(Map.of("po_number", "PO123"));
                return new Outcome(Status.EXECUTED, null, null, rows,
                        "[{\"po_number\":\"PO123\"}]", 10L, List.of(), null, null, null);
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

        String question = "show me all open orders for turtleneck products";
        ReasoningEngine.ReasoningResult result = engine.reason(
                question, question, "rsession-test", "schema context", "run-test", "user@test.com",
                false, null, null, false, "conv-test", null, null);

        assertEquals(2, plannerCalls.get());

        // The SUFFICIENT step (Step 2, the purchase order) must be shown — not Step 1's product id,
        // even though both steps returned exactly one row.
        assertEquals(1, result.queryData().size());
        assertEquals("PO123", result.queryData().get(0).get("po_number"),
                "queryData must reflect the SUFFICIENT step's rows, not an earlier tied-row-count step");
        assertNull(result.queryData().get(0).get("id"),
                "the Step 1 product-id row must not be shown as the final answer");
    }

    @Test
    void fallsBackToMostRowsWhenNoStepWasMarkedSufficient() {
        // The loop runs to MAX_STEPS without ever being marked SUFFICIENT (planner keeps
        // proposing new steps) — the prior "most rows of any step" heuristic must still apply,
        // so an inconclusive investigation still surfaces its best available data.
        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                if (evidence.stepCount() >= ReasoningEngine.MAX_STEPS) return null;
                return new StepPlan("Step " + (evidence.stepCount() + 1),
                        "SELECT id FROM retail_core.products LIMIT " + (evidence.stepCount() + 1),
                        "conn-5780d333", null, "Keep looking");
            }
        };

        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override
            public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                return new EvaluationResult("NEED_MORE_DATA", "Still looking");
            }
        };

        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null) {
            @Override
            public Outcome execute(Request r) {
                // Step 2 (0-indexed second call) returns the most rows of any step.
                List<Map<String, Object>> rows = r.sql().endsWith("LIMIT 3")
                        ? List.of(Map.of("id", "a"), Map.of("id", "b"), Map.of("id", "c"))
                        : List.of(Map.of("id", "x"));
                return new Outcome(Status.EXECUTED, null, null, rows, "[]", 10L, List.of(), null, null, null);
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

        String question = "keep looking";
        ReasoningEngine.ReasoningResult result = engine.reason(
                question, question, "rsession-test", "schema context", "run-test", "user@test.com",
                false, null, null, false, "conv-test", null, null);

        assertEquals(3, result.queryData().size(),
                "with no SUFFICIENT step, the existing most-rows fallback must still apply");
    }
}
