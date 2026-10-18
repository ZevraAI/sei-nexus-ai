package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Zero rows is not proof of absence": a filter literal that does not match how data is stored
 * (scale, punctuation, case, abbreviation) returns nothing, and concluding "none exist" from that
 * is a confident wrong answer. The rule lives in the planner's and evaluator's system prompts so
 * the MODEL decides what verification to run; Java adds no heuristic. These tests pin that the
 * rule is present in both prompts and stays domain-neutral (Zevra serves every industry).
 */
class ZeroRowVerificationPromptTest {

    /** Words that would make a prompt rule tenant-, industry- or question-specific. */
    private static final List<String> DOMAIN_WORDS = List.of(
            "supplier", "purchase order", "store", "retail", "product", "warehouse", "inventory",
            "invoice", "patient", "shipment", "continental", "cost price", "on_time");

    static class CapturingClient extends AzureOpenAiClient {
        String plannerSystemPrompt;
        String evaluatorSystemPrompt;
        CapturingClient() { super(new ObjectMapper(), null); }
        @Override public String respondForPlanner(List<ChatMessage> m, String systemPrompt, String n, Map<String, Object> s) {
            plannerSystemPrompt = systemPrompt;
            return "{\"done\":true}";
        }
        @Override public String respondForEvaluator(List<ChatMessage> m, String systemPrompt, String n, Map<String, Object> s) {
            evaluatorSystemPrompt = systemPrompt;
            return "{\"resultSetMatches\":true,\"decision\":\"SUFFICIENT\",\"rationale\":\"ok\"}";
        }
    }

    private static String section(String prompt, String startMarker, String endMarker) {
        int s = prompt.indexOf(startMarker);
        assertTrue(s >= 0, "marker missing: " + startMarker);
        int e = prompt.indexOf(endMarker, s);
        assertTrue(e > s, "end marker missing: " + endMarker);
        return prompt.substring(s, e);
    }

    @Test
    void thePlannerIsToldToVerifyAZeroRowResultBeforeConcludingNothingExists() {
        CapturingClient client = new CapturingClient();
        new ReasoningPlanner(client, new ObjectMapper()).nextStep("q", "schema", new EvidenceStore());

        String rule = section(client.plannerSystemPrompt,
                "VERIFY A ZERO-ROW RESULT BEFORE CONCLUDING", "If NO evidence exists yet");
        assertTrue(rule.contains("units or scale"));
        assertTrue(rule.contains("punctuation"));
        assertTrue(rule.contains("do NOT return"));
        assertTrue(rule.contains("decide for yourself which check fits"),
                "which verification to run is the model's decision, not Java's");
    }

    @Test
    void theEvaluatorIsToldAZeroRowStepHasNotConfirmedAnythingWhenItFilteredOnAValue() {
        CapturingClient client = new CapturingClient();
        EvidenceStore evidence = new EvidenceStore();
        evidence.add(1, "d", "SELECT 1", "conn-1", List.of(), "r", null, null, 1L);
        new ReasoningEvaluator(client, new ObjectMapper()).evaluate("q", evidence);

        String rule = section(client.evaluatorSystemPrompt,
                "A ZERO-ROW RESULT IS NOT, BY ITSELF, PROOF", "Be decisive.");
        assertTrue(rule.contains("choose NEED_MORE_DATA"));
        assertTrue(rule.contains("units"));
        assertTrue(rule.contains("no value-dependent filter"),
                "a zero-row result with no value-dependent filter may still conclude");
        assertTrue(rule.contains("a verification step has already been run"),
                "the rule is bounded: after one verification the model may conclude");
    }

    @Test
    void theEvaluatorIsToldADiagnosticListingIsNeverTheAnswer() {
        CapturingClient client = new CapturingClient();
        EvidenceStore evidence = new EvidenceStore();
        evidence.add(1, "d", "SELECT 1", "conn-1", List.of(Map.of("c", 1)), "r", null, null, 1L);
        new ReasoningEvaluator(client, new ObjectMapper()).evaluate("q", evidence);

        String p = client.evaluatorSystemPrompt;
        assertTrue(p.contains("Rows gathered only to CHECK how the data is"));
        assertTrue(p.contains("never the answer to the user's question"));
        assertTrue(p.contains("unless the question itself asked for exactly that listing"),
                "a question that genuinely asks for a listing is not penalised");
    }

    @Test
    void bothRulesAreDomainNeutral() {
        CapturingClient client = new CapturingClient();
        new ReasoningPlanner(client, new ObjectMapper()).nextStep("q", "schema", new EvidenceStore());
        EvidenceStore evidence = new EvidenceStore();
        evidence.add(1, "d", "SELECT 1", "conn-1", List.of(), "r", null, null, 1L);
        new ReasoningEvaluator(client, new ObjectMapper()).evaluate("q", evidence);

        String planner = section(client.plannerSystemPrompt,
                "VERIFY A ZERO-ROW RESULT BEFORE CONCLUDING", "If NO evidence exists yet").toLowerCase(Locale.ROOT);
        String evaluator = section(client.evaluatorSystemPrompt,
                "A ZERO-ROW RESULT IS NOT, BY ITSELF, PROOF", "Be decisive.").toLowerCase(Locale.ROOT);
        for (String w : DOMAIN_WORDS) {
            // Whole-word match: "stored" in "how the data is stored" is not the domain noun "store".
            java.util.regex.Pattern word = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(w) + "\\b");
            assertFalse(word.matcher(planner).find(), "planner rule must not mention '" + w + "'");
            assertFalse(word.matcher(evaluator).find(), "evaluator rule must not mention '" + w + "'");
        }
    }
}
