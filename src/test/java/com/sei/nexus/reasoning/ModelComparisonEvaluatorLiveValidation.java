package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.usage.UsageService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PHASE 2 — MODEL EVALUATION (gpt-4o vs gpt-4.1) for {@link ReasoningEvaluator}. DIAGNOSTIC ONLY,
 * real OpenAI, no DB — same three scenarios as {@link ReasoningEvaluatorResultSetRealTenantValidation}
 * (the actual reported result-set-reuse defect and its fix), run against BOTH models with
 * everything else (system prompt, evidence, question) held identical. Covers Step 5/6's
 * resultSetMatches / clamp / semantic-answerability contract directly, using the real, unmodified
 * {@link ReasoningEvaluator#evaluate}.
 */
class ModelComparisonEvaluatorLiveValidation {

    private static final List<String> MODELS = List.of("gpt-4o", "gpt-4.1");

    static class RecordingUsageService extends UsageService {
        record Call(String model, int promptTokens, int completionTokens, int cachedTokens, String callType) {}
        final List<Call> calls = new ArrayList<>();
        RecordingUsageService() { super(null); }
        @Override
        public void record(String model, int promptTokens, int completionTokens, int cachedTokens, String callType) {
            calls.add(new Call(model, promptTokens, completionTokens, cachedTokens, callType));
        }
    }

    private record Scenario(String label, String question, String expectedDecisionContains) {}

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
    private static EvidenceStore seededEvidence(ObjectMapper mapper) throws Exception {
        List<Map<String, Object>> rows = mapper.readValue(twelvePurchaseOrdersJson(), List.class);
        EvidenceStore evidence = new EvidenceStore();
        evidence.add(0, "Result carried over from the previous turn in this conversation",
                "SELECT po_number, status FROM retail_core.purchase_orders", "conn-5780d333",
                rows, null, null, null, 120L);
        return evidence;
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("filtered follow-up (must NOT reuse — expect NEED_MORE_DATA)",
                    "I want only submitted", "NEED_MORE_DATA"),
            new Scenario("counting follow-up (may reuse — expect SUFFICIENT)",
                    "How many purchase orders are there?", "SUFFICIENT"),
            new Scenario("different subset (must NOT reuse — expect NEED_MORE_DATA)",
                    "What about the closed ones?", "NEED_MORE_DATA")
    );

    @Test
    void compareModelsAcrossResultSetReuseScenarios() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        ObjectMapper mapper = new ObjectMapper();

        System.out.println("\n########## PHASE 2 MODEL COMPARISON — Evaluator ##########");

        for (String model : MODELS) {
            System.out.println("\n=================== MODEL: " + model + " ===================");
            RecordingUsageService usage = new RecordingUsageService();
            AzureOpenAiClient aiClient = new AzureOpenAiClient(mapper, usage);
            setField(aiClient, "apiKey", apiKey);
            setField(aiClient, "chatModel", model);
            setField(aiClient, "maxConcurrentCalls", 6);
            Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
            initThrottle.setAccessible(true);
            initThrottle.invoke(aiClient);

            ReasoningEvaluator evaluator = new ReasoningEvaluator(aiClient, mapper);

            for (Scenario s : SCENARIOS) {
                long t0 = System.nanoTime();
                ReasoningEvaluator.EvaluationResult result = evaluator.evaluate(s.question(), seededEvidence(mapper));
                long latencyMs = (System.nanoTime() - t0) / 1_000_000;

                boolean matches = s.expectedDecisionContains().equals("SUFFICIENT")
                        ? result.isSufficient()
                        : s.expectedDecisionContains().equals(result.decision());

                System.out.println("[" + s.label() + "] decision=" + result.decision()
                        + " rationale=" + result.rationale() + " latencyMs=" + latencyMs
                        + " CONTRACT_MATCH=" + matches);
            }

            System.out.println("\n--- USAGE (" + model + ") ---");
            for (var call : usage.calls) {
                System.out.println("  callType=" + call.callType() + " model=" + call.model()
                        + " promptTokens=" + call.promptTokens() + " cachedTokens=" + call.cachedTokens()
                        + " completionTokens=" + call.completionTokens());
            }
        }
    }

    /** PHASE 3 — POST-MIGRATION REAL-TENANT VALIDATION against the real deployed configuration
     *  (Evaluator uses {@code evaluatorModel}=gpt-4.1), run once (not A/B). */
    @Test
    void validateDeployedConfigurationAcrossResultSetReuseScenarios() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        ObjectMapper mapper = new ObjectMapper();

        System.out.println("\n########## PHASE 3 — POST-MIGRATION EVALUATOR VALIDATION (real deployed config) ##########");

        RecordingUsageService usage = new RecordingUsageService();
        AzureOpenAiClient aiClient = new AzureOpenAiClient(mapper, usage);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "evaluatorModel", "gpt-4.1");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        ReasoningEvaluator evaluator = new ReasoningEvaluator(aiClient, mapper);

        for (Scenario s : SCENARIOS) {
            long t0 = System.nanoTime();
            ReasoningEvaluator.EvaluationResult result = evaluator.evaluate(s.question(), seededEvidence(mapper));
            long latencyMs = (System.nanoTime() - t0) / 1_000_000;

            boolean matches = s.expectedDecisionContains().equals("SUFFICIENT")
                    ? result.isSufficient()
                    : s.expectedDecisionContains().equals(result.decision());

            System.out.println("[" + s.label() + "] decision=" + result.decision()
                    + " rationale=" + result.rationale() + " latencyMs=" + latencyMs
                    + " CONTRACT_MATCH=" + matches);
            assertTrue(matches, "contract must hold under the real deployed configuration: " + s.label());
        }

        System.out.println("\n--- USAGE (deployed config) ---");
        for (var call : usage.calls) {
            System.out.println("  callType=" + call.callType() + " model=" + call.model()
                    + " promptTokens=" + call.promptTokens() + " cachedTokens=" + call.cachedTokens()
                    + " completionTokens=" + call.completionTokens());
            assertEquals("gpt-4.1", call.model(), "every Evaluator call in the deployed configuration must use gpt-4.1");
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
