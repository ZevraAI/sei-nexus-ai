package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for {@link ReasoningEvaluator#evaluate}'s deterministic clamp: {@code decision}
 * can never surface as {@code SUFFICIENT} when the model's own {@code resultSetMatches} field is
 * explicitly {@code false} — added after live validation showed the model's holistic {@code
 * decision} label alone was not reliably correct even under explicit prose instruction (see
 * {@link ReasoningEvaluatorResultSetRealTenantValidation} for that live evidence). Hand-rolled
 * fake {@link AzureOpenAiClient}, no network — same convention as this package's other evaluator
 * tests.
 */
class ReasoningEvaluatorResultSetMatchesClampTest {

    static class ScriptedAiClient extends AzureOpenAiClient {
        String scriptedResponse;
        int callCount = 0;
        ScriptedAiClient(String scriptedResponse) {
            super(new ObjectMapper(), null);
            this.scriptedResponse = scriptedResponse;
        }
        @Override public String chat(List<ChatMessage> messages, String systemPrompt) {
            callCount++;
            return scriptedResponse;
        }
    }

    private EvidenceStore anyEvidence() {
        EvidenceStore evidence = new EvidenceStore();
        evidence.add(0, "seed", "SELECT 1", "conn-1", List.of(), null, null, null, 1L);
        return evidence;
    }

    @Test
    void sufficientIsOverriddenToNeedMoreDataWhenResultSetMatchesIsFalse() {
        ScriptedAiClient client = new ScriptedAiClient(
                "{\"resultSetMatches\":false,\"decision\":\"SUFFICIENT\",\"rationale\":\"can compute it from existing rows\"}");
        ReasoningEvaluator evaluator = new ReasoningEvaluator(client, new ObjectMapper());

        ReasoningEvaluator.EvaluationResult result = evaluator.evaluate("I want only submitted", anyEvidence());

        assertEquals("NEED_MORE_DATA", result.decision(),
                "the model's own resultSetMatches=false must force NEED_MORE_DATA regardless of its decision field");
        assertEquals(1, client.callCount, "exactly one evaluator call — no additional LLM call introduced");
    }

    @Test
    void sufficientIsPreservedWhenResultSetMatchesIsTrue() {
        ScriptedAiClient client = new ScriptedAiClient(
                "{\"resultSetMatches\":true,\"decision\":\"SUFFICIENT\",\"rationale\":\"a count over the exact existing rows\"}");
        ReasoningEvaluator evaluator = new ReasoningEvaluator(client, new ObjectMapper());

        ReasoningEvaluator.EvaluationResult result = evaluator.evaluate("How many are there?", anyEvidence());

        assertEquals("SUFFICIENT", result.decision(), "a genuine resultSetMatches=true must not be clamped");
    }

    @Test
    void sufficientIsPreservedWhenResultSetMatchesIsAbsent() {
        // Backward safety: an older/degraded model response without this field must behave
        // exactly as it did before this field existed — never silently forced to NEED_MORE_DATA.
        ScriptedAiClient client = new ScriptedAiClient(
                "{\"decision\":\"SUFFICIENT\",\"rationale\":\"no resultSetMatches field in this response\"}");
        ReasoningEvaluator evaluator = new ReasoningEvaluator(client, new ObjectMapper());

        ReasoningEvaluator.EvaluationResult result = evaluator.evaluate("q", anyEvidence());

        assertEquals("SUFFICIENT", result.decision(),
                "an absent resultSetMatches field must not trigger the clamp — treated as unknown, not false");
    }

    @Test
    void needMoreDataIsUnaffectedByResultSetMatches() {
        ScriptedAiClient client = new ScriptedAiClient(
                "{\"resultSetMatches\":false,\"decision\":\"NEED_MORE_DATA\",\"rationale\":\"needs a filter\"}");
        ReasoningEvaluator evaluator = new ReasoningEvaluator(client, new ObjectMapper());

        ReasoningEvaluator.EvaluationResult result = evaluator.evaluate("q", anyEvidence());

        assertEquals("NEED_MORE_DATA", result.decision());
    }

    @Test
    void deadEndIsUnaffectedByResultSetMatches() {
        ScriptedAiClient client = new ScriptedAiClient(
                "{\"resultSetMatches\":false,\"decision\":\"DEAD_END\",\"rationale\":\"not obtainable\"}");
        ReasoningEvaluator evaluator = new ReasoningEvaluator(client, new ObjectMapper());

        ReasoningEvaluator.EvaluationResult result = evaluator.evaluate("q", anyEvidence());

        assertEquals("DEAD_END", result.decision(), "the clamp only ever forces NEED_MORE_DATA out of SUFFICIENT, never touches DEAD_END");
    }
}
