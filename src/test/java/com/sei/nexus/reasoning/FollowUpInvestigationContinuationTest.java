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
 * Regression test for the Luxury Peptide / purchase-order defect, traced from the real
 * conversation {@code conv-slaydwsd} (tenant_maryland_corporations):
 *
 * <p>Q1 — "What is the current inventory status of our products?" — investigated
 * {@code products}/{@code inventory_balances} and returned inventory rows. Q2 — "when was our
 * last order for Luxury Peptide products" — was asked as a follow-up in the same conversation.
 * ReasoningEngine seeded Q2's EvidenceStore with Q1's ExecutionReference (inventory rows) and
 * the pre-check evaluator judged that evidence SUFFICIENT/DEAD_END for a purchase-order
 * question it has nothing to do with — Planner and GovernedSqlRuntime never ran, and the
 * composed answer reused Q1's "61 active" status count to fabricate an answer about orders.
 *
 * <p>This test does not exercise the real LLM evaluator (non-deterministic, network-dependent —
 * out of scope for a unit test per this repo's no-network-in-tests convention; the prompt
 * itself is reviewed separately). It scripts an evaluator that behaves the way a *correct*
 * evaluator must: NEED_MORE_DATA against off-topic seeded evidence, SUFFICIENT once
 * purchase-order evidence actually exists — and asserts the rest of the pipeline (seeding,
 * Planner, GovernedSqlRuntime, final evidence) behaves correctly given that judgment.
 */
class FollowUpInvestigationContinuationTest {

