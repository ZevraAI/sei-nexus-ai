package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Semantic-Answerability rule — {@link ReasoningEvaluator#evaluate}'s handling of the three
 * conceptual cases the rule targets (see {@link ReasoningEvaluatorPromptTest} for the rule-text
 * pins). Same hand-rolled scripted-fake convention as {@link
 * ReasoningEvaluatorResultSetMatchesClampTest} — these tests prove JAVA PARSING/RELAY is correct
 * for each scripted model response; they do not, and cannot, prove the real model always makes
 * the semantically-correct call for a given evidence shape. That judgment belongs entirely to
 * the LLM (the rule text it's given); Java never inspects {@code evidence} or {@code question}
 * to decide sufficiency itself — it only relays whatever {@code decision} the model returns,
 * exactly as before this rule was added.
 */
class ReasoningEvaluatorSemanticAnswerabilityTest {

    static class ScriptedAiClient extends AzureOpenAiClient {
        String scriptedResponse;
        String lastPrompt;
        ScriptedAiClient(String scriptedResponse) {
            super(new ObjectMapper(), null);
            this.scriptedResponse = scriptedResponse;
        }
        @Override public String chat(List<ChatMessage> messages, String systemPrompt) {
            lastPrompt = messages.get(0).content();
            return scriptedResponse;
        }
    }

    // ── CASE A: evidence contains the requested entity's descriptive attribute — the model may
    //     judge this sufficient (Java relays it verbatim, never second-guesses it) ─────────────

    @Test
    void caseA_descriptiveEvidencePresent_modelsSufficientDecisionIsRelayedVerbatim() {
        ScriptedAiClient client = new ScriptedAiClient(
                "{\"resultSetMatches\":true,\"decision\":\"SUFFICIENT\","
                        + "\"rationale\":\"the evidence includes a human-readable descriptive value for the entity\"}");
        ReasoningEvaluator evaluator = new ReasoningEvaluator(client, new ObjectMapper());

        EvidenceStore evidence = new EvidenceStore();
        evidence.add(1, "step", "SELECT id, name FROM widgets", "conn-1",
                List.of(Map.of("id", "W-1", "name", "Blue Widget")), null, null, null, 5L);

        ReasoningEvaluator.EvaluationResult result = evaluator.evaluate("which widget is most popular", evidence);

        assertEquals("SUFFICIENT", result.decision(),
                "Java never overrides a model decision it has no rule to override — this is the "
                        + "model's own judgment, relayed as-is");
    }

    // ── CASE B: evidence contains only an opaque identifier for the requested entity — the
    //     model is instructed (in the prompt, not in Java) to treat this as insufficient ───────

    @Test
    void caseB_onlyOpaqueIdentifierPresent_modelsNeedMoreDataDecisionIsRelayedVerbatim() {
        ScriptedAiClient client = new ScriptedAiClient(
                "{\"resultSetMatches\":true,\"decision\":\"NEED_MORE_DATA\","
                        + "\"rationale\":\"only an opaque identifier was returned for the requested entity, "
                        + "no descriptive value yet\"}");
        ReasoningEvaluator evaluator = new ReasoningEvaluator(client, new ObjectMapper());

        EvidenceStore evidence = new EvidenceStore();
        evidence.add(1, "step", "SELECT entity_id, SUM(qty) FROM facts GROUP BY entity_id ORDER BY 2 DESC LIMIT 1",
                "conn-1", List.of(Map.of("entity_id", "E-9f21", "sum", 1500)), null, null, null, 5L);

        ReasoningEvaluator.EvaluationResult result = evaluator.evaluate("which entity is used the most", evidence);

        assertEquals("NEED_MORE_DATA", result.decision(),
                "Java relays the model's own insufficiency judgment — it never independently "
                        + "decides an identifier is or isn't 'enough'");
    }

    // ── CASE C: an identifier was learned via a JOIN/relationship reference, but that object's
    //     own descriptive metadata was never retrieved — still not sufficient ──────────────────

    @Test
    void caseC_identifierLearnedViaJoinGuidance_modelsNeedMoreDataDecisionIsRelayedVerbatim() {
        ScriptedAiClient client = new ScriptedAiClient(
                "{\"resultSetMatches\":true,\"decision\":\"NEED_MORE_DATA\","
                        + "\"rationale\":\"the related entity's identifier came from JOIN guidance only; "
                        + "its own descriptive metadata has not been retrieved\"}");
        ReasoningEvaluator evaluator = new ReasoningEvaluator(client, new ObjectMapper());

        EvidenceStore evidence = new EvidenceStore();
        evidence.add(1, "step", "SELECT related_id FROM facts LIMIT 1",
                "conn-1", List.of(Map.of("related_id", "R-42")), null, null, null, 5L);

        ReasoningEvaluator.EvaluationResult result = evaluator.evaluate("which related record is this", evidence);

        assertEquals("NEED_MORE_DATA", result.decision());
    }

    // ── Java never inspects the question or evidence itself to make this call — confirmed by
    //     the same scripted evidence/question producing whatever decision the model scripts,
    //     with no Java-side branching on evidence shape or question text anywhere in this path ──

    @Test
    void javaNeverInspectsQuestionOrEvidenceContent_onlyRelaysTheModelsOwnDecision() {
        // Identical evidence and question, but the SCRIPTED response differs — proves the
        // decision is driven entirely by what the model returns, not by any Java-side
        // inspection of the evidence/question (which are identical across both calls).
        EvidenceStore evidence = new EvidenceStore();
        evidence.add(1, "step", "SELECT entity_id FROM facts LIMIT 1",
                "conn-1", List.of(Map.of("entity_id", "E-1")), null, null, null, 5L);
        String question = "which entity is this";

        ScriptedAiClient sufficientClient = new ScriptedAiClient(
                "{\"resultSetMatches\":true,\"decision\":\"SUFFICIENT\",\"rationale\":\"model's own call\"}");
        ScriptedAiClient needMoreClient = new ScriptedAiClient(
                "{\"resultSetMatches\":true,\"decision\":\"NEED_MORE_DATA\",\"rationale\":\"model's own call\"}");

        String a = new ReasoningEvaluator(sufficientClient, new ObjectMapper()).evaluate(question, evidence).decision();
        String b = new ReasoningEvaluator(needMoreClient, new ObjectMapper()).evaluate(question, evidence).decision();

        assertEquals("SUFFICIENT", a);
        assertEquals("NEED_MORE_DATA", b);
        assertNotEquals(a, b, "same evidence/question, different scripted model decision — proves "
                + "Java performs no independent evaluation of its own");
    }
}
