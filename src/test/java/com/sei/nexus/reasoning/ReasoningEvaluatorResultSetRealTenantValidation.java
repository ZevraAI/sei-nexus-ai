package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIAGNOSTIC / LIVE VALIDATION ONLY — real OpenAI, no DB, no full Zevra server (this environment
 * has no running Zevra backend/UI process and no Supabase credentials to start one — see {@code
 * docs/investigations/} for that established limitation). Same {@code *RealTenantValidation}
 * naming convention as the rest of this codebase's real-tenant/live-model diagnostics — excluded
 * from Surefire's default {@code **&#47;*Test.java} inclusion.
 *
 * <p>Calls the REAL, unmodified {@link ReasoningEvaluator#evaluate} — the actual production
 * method carrying the fixed {@code SYSTEM_PROMPT} — against real OpenAI, with an {@link
 * EvidenceStore} built to look exactly like the reported defect scenario: a seeded, unfiltered
 * 12-row purchase-order result (mirroring the real conversation's Turn 1), evaluated against two
 * different Turn 2 questions. This is the closest live validation possible from this environment
 * without a running Chat backend: it proves the actual fixed component, talking to the actual
 * model, makes the correct decision for both the must-not-reuse and the may-reuse cases.
 */
class ReasoningEvaluatorResultSetRealTenantValidation {

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

    @SuppressWarnings("unchecked")
    private static EvidenceStore seededEvidence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> rows = mapper.readValue(twelvePurchaseOrdersJson(), List.class);
        EvidenceStore evidence = new EvidenceStore();
        evidence.add(0, "Result carried over from the previous turn in this conversation",
                "SELECT po_number, status FROM retail_core.purchase_orders", "conn-5780d333",
                rows, null, null, null, 120L);
        return evidence;
    }

    private static AzureOpenAiClient realClient() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        AzureOpenAiClient aiClient = new AzureOpenAiClient(new ObjectMapper(), null);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);
        return aiClient;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void filteredFollowUpIsNoLongerJudgedSufficientAgainstUnfilteredSeededEvidence() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        ReasoningEvaluator evaluator = new ReasoningEvaluator(realClient(), new ObjectMapper());
        EvidenceStore evidence = seededEvidence();

        ReasoningEvaluator.EvaluationResult result = evaluator.evaluate("I want only submitted", evidence);
        System.out.println("=== 'I want only submitted' against unfiltered 12-row seeded evidence ===");
        System.out.println("decision=" + result.decision() + " rationale=" + result.rationale());

        assertEquals("NEED_MORE_DATA", result.decision(),
                "the fixed prompt must recognize the seeded, unfiltered evidence is NOT the correct "
                        + "result set for a status-filtered request, even though it is on-topic and "
                        + "could be read to compute the right count");
    }

    @Test
    void countingFollowUpIsStillJudgedSufficientAgainstTheSameSeededEvidence() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        ReasoningEvaluator evaluator = new ReasoningEvaluator(realClient(), new ObjectMapper());
        EvidenceStore evidence = seededEvidence();

        ReasoningEvaluator.EvaluationResult result = evaluator.evaluate(
                "How many purchase orders are there?", evidence);
        System.out.println("=== 'How many purchase orders are there?' against the same seeded evidence ===");
        System.out.println("decision=" + result.decision() + " rationale=" + result.rationale());

        assertTrue(result.isSufficient(),
                "a genuinely answerable follow-up (a count over the exact rows already gathered) "
                        + "must still be able to reuse evidence — the fix must not regress this case");
    }

    @Test
    void differentSubsetFollowUpIsAlsoNotJudgedSufficient() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        ReasoningEvaluator evaluator = new ReasoningEvaluator(realClient(), new ObjectMapper());
        EvidenceStore evidence = seededEvidence();

        ReasoningEvaluator.EvaluationResult result = evaluator.evaluate("What about the closed ones?", evidence);
        System.out.println("=== 'What about the closed ones?' against the same seeded evidence ===");
        System.out.println("decision=" + result.decision() + " rationale=" + result.rationale());

        assertEquals("NEED_MORE_DATA", result.decision(),
                "a different subset (closed vs the seeded unfiltered set) must not be reused verbatim");
    }
}
