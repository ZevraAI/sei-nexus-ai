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

        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
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
                new ReasoningEventBus(new ObjectMapper()), fakeRepository, fakeRuntime, new ObjectMapper(),
                new ColumnMetadataRequestHandler(new com.sei.nexus.agentbrain.PromptContextBuilder(),
                        new com.sei.nexus.agentbrain.PromptAssembler(),
                        new com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler(null)));

        String question = "show me all open orders for turtleneck products";
        ReasoningEngine.ReasoningResult result = engine.reason(
                question, question, "rsession-test", "schema context", "run-test", "user@test.com",
                false, null, null, false, "conv-test", null, null, null);

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

        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
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
                new ReasoningEventBus(new ObjectMapper()), fakeRepository, fakeRuntime, new ObjectMapper(),
                new ColumnMetadataRequestHandler(new com.sei.nexus.agentbrain.PromptContextBuilder(),
                        new com.sei.nexus.agentbrain.PromptAssembler(),
                        new com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler(null)));

        String question = "keep looking";
        ReasoningEngine.ReasoningResult result = engine.reason(
                question, question, "rsession-test", "schema context", "run-test", "user@test.com",
                false, null, null, false, "conv-test", null, null, null);

        assertEquals(3, result.queryData().size(),
                "with no SUFFICIENT step, the existing most-rows fallback must still apply");
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // InvestigationDataset — information-preservation fix. `queryData` above stays exactly as it
    // was (single "primary visualisation" selection, unchanged); these tests cover the SEPARATE,
    // additive `investigationDatasets` field: every row-bearing step, preserved independently,
    // regardless of evaluatorDecision. Reproduces the exact reported failing case:
    //   Step 2 -> most-ordered item (1 row), Step 3 -> 5 open purchase orders, Step 4 -> metadata
    //   (no business rows), Step 5 -> product details (1 row) -- all three business result sets
    //   must remain available, not just the last SUFFICIENT step (Step 5).
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /** Builds a 5-step engine matching the concrete failing case from the architecture review:
     *  step 2 (1 row, NEED_MORE_DATA), step 3 (5 rows, NEED_MORE_DATA), step 4 (metadata request,
     *  no rows), step 5 (1 row, SUFFICIENT). */
    private ReasoningEngine.ReasoningResult runFiveStepInvestigation() {
        AtomicInteger calls = new AtomicInteger(0);

        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                int call = calls.incrementAndGet();
                return switch (call) {
                    case 1 -> new StepPlan("Find the most ordered item",
                            "SELECT product_id, qty FROM retail_core.order_lines LIMIT 1",
                            "conn-1", null, "Identify the most-ordered item first");
                    case 2 -> new StepPlan("List all open purchase orders",
                            "SELECT po_number FROM retail_core.purchase_orders WHERE status = 'open'",
                            "conn-1", null, "Retrieve open purchase orders");
                    case 3 -> StepPlan.metadataRequest("Retrieve product metadata",
                            "Need product columns", "Products", "columns");
                    case 4 -> new StepPlan("Retrieve product name and SKU",
                            "SELECT name, sku FROM retail_core.products WHERE id = 'p1'",
                            "conn-1", null, "Answer with the product's name and SKU");
                    default -> null;
                };
            }
        };

        // evaluate() is called once per EXECUTED query step only — never for the metadata step
        // (see ReasoningEngine#reason) — so a plain call counter (not evidence.stepCount(), which
        // would also count the metadata step once it's added) correctly marks only the 3rd
        // executed step (product details) SUFFICIENT.
        AtomicInteger evalCalls = new AtomicInteger(0);
        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override
            public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                return evalCalls.incrementAndGet() >= 3
                        ? new EvaluationResult("SUFFICIENT", "Product identified")
                        : new EvaluationResult("NEED_MORE_DATA", "Keep investigating");
            }
        };

        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override
            public Outcome execute(Request r) {
                if (r.sql().contains("order_lines")) {
                    List<Map<String, Object>> rows = List.of(Map.of("product_id", "p1", "qty", 42));
                    return new Outcome(Status.EXECUTED, null, null, rows, "[]", 5L, List.of(), null, null, null);
                }
                if (r.sql().contains("purchase_orders")) {
                    List<Map<String, Object>> rows = List.of(
                            Map.of("po_number", "PO1"), Map.of("po_number", "PO2"),
                            Map.of("po_number", "PO3"), Map.of("po_number", "PO4"),
                            Map.of("po_number", "PO5"));
                    return new Outcome(Status.EXECUTED, null, null, rows, "[]", 5L, List.of(), null, null, null);
                }
                List<Map<String, Object>> rows = List.of(Map.of("name", "Turtleneck", "sku", "SKU-1"));
                return new Outcome(Status.EXECUTED, null, null, rows, "[]", 5L, List.of(), null, null, null);
            }
        };

        ReasoningRepository fakeRepository = new ReasoningRepository(null) {
            @Override public void saveSession(ReasoningSession s) { /* no-op */ }
            @Override public void updateSessionStatus(String sessionKey, String status,
                    String conclusion, Double confidence, Instant concludedAt) { /* no-op */ }
            @Override public void saveStep(ReasoningStep step) { /* no-op */ }
        };

        // Metadata request resolves against nothing (resolvedObjects == null) — irrelevant here;
        // the metadata step's own outcome (METADATA_UNAVAILABLE, zero rows) is what matters: it
        // must simply be absent from investigationDatasets, not corrupt the other steps.
        ReasoningEngine engine = new ReasoningEngine(fakePlanner, fakeEvaluator,
                new ReasoningEventBus(new ObjectMapper()), fakeRepository, fakeRuntime, new ObjectMapper(),
                new ColumnMetadataRequestHandler(new com.sei.nexus.agentbrain.PromptContextBuilder(),
                        new com.sei.nexus.agentbrain.PromptAssembler(),
                        new com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler(null)));

        String question = "Which item am I ordering the most, and what are my open purchase orders?";
        return engine.reason(question, question, "rsession-test", "schema context", "run-test",
                "user@test.com", false, null, null, false, "conv-test", null, null, null);
    }

    @Test
    void allThreeBusinessDatasetsArePreservedNotJustTheLastSufficientStep() {
        ReasoningEngine.ReasoningResult result = runFiveStepInvestigation();

        // The legacy single-dataset field is untouched — still only the last SUFFICIENT step.
        assertEquals(1, result.queryData().size());
        assertEquals("Turtleneck", result.queryData().get(0).get("name"));

        // The new field preserves every row-bearing step — all three business datasets.
        List<Integer> rowCounts = result.investigationDatasets().stream()
                .map(d -> d.rows().size()).toList();
        assertEquals(List.of(1, 5, 1), rowCounts,
                "step 1 (most-ordered item), step 2 (5 open POs), step 4 (product) must all be "
                        + "present — the metadata step (step 3, zero rows) is correctly absent");
    }

    @Test
    void rowBearingStepIsPreservedRegardlessOfEvaluatorDecision() {
        ReasoningEngine.ReasoningResult result = runFiveStepInvestigation();

        // Step 2 (5 PO rows) was marked NEED_MORE_DATA, not SUFFICIENT — it must still appear.
        boolean fiveRowStepPresent = result.investigationDatasets().stream()
                .anyMatch(d -> d.rows().size() == 5);
        assertTrue(fiveRowStepPresent,
                "a step marked NEED_MORE_DATA is not filtered out of investigationDatasets — "
                        + "only rows() emptiness matters, never evaluatorDecision");
    }

    @Test
    void stepDescriptionsAndStepNumbersArePreservedVerbatim() {
        ReasoningEngine.ReasoningResult result = runFiveStepInvestigation();

        var poDataset = result.investigationDatasets().stream()
                .filter(d -> d.rows().size() == 5).findFirst().orElseThrow();
        assertEquals(2, poDataset.stepNo(), "step number must match EvidenceStore's own numbering");
        assertEquals("List all open purchase orders", poDataset.description(),
                "description must be copied verbatim from the planner's own step description, "
                        + "never re-derived or re-titled from row content");
    }

    @Test
    void rowsFromDifferentStepsRemainSeparateAndAreNeverMerged() {
        ReasoningEngine.ReasoningResult result = runFiveStepInvestigation();

        assertEquals(3, result.investigationDatasets().size(),
                "three independent datasets, never combined into one flat list");
        for (var ds : result.investigationDatasets()) {
            // Each dataset's rows share the same, single schema of that one step only — proof
            // no cross-step merging occurred (a merge would mix product/PO/item-level columns
            // into rows that don't have them).
            long distinctKeySets = ds.rows().stream().map(Map::keySet).distinct().count();
            assertEquals(1, distinctKeySets,
                    "a single step's own rows share one schema — never blended with another step's");
        }
    }
}
