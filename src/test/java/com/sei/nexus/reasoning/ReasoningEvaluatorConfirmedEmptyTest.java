package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A verification step that confirms the question's own zero-row result must not leave the
 * investigation "needing more data". The rule lives in the evaluator's prompt; the model reports
 * resultSetMatches, and the code clamps SUFFICIENT to NEED_MORE_DATA only when that is false.
 */
class ReasoningEvaluatorConfirmedEmptyTest {

    static class CapturingClient extends AzureOpenAiClient {
        String systemPrompt;
        final String reply;
        CapturingClient(String reply) { super(new ObjectMapper(), null); this.reply = reply; }
        @Override public String respondForEvaluator(List<ChatMessage> m, String sys, String n, Map<String, Object> s) {
            systemPrompt = sys;
            return reply;
        }
    }

    @Test
    void promptTreatsAConfirmedEmptyResultAsTheAnswer() {
        CapturingClient c = new CapturingClient(
                "{\"resultSetMatches\":true,\"zeroRowResultConfirmed\":null,\"decision\":\"SUFFICIENT\",\"rationale\":\"confirmed none\"}");
        new ReasoningEvaluator(c, new ObjectMapper()).evaluate("q", new EvidenceStore());
        assertTrue(c.systemPrompt.contains("that\n               confirmed empty result IS the answer")
                || c.systemPrompt.replaceAll("\\s+", " ").contains("confirmed empty result IS the answer"));
    }

    @Test
    void whenTheModelReportsAMatchTheConfirmedEmptyResultIsSufficient() {
        CapturingClient c = new CapturingClient(
                "{\"resultSetMatches\":true,\"zeroRowResultConfirmed\":null,\"decision\":\"SUFFICIENT\",\"rationale\":\"confirmed none\"}");
        ReasoningEvaluator.EvaluationResult r = new ReasoningEvaluator(c, new ObjectMapper()).evaluate("q", new EvidenceStore());
        assertEquals("SUFFICIENT", r.decision());
    }

    @Test
    void theClampStillTurnsAMismatchIntoNeedMoreData() {
        CapturingClient c = new CapturingClient(
                "{\"resultSetMatches\":false,\"zeroRowResultConfirmed\":null,\"decision\":\"SUFFICIENT\",\"rationale\":\"x\"}");
        ReasoningEvaluator.EvaluationResult r = new ReasoningEvaluator(c, new ObjectMapper()).evaluate("q", new EvidenceStore());
        assertEquals("NEED_MORE_DATA", r.decision());
    }
}
