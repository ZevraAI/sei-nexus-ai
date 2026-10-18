package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The zero-row clamp in {@link ReasoningEvaluator#evaluate}: the model reports {@code
 * zeroRowResultConfirmed} as its own narrow boolean and Java enforces it — the same ratified
 * pattern as {@code resultSetMatches}, adopted because a prose-only rule was live-observed to be
 * unreliable (an unverified zero-row result was called "sufficient"). Java reads only that
 * self-report, never the SQL, the question or the evidence text. Hand-rolled fake client.
 */
class ReasoningEvaluatorZeroRowClampTest {

    static class ScriptedAiClient extends AzureOpenAiClient {
        final String scriptedResponse;
        int callCount = 0;
        ScriptedAiClient(String scriptedResponse) {
            super(new ObjectMapper(), null);
            this.scriptedResponse = scriptedResponse;
        }
        @Override public String respondForEvaluator(List<ChatMessage> m, String systemPrompt,
                                                      String name, Map<String, Object> schema) {
            callCount++;
            return scriptedResponse;
        }
    }

    private static EvidenceStore zeroRowEvidence() {
        EvidenceStore e = new EvidenceStore();
        e.add(1, "d", "SELECT * FROM t WHERE x < 0.9", "conn-1", List.of(), null, null, null, 1L);
        return e;
    }

    private static ReasoningEvaluator.EvaluationResult evaluate(String json) {
        return new ReasoningEvaluator(new ScriptedAiClient(json), new ObjectMapper())
                .evaluate("q", zeroRowEvidence());
    }

    private static String json(String zeroRowConfirmed, String decision) {
        return "{\"resultSetMatches\":true,\"zeroRowResultConfirmed\":" + zeroRowConfirmed
                + ",\"decision\":\"" + decision + "\",\"rationale\":\"r\"}";
    }

    @Test
    void sufficientIsOverriddenWhenTheModelReportsTheZeroRowResultUnconfirmed() {
        assertEquals("NEED_MORE_DATA", evaluate(json("false", "SUFFICIENT")).decision());
    }

    @Test
    void deadEndIsOverriddenToo() {
        assertEquals("NEED_MORE_DATA", evaluate(json("false", "DEAD_END")).decision());
    }

    @Test
    void aConfirmedZeroRowResultMayConclude() {
        assertEquals("SUFFICIENT", evaluate(json("true", "SUFFICIENT")).decision());
        assertEquals("DEAD_END", evaluate(json("true", "DEAD_END")).decision());
    }

    @Test
    void nullNeverClampsBecauseRowsWereReturnedOrTheFieldIsAbsent() {
        assertEquals("SUFFICIENT", evaluate(json("null", "SUFFICIENT")).decision());
        assertEquals("SUFFICIENT", evaluate(
                "{\"resultSetMatches\":true,\"decision\":\"SUFFICIENT\",\"rationale\":\"r\"}").decision(),
                "a response that omits the field behaves exactly as before the field existed");
    }

    @Test
    void needMoreDataIsLeftAlone() {
        assertEquals("NEED_MORE_DATA", evaluate(json("false", "NEED_MORE_DATA")).decision());
    }

    @Test
    void exactlyOneEvaluatorCallIsMade() {
        ScriptedAiClient client = new ScriptedAiClient(json("false", "SUFFICIENT"));
        new ReasoningEvaluator(client, new ObjectMapper()).evaluate("q", zeroRowEvidence());
        assertEquals(1, client.callCount, "the clamp adds no LLM call");
    }
}