    @Test
    void followUpToADifferentSubjectInvokesPlannerAndReturnsRealEvidence() {
        // ── Q1's seeded evidence: real inventory data, nothing about orders ──────────────
        String inventorySql = "SELECT products.id, products.name, products.status, "
                + "inventory_balances.on_hand_qty, inventory_balances.available_qty, "
                + "inventory_balances.in_transit_qty FROM retail_core.products "
                + "JOIN retail_core.inventory_balances ON products.id = inventory_balances.product_id "
                + "LIMIT 100";
        String inventoryResultJson = "["
                + "{\"id\":\"p-047\",\"name\":\"Luxury Peptide Face Serum 30ml\",\"status\":\"active\","
                + "\"on_hand_qty\":45,\"available_qty\":45,\"in_transit_qty\":0},"
                + "{\"id\":\"p-1100\",\"name\":\"Women's Cashmere Blend Sweater\",\"status\":\"active\","
                + "\"on_hand_qty\":620,\"available_qty\":620,\"in_transit_qty\":0}]";

        ExecutionReference priorExecution = new ExecutionReference(
                "exec-q1-inventory", null, "conv-test", "run-q1", "conn-5780d333",
                Instant.now(), Instant.now(), 120L, "EXECUTE_SYNC", 2,
                List.of("id", "name", "status", "on_hand_qty", "available_qty", "in_transit_qty"),
                inventoryResultJson, inventorySql,
                "contract-q1", "hash-q1", List.of("retail_core.products", "retail_core.inventory_balances"),
                Map.of(), Map.of(), List.of());

        // ── Fakes: no LLM, no DB, no Mockito — subclass and override the seam methods ────
        AtomicInteger plannerCalls = new AtomicInteger(0);
        List<GovernedSqlRuntime.Request> executedRequests = new ArrayList<>();
        List<ReasoningStep> savedSteps = new ArrayList<>();

        String poResultJson = "[{\"po_number\":\"PO-10432\",\"order_date\":\"2026-07-15\"}]";

        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                plannerCalls.incrementAndGet();
                if (evidence.stepCount() == 1) {
                    // Correct behavior: plan a query against purchase orders, not inventory.
                    return new StepPlan(
                            "Find the most recent purchase order for Luxury Peptide products",
                            "SELECT po.po_number, po.order_date FROM retail_core.purchase_orders po "
                                    + "JOIN retail_core.purchase_order_lines pol ON pol.po_id = po.id "
                                    + "JOIN retail_core.products p ON p.id = pol.product_id "
                                    + "WHERE p.name ILIKE '%Luxury Peptide%' ORDER BY po.order_date DESC LIMIT 1",
                            "conn-5780d333", null, "Question asks about order history, not inventory levels");
                }
                return null; // nothing further to plan once purchase-order evidence exists
            }
        };

        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override
            public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                if (evidence.stepCount() == 1) {
                    // Only the seeded inventory step exists — a correct evaluator must not
                    // accept this for a purchase-order question.
                    return new EvaluationResult("NEED_MORE_DATA",
                            "Seeded evidence is about inventory levels, not order history");
                }
                return new EvaluationResult("SUFFICIENT", "Purchase order evidence now available");
            }
        };

        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null) {
            @Override
            public Outcome execute(Request r) {
                executedRequests.add(r);
                List<Map<String, Object>> rows = List.of(Map.of(
                        "po_number", "PO-10432", "order_date", "2026-07-15"));
                return new Outcome(Status.EXECUTED, null, null, rows, poResultJson,
                        15L, List.of(), null, null, null);
            }
        };

        ReasoningRepository fakeRepository = new ReasoningRepository(null) {
            @Override public void saveSession(ReasoningSession s) { /* no-op */ }
            @Override public void updateSessionStatus(String sessionKey, String status,
                    String conclusion, Double confidence, Instant concludedAt) { /* no-op */ }
            @Override public void saveStep(ReasoningStep step) { savedSteps.add(step); }
        };

        ReasoningEngine engine = new ReasoningEngine(fakePlanner, fakeEvaluator,
                new ReasoningEventBus(new ObjectMapper()), fakeRepository, fakeRuntime, new ObjectMapper());

        // ── Act: Q2, seeded with Q1's unrelated evidence ─────────────────────────────────
        String question = "when was our lkast order for Luxury Peptide products";
        ReasoningEngine.ReasoningResult result = engine.reason(
                question, question, "rsession-test", "schema context", "run-q2", "user@test.com",
                false, null, null, false, "conv-test", "exec-q1-inventory", priorExecution);

        // ── Assert: Planner ran ───────────────────────────────────────────────────────
        assertEquals(1, plannerCalls.get(),
                "Planner must be invoked for a follow-up whose subject differs from seeded evidence");

        // ── Assert: Governed runtime executed a purchase-order retrieval ────────────────
        assertEquals(1, executedRequests.size());
        assertTrue(executedRequests.get(0).sql().contains("purchase_orders"),
                "Governed runtime must execute a query against purchase order data, not inventory");

        // ── Assert: final evidence reflects real purchase-order data ────────────────────
        // resultSnapshot (what ChatService persists as this run's ExecutionReference-equivalent
        // snapshot, and what a further follow-up would itself seed from) is the purchase-order
        // result, not the carried-over inventory rows.
        assertEquals(poResultJson, result.resultSnapshot());

        // The full evidence store — what ChatService.composeAnswer actually reads via
        // evidenceToExecResults — contains the purchase-order step with real data.
        EvidenceStore.StepEvidence purchaseOrderStep = result.evidence().getSteps().stream()
                .filter(s -> s.sql() != null && s.sql().contains("purchase_orders"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No purchase-order step found in evidence"));
        assertEquals(1, purchaseOrderStep.rows().size());
        assertEquals("PO-10432", purchaseOrderStep.rows().get(0).get("po_number"));

        // queryData (the UI's result table) must reflect the SUFFICIENT purchase-order step,
        // not the seeded 2-row inventory evidence it ties/loses to on raw row count alone.
        assertEquals(1, result.queryData().size());
        assertEquals("PO-10432", result.queryData().get(0).get("po_number"));

        // ── Assert: observability — the pre-check decision is now persisted, distinguishing
        //    NEED_MORE_DATA from SUFFICIENT/DEAD_END after the fact (the Luxury Peptide defect
        //    was invisible in nexus_reasoning_step precisely because this wasn't happening) ──
        ReasoningStep seedStep = savedSteps.stream().filter(s -> s.stepNo() == 0).findFirst()
                .orElseThrow(() -> new AssertionError("Pre-check decision was not persisted"));
        assertEquals("NEED_MORE_DATA", seedStep.evaluatorDecision());
    }
}
