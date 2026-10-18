package com.sei.nexus.chat;

import com.sei.nexus.reasoning.EvidenceStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "No query ran" must never be reported as "the query ran and found nothing". These tests pin the
 * facts the composer is handed; they do not decide what the model says — the answer's wording is
 * the model's.
 */
class ChatServiceNoQueryOutcomeTest {

    @Test
    void aNoQueryStepBecomesItsOwnExecResultCarryingTheReason() {
        EvidenceStore evidence = new EvidenceStore();
        evidence.addOutcome(1, "No query executed", "", "", "r",
                EvidenceStore.OUTCOME_NO_QUERY_EXECUTED, "No object was selected for this question.", 0L);

        List<Map<String, Object>> results = ChatService.evidenceToExecResults(evidence);

        assertEquals(1, results.size());
        assertEquals(Boolean.TRUE, results.get(0).get("noQuery"));
        assertEquals("No object was selected for this question.", results.get(0).get("reason"));
        assertFalse(results.get(0).containsKey("rows"));
    }

    @Test
    void aCleanZeroRowQueryIsNotReportedAsNoQuery() {
        EvidenceStore evidence = new EvidenceStore();
        evidence.add(1, "d", "SELECT 1", "conn-1", List.of(), "r", "DEAD_END", "nothing", 3L);

        assertTrue(ChatService.evidenceToExecResults(evidence).isEmpty(),
                "an executed query with zero rows stays the ordinary empty-result case");
    }

    @Test
    void noQueryPromptNeverClaimsTheQueryRanOrMatchedNothing() {
        String noQuery = ChatService.resultSystemPrompt(false, false, false, false, false, true, false);
        String zeroRow = ChatService.resultSystemPrompt(false, false, false, false, false, false, false);

        assertNotEquals(zeroRow, noQuery);
        assertTrue(zeroRow.contains("executed successfully"), "the zero-row prompt asserts the query ran");
        assertFalse(noQuery.contains("executed successfully"));
        assertTrue(noQuery.contains("No database query was run"));
        assertTrue(noQuery.contains("Do NOT say or imply that records were searched"));
    }

    @Test
    void realDataStillWinsOverANoQueryFlag() {
        assertEquals(ChatService.resultSystemPrompt(true, false, false, false, false, false, false),
                ChatService.resultSystemPrompt(true, false, false, false, false, true, false));
    }

    @Test
    void failureAndBlockedAndClarificationOutrankNoQuery() {
        assertEquals(ChatService.failedQuerySystemPrompt(),
                ChatService.resultSystemPrompt(false, true, false, false, false, true, false));
        assertEquals(ChatService.blockedQuerySystemPrompt(),
                ChatService.resultSystemPrompt(false, false, true, false, false, true, false));
        assertEquals(ChatService.clarificationSystemPrompt(),
                ChatService.resultSystemPrompt(false, false, false, true, false, true, false));
    }

    @Test
    void theOlderSignaturesAreByteIdentical() {
        assertEquals(ChatService.resultSystemPrompt(false, false, false, false, false, false),
                ChatService.resultSystemPrompt(false, false, false, false, false, false, false));
        assertEquals(ChatService.resultFallbackMessage(false, false, false, false, false),
                ChatService.resultFallbackMessage(false, false, false, false, false, false));
    }

    @Test
    void noQueryFallbackNeverSaysAnInvestigationCompletedWithNoData() {
        String fallback = ChatService.resultFallbackMessage(false, false, false, false, false, true);

        assertFalse(fallback.toLowerCase().contains("no data returned"));
        assertFalse(fallback.toLowerCase().contains("completed"));
    }

    // ── the planner-facing statement for an empty schema ─────────────────────────────────────────

    @Test
    void whenConceptSelectionSelectedNothingTheSchemaSectionDoesNotClaimNoSourcesExist() {
        String text = ChatService.emptyScopeGrounding(true, false, List.of("alpha", "beta"));

        assertFalse(text.contains("NO LIVE DATA SOURCES CONFIGURED"), "sources ARE connected — saying otherwise is false");
        assertTrue(text.contains("Data sources ARE connected"));
        assertTrue(text.contains("alpha, beta"), "the tenant's concepts are stated as facts");
        assertTrue(text.contains("does NOT mean the data does not exist"));
        assertTrue(text.contains("Do NOT generate SQL against a table that is not listed"));
    }

    @Test
    void whenNothingIsConnectedTheOriginalStatementIsStillTrueAndUnchanged() {
        String withMemory = ChatService.emptyScopeGrounding(false, true, List.of());
        String noMemory = ChatService.emptyScopeGrounding(false, false, List.of());

        assertTrue(withMemory.contains("NO LIVE DATA SOURCES CONFIGURED. Do NOT generate SQL or use QUERY_LIVE_DATA."));
        assertTrue(withMemory.contains("use ANSWER_FROM_MEMORY"));
        assertTrue(noMemory.contains("use KNOWLEDGE_GAP"));
    }

    @Test
    void anEmptyConceptMenuIsOmittedNotRenderedAsAnEmptyList() {
        String text = ChatService.emptyScopeGrounding(true, false, List.of());

        assertFalse(text.contains("Business concepts this tenant has"));
    }
}
