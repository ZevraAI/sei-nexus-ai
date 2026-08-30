package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.runtime.ExecutionReference;
import com.sei.nexus.runtime.GovernedSqlRuntime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the "I want only submitted" conversational result-set reuse defect —
 * same subject as {@link FollowUpInvestigationContinuationTest} ("purchase orders" both turns,
 * so the on-topic check alone does not catch it), but the follow-up requires a DIFFERENT result
 * set (a status filter) than what was already gathered. {@link ReasoningEngine#reason} must
 * still invoke {@link ReasoningPlanner} and execute a new, correctly filtered query rather than
 * reusing the prior turn's unfiltered rows — even though an LLM could technically compute the
 * right count/description by reading through the unfiltered evidence.
 *
 * <p>Does not exercise the real LLM evaluator (non-deterministic, network-dependent — the
 * strengthened {@code SYSTEM_PROMPT} text itself is reviewed by {@link
 * ReasoningEvaluatorPromptTest}). Scripts the evaluator the way a *correctly prompted* evaluator
 * must now behave for each scenario, and asserts the rest of the pipeline (seeding, Planner,
 * GovernedSqlRuntime, queryData selection) honors that judgment correctly — proving the fix is
 * fully load-bearing on the evaluator's decision, with zero additional LLM calls or changes to
 * Stage-1/Decision Router/Planner/SQL generation.
 */
class FollowUpFilterChangeReuseTest {

    private static ExecutionReference purchaseOrdersExecutionReference(String resultJson) {
        String sql = "SELECT po_number, status FROM retail_core.purchase_orders";
        return new ExecutionReference(
                "exec-q1-pos", null, "conv-test", "run-q1", "conn-5780d333",
                Instant.now(), Instant.now(), 120L, "EXECUTE_SYNC", 12,
                List.of("po_number", "status"), resultJson, sql,
                "contract-q1", "hash-q1", List.of("retail_core.purchase_orders"),
                Map.of(), Map.of(), List.of());
    }

    private static String twelvePurchaseOrdersJson() {
        StringBuilder sb = new StringBuilder("[");
        String[] statuses = {"received", "received", "received", "received", "received",
                "partially_received", "partially_received", "partially_received",
                "acknowledged", "acknowledged", "submitted", "closed"};
        for (int i = 0; i < statuses.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"po_number\":\"PO-").append(1000 + i).append("\",\"status\":\"")
              .append(statuses[i]).append("\"}");
        }
        return sb.append(']').toString();
    }

    // ── 1/2/3/4/5. "I want only submitted" must NOT reuse the old unfiltered result ──────────────

    @Test
    void filteredFollowUpInvokesPlannerAndReturnsOnlyMatchingRows() {
        ExecutionReference priorExecution = purchaseOrdersExecutionReference(twelvePurchaseOrdersJson());

        AtomicInteger plannerCalls = new AtomicInteger(0);
        AtomicInteger evaluatorCalls = new AtomicInteger(0);
        List<GovernedSqlRuntime.Request> executedRequests = new ArrayList<>();
        String filteredResultJson = "[{\"po_number\":\"PO-1010\",\"status\":\"submitted\"}]";

        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                plannerCalls.incrementAndGet();
                if (evidence.stepCount() == 1) {
                    return new StepPlan("Retrieve only submitted purchase orders",
                            "SELECT po_number, status FROM retail_core.purchase_orders WHERE status = 'submitted'",
                            "conn-5780d333", null, "Question asks for a subset filtered by status");
                }
                return null;
            }
        };

        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override
            public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                evaluatorCalls.incrementAndGet();
                if (evidence.stepCount() == 1) {
                    // Correct behavior post-fix: same subject (purchase orders), but the seeded
                    // evidence is NOT the correct result set for a status-filtered request.
                    return new EvaluationResult("NEED_MORE_DATA",
                            "Seeded evidence is unfiltered; the question requires a status='submitted' subset");
                }
                return new EvaluationResult("SUFFICIENT", "Filtered purchase order evidence now available");
            }
        };

        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null) {
            @Override
            public Outcome execute(Request r) {
                executedRequests.add(r);
                List<Map<String, Object>> rows = List.of(Map.of("po_number", "PO-1010", "status", "submitted"));
                return new Outcome(Status.EXECUTED, null, null, rows, filteredResultJson,
                        12L, List.of(), null, null, null);
            }
        };

        ReasoningRepository fakeRepository = new ReasoningRepository(null) {
            @Override public void saveSession(ReasoningSession s) { }
            @Override public void updateSessionStatus(String sessionKey, String status,
                    String conclusion, Double confidence, Instant concludedAt) { }
            @Override public void saveStep(ReasoningStep step) { }
        };

        ReasoningEngine engine = new ReasoningEngine(fakePlanner, fakeEvaluator,
                new ReasoningEventBus(new ObjectMapper()), fakeRepository, fakeRuntime, new ObjectMapper());

        String question = "I want only submitted";
        ReasoningEngine.ReasoningResult result = engine.reason(
                question, question, "rsession-test", "schema context", "run-q2", "user@test.com",
                false, null, null, false, "conv-test", "exec-q1-pos", priorExecution);

        // ── 3. Planner invoked for the filtered follow-up ────────────────────────────────
        assertEquals(1, plannerCalls.get(), "Planner must be invoked when the follow-up requires a different result set");

        // ── 4. Generated/executed query contains the appropriate filter ─────────────────
        assertEquals(1, executedRequests.size());
        assertTrue(executedRequests.get(0).sql().contains("status = 'submitted'"),
                "the executed query must apply the requested status filter");

        // ── 5. Final result contains only matching rows ──────────────────────────────────
        assertEquals(1, result.queryData().size(),
                "queryData (the UI result table) must contain only the submitted row(s), not the carried-over 12");
        assertEquals("submitted", result.queryData().get(0).get("status"));
        assertEquals(filteredResultJson, result.resultSnapshot());

        // ── 7. No additional LLM call: evaluator called exactly once per decision point ──
        assertEquals(2, evaluatorCalls.get(),
                "one evaluation of the seeded (rejected) evidence, one of the new (accepted) evidence — "
                        + "no extra calls introduced by the fix");
    }

    // ── 6. Genuinely answerable follow-ups still reuse existing evidence (no regression) ────────

    @Test
    void countingFollowUpStillReusesExistingEvidenceWithoutInvokingPlanner() {
        ExecutionReference priorExecution = purchaseOrdersExecutionReference(twelvePurchaseOrdersJson());

        AtomicInteger plannerCalls = new AtomicInteger(0);
        List<GovernedSqlRuntime.Request> executedRequests = new ArrayList<>();

        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                plannerCalls.incrementAndGet();
                return null; // must never be reached in this scenario
            }
        };

        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override
            public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                // "How many purchase orders are there?" is answerable from the existing rows,
                // unchanged — no different result set is implied.
                return new EvaluationResult("SUFFICIENT",
                        "The existing 12 rows are themselves the correct result set for a count over them");
            }
        };

        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null) {
            @Override
            public Outcome execute(Request r) {
                executedRequests.add(r);
                throw new AssertionError("GovernedSqlRuntime must not be invoked when prior evidence is reused");
            }
        };

        ReasoningRepository fakeRepository = new ReasoningRepository(null) {
            @Override public void saveSession(ReasoningSession s) { }
            @Override public void updateSessionStatus(String sessionKey, String status,
                    String conclusion, Double confidence, Instant concludedAt) { }
            @Override public void saveStep(ReasoningStep step) { }
        };

        ReasoningEngine engine = new ReasoningEngine(fakePlanner, fakeEvaluator,
                new ReasoningEventBus(new ObjectMapper()), fakeRepository, fakeRuntime, new ObjectMapper());

        String question = "How many purchase orders are there?";
        ReasoningEngine.ReasoningResult result = engine.reason(
                question, question, "rsession-test", "schema context", "run-q2", "user@test.com",
                false, null, null, false, "conv-test", "exec-q1-pos", priorExecution);

        assertEquals(0, plannerCalls.get(), "a genuinely answerable follow-up must still reuse evidence, not re-plan");
        assertEquals(0, executedRequests.size());
        assertEquals(12, result.queryData().size(), "the reused evidence is the full, correct result set for a count");
    }

    // ── Case 4 from the task: a different subset ("closed ones") must also not reuse verbatim ──

    @Test
    void differentSubsetFollowUpInvokesPlannerRatherThanReusingTheFullSet() {
        ExecutionReference priorExecution = purchaseOrdersExecutionReference(twelvePurchaseOrdersJson());

        AtomicInteger plannerCalls = new AtomicInteger(0);
        String closedResultJson = "[{\"po_number\":\"PO-1011\",\"status\":\"closed\"}]";

        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                plannerCalls.incrementAndGet();
                if (evidence.stepCount() == 1) {
                    return new StepPlan("Retrieve only closed purchase orders",
                            "SELECT po_number, status FROM retail_core.purchase_orders WHERE status = 'closed'",
                            "conn-5780d333", null, "Question asks for the closed subset");
                }
                return null;
            }
        };

        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override
            public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                if (evidence.stepCount() == 1) {
                    return new EvaluationResult("NEED_MORE_DATA",
                            "Seeded evidence is unfiltered; the question asks for the closed subset only");
                }
                return new EvaluationResult("SUFFICIENT", "Closed-only evidence now available");
            }
        };

        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null) {
            @Override
            public Outcome execute(Request r) {
                List<Map<String, Object>> rows = List.of(Map.of("po_number", "PO-1011", "status", "closed"));
                return new Outcome(Status.EXECUTED, null, null, rows, closedResultJson,
                        12L, List.of(), null, null, null);
            }
        };

        ReasoningRepository fakeRepository = new ReasoningRepository(null) {
            @Override public void saveSession(ReasoningSession s) { }
            @Override public void updateSessionStatus(String sessionKey, String status,
                    String conclusion, Double confidence, Instant concludedAt) { }
            @Override public void saveStep(ReasoningStep step) { }
        };

        ReasoningEngine engine = new ReasoningEngine(fakePlanner, fakeEvaluator,
                new ReasoningEventBus(new ObjectMapper()), fakeRepository, fakeRuntime, new ObjectMapper());

        String question = "What about the closed ones?";
        ReasoningEngine.ReasoningResult result = engine.reason(
                question, question, "rsession-test", "schema context", "run-q2", "user@test.com",
                false, null, null, false, "conv-test", "exec-q1-pos", priorExecution);

        assertEquals(1, plannerCalls.get());
        assertEquals(1, result.queryData().size(),
                "the displayed result set must actually be the closed subset, not the full 12-row carryover");
        assertEquals("closed", result.queryData().get(0).get("status"));
    }
}
