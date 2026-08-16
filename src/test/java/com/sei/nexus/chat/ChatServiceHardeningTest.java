package com.sei.nexus.chat;

import com.sei.nexus.reasoning.EvidenceStore;
import com.sei.nexus.runtime.ExecutionReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production hardening + Execution Continuity — regression tests for the isolated Chat seams:
 * <ul>
 *   <li>follow-up grounding on the previous {@link ExecutionReference} rather than answer prose
 *       ({@link ChatService#buildExecutionGrounding}),</li>
 *   <li>empty-result messaging ({@link ChatService#zeroRowSystemPrompt}),</li>
 *   <li>internal-error leakage ({@link ChatService#GENERIC_INVESTIGATION_ERROR}).</li>
 * </ul>
 * Pure static seams; no Spring context or database.
 */
class ChatServiceHardeningTest {

    private static ExecutionReference inventoryExecution() {
        return new ExecutionReference(
                "exec-1", null, "conv-1", "run-1", "conn-5780d333",
                Instant.EPOCH, Instant.EPOCH, 12L, "EXECUTE_SYNC",
                34, List.of("product_id", "location_id", "available_qty"),
                "[]", "SELECT product_id, ... FROM retail_core.inventory_balances",
                "ctr-1", "hash-1", List.of("retail_core.inventory_balances"),
                Map.of("Inventory Balances", "retail_core.inventory_balances"),
                Map.of("product id", "retail_core.inventory_balances.product_id"),
                List.of("conn-5780d333:retail_core.inventory_balances"));
    }

    // ── Follow-up grounding on the previous ExecutionReference (not prose) ───────

    @Test
    void groundingCarriesRetrievalTargetAndRowScopeFromThePreviousExecution() {
        String g = ChatService.buildExecutionGrounding(inventoryExecution());

        assertFalse(g.isBlank(), "a follow-up must receive the previous execution's facts");
        assertTrue(g.contains("retail_core.inventory_balances"),
                "the retrieval base is carried so a follow-up continues the same result set");
        assertTrue(g.contains("34"), "the row scope actually returned (34) is carried, not a prose subset");
        assertTrue(g.contains("product_id"), "result columns are available for the follow-up");
        assertTrue(g.toLowerCase().contains("keep this same retrieval base"),
                "the grounding instructs enrichment to preserve the base and row count");
    }

    @Test
    void groundingIsEmptyForTheFirstDataTurn() {
        assertEquals("", ChatService.buildExecutionGrounding(null),
                "no prior execution ⇒ no continuity grounding (single-turn unaffected)");
    }

    @Test
    void groundingUsesExecutionFactsNotAnswerProse() {
        // The reference carries NO answer text — grounding must still be complete from facts alone.
        String g = ChatService.buildExecutionGrounding(inventoryExecution());
        assertTrue(g.contains("Rows returned: 34"));
        assertFalse(g.toLowerCase().contains("zevra answered"),
                "continuity no longer depends on prior answer prose");
    }

    // ── Empty-result messaging ──────────────────────────────────────────────────

    @Test
    void emptyResultWithoutAttachmentIsNeutralNotAlarming() {
        String plain = ChatService.zeroRowSystemPrompt(false).toLowerCase();
        assertTrue(plain.contains("no matching records") || plain.contains("no records currently match"),
                "a plain empty result is framed as simply no matches");
        assertTrue(plain.contains("do not suggest"),
                "the plain prompt explicitly guards the model against blaming the table/metadata/data");
        // The "records do not exist" framing belongs only to the file-lookup case.
        assertFalse(plain.contains("do not exist"),
                "a valid empty result must not tell the user the records do not exist");
    }

    @Test
    void emptyResultWithAttachmentStillExplainsRecordsDoNotExist() {
        String attach = ChatService.zeroRowSystemPrompt(true).toLowerCase();
        assertTrue(attach.contains("do not exist"),
                "the file-lookup case legitimately reports the supplied records are absent");
        assertTrue(attach.contains("uploaded"), "the attachment variant references the uploaded file");
        assertNotEquals(ChatService.zeroRowSystemPrompt(false), ChatService.zeroRowSystemPrompt(true),
                "the two empty-result cases must use different guidance");
    }

    // ── Failed execution vs. zero rows vs. blocked (the "order_date" defect) ────
    //
    // ChatService.composeAnswer() used to pick its system prompt from `anyRows` alone, so a
    // genuine execution failure (zero rows, because the query never ran) was indistinguishable
    // from a query that ran cleanly and simply matched nothing — producing "The query executed
    // successfully and returned no matching records" for an actual SQL error. resultSystemPrompt
    // now takes the failure/blocked signal as an explicit input alongside anyRows.

    @Test
    void successfulQueryWithRowsUsesTheDataAnswerPrompt() {
        String p = ChatService.resultSystemPrompt(true, false, false, false);
        assertTrue(p.contains("VERDICT"), "rows present ⇒ the normal executive-summary prompt");
    }

    @Test
    void successfulQueryWithZeroRowsUsesTheZeroRowPrompt() {
        String p = ChatService.resultSystemPrompt(false, false, false, false);
        assertEquals(ChatService.zeroRowSystemPrompt(false), p);
        assertTrue(p.toLowerCase().contains("executed successfully"),
                "a genuinely clean, empty result may still say the query executed successfully");
    }

    @Test
    void failedExecutionNeverClaimsSuccessOrNoRecords() {
        // Both phrases legitimately appear once each, inside "Do NOT say ..." instructions —
        // that's the point of the prompt. Assert the prohibitions are explicit, not their
        // absence as a substring (which would false-positive on the prohibition text itself).
        String p = ChatService.resultSystemPrompt(false, true, false, false).toLowerCase();
        assertTrue(p.contains("do not say the query executed successfully"));
        assertTrue(p.contains("do not say no"));
        assertTrue(p.contains("could not be executed") || p.contains("failed"));
    }

    @Test
    void blockedQueryIsDistinctFromFailureAndZeroRows() {
        String p = ChatService.resultSystemPrompt(false, false, true, false).toLowerCase();
        assertTrue(p.contains("do not say the query executed successfully"));
        assertTrue(p.contains("do not say no"));
        assertTrue(p.contains("governance") || p.contains("policy"));
        assertNotEquals(ChatService.resultSystemPrompt(false, true, false, false),
                ChatService.resultSystemPrompt(false, false, true, false),
                "blocked and failed must be told apart, not collapsed into one message");
    }

    @Test
    void rowsTakePrecedenceOverAnErrorOrBlockElsewhereInTheInvestigation() {
        // An earlier step's real data still answers the question even if a later step in the
        // same multi-step investigation failed or was blocked.
        assertEquals(ChatService.resultSystemPrompt(true, false, false, false),
                ChatService.resultSystemPrompt(true, true, false, false));
        assertEquals(ChatService.resultSystemPrompt(true, false, false, false),
                ChatService.resultSystemPrompt(true, false, true, false));
    }

    @Test
    void fallbackMessagesMatchTheSamePrecedenceAndNeverClaimSuccessOnFailure() {
        assertTrue(ChatService.resultFallbackMessage(true, false, false).contains("table below"));
        assertFalse(ChatService.resultFallbackMessage(false, true, false).toLowerCase().contains("completed"));
        assertTrue(ChatService.resultFallbackMessage(false, true, false).contains("could not be executed"));
        assertTrue(ChatService.resultFallbackMessage(false, false, true).toLowerCase().contains("blocked"));
        assertEquals("Investigation completed. No data returned.",
                ChatService.resultFallbackMessage(false, false, false));
    }

    // ── evidenceToExecResults: rows / error / blocked map shapes ────────────────

    @Test
    void evidenceToExecResultsEmitsErrorForAFailedStep() {
        EvidenceStore evidence = new EvidenceStore();
        evidence.add(1, "step", "SELECT po.order_date FROM retail_core.purchase_orders po",
                "conn-1", List.of(), null, "ERROR",
                "ERROR: column po.order_date does not exist", 5L);

        List<Map<String, Object>> results = ChatService.evidenceToExecResults(evidence);

        assertEquals(1, results.size());
        assertTrue(results.get(0).containsKey("error"));
        assertFalse(results.get(0).containsKey("rows"));
        assertFalse(results.get(0).containsKey("blocked"));
    }

    @Test
    void evidenceToExecResultsEmitsBlockedForAGovernanceBlockedStep() {
        EvidenceStore evidence = new EvidenceStore();
        evidence.add(1, "step", "SELECT * FROM retail_core.purchase_orders",
                "conn-1", List.of(), null, "BLOCKED",
                "SELECT * is not allowed; specify column names explicitly", 5L);

        List<Map<String, Object>> results = ChatService.evidenceToExecResults(evidence);

        assertEquals(1, results.size());
        assertTrue(results.get(0).containsKey("blocked"));
        assertFalse(results.get(0).containsKey("error"));
        assertFalse(results.get(0).containsKey("rows"));
    }

    @Test
    void evidenceToExecResultsEmitsRowsForASuccessfulStep() {
        EvidenceStore evidence = new EvidenceStore();
        evidence.add(1, "step", "SELECT po_number FROM retail_core.purchase_orders",
                "conn-1", List.of(Map.of("po_number", "PO123")), null, "SUFFICIENT", "Answered", 5L);

        List<Map<String, Object>> results = ChatService.evidenceToExecResults(evidence);

        assertEquals(1, results.size());
        assertTrue(results.get(0).containsKey("rows"));
    }

    // ── Internal-error leakage ──────────────────────────────────────────────────

    @Test
    void genericErrorMessageLeaksNoInternalDetail() {
        String m = ChatService.GENERIC_INVESTIGATION_ERROR;
        assertFalse(m.isBlank());
        // No interpolation markers or internal vocabulary that would carry SQL/driver/table detail.
        for (String forbidden : List.of("select", "sql", "exception", "nexus_", "%s", "+ e", "getMessage")) {
            assertFalse(m.toLowerCase().contains(forbidden),
                    "user-facing error must not contain '" + forbidden + "'");
        }
    }
}
