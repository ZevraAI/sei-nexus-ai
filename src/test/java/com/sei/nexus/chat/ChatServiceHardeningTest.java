package com.sei.nexus.chat;

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
