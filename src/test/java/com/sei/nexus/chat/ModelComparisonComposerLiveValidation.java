package com.sei.nexus.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.reasoning.InvestigationDataset;
import com.sei.nexus.response.ExecutionOutcomeInterpreter;
import com.sei.nexus.response.NaturalLanguageComposer;
import com.sei.nexus.response.StructuredAnswer;
import com.sei.nexus.usage.UsageService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PHASE 2 — MODEL EVALUATION (gpt-4o vs gpt-4.1) for the Composer (STRICT_JSON data-answer path).
 * DIAGNOSTIC ONLY, real OpenAI, no DB. Uses the REAL, unmodified {@code
 * ChatService#DATA_ANSWER_JSON_SYSTEM_PROMPT} (read via reflection — it's {@code private static
 * final}, unlike {@link ChatService#dataAnswerJsonSchema()}/{@link
 * ChatService#parseStructuredAnswer} which are package-private and called directly) and the real
 * {@code buildInvestigationDatasetsBlock} rendering, against a realistic dataset shaped like the
 * one already used in {@code DataAnswerContractRefinementTest} (5 open purchase orders, Marcus
 * Webb 3 of 5) — grounding this evaluation in an existing, already-vetted fixture rather than an
 * invented one. Covers historical failure mode #7 (Investigation Response dataset grounding).
 */
class ModelComparisonComposerLiveValidation {

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

    private static String realDataAnswerSystemPrompt() throws Exception {
        Field f = ChatService.class.getDeclaredField("DATA_ANSWER_JSON_SYSTEM_PROMPT");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** 5 open purchase orders, 3 of which belong to Marcus Webb — same shape already vetted by
     *  DataAnswerContractRefinementTest's "genuine additional finding" scenario. */
    @SuppressWarnings("unchecked")
    private static InvestigationDataset openPurchaseOrdersDataset() {
        return new InvestigationDataset(1, "Open purchase orders", List.of(
                row("po_number", "PO-1001", "buyer", "Marcus Webb", "status", "open"),
                row("po_number", "PO-1002", "buyer", "Marcus Webb", "status", "open"),
                row("po_number", "PO-1003", "buyer", "Marcus Webb", "status", "open"),
                row("po_number", "PO-1004", "buyer", "Priya Nair", "status", "open"),
                row("po_number", "PO-1005", "buyer", "Diane Osei", "status", "open")
        ), null, null, List.of());
    }

    @Test
    void compareModelsOnOpenPurchaseOrdersDatasetGrounding() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        String systemPrompt = realDataAnswerSystemPrompt();
        ExecutionOutcomeInterpreter interpreter = new ExecutionOutcomeInterpreter();
        String datasetsBlock = ChatService.buildInvestigationDatasetsBlock(
                List.of(openPurchaseOrdersDataset()), interpreter);
        String question = "Show me all open purchase orders";
        String userPrompt = "Question: " + question + "\n\nQuery results:\n" + datasetsBlock;

        System.out.println("\n########## PHASE 2 MODEL COMPARISON — Composer (STRICT_JSON) ##########");

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

            NaturalLanguageComposer composer = new NaturalLanguageComposer(aiClient);
            long t0 = System.nanoTime();
            String raw = composer.compose(NaturalLanguageComposer.CompositionRequest.strictJson(
                    userPrompt, systemPrompt, "data_answer", ChatService.dataAnswerJsonSchema(),
                    (String) null));
            long latencyMs = (System.nanoTime() - t0) / 1_000_000;

            System.out.println("RAW: " + raw);
            StructuredAnswer answer = ChatService.parseStructuredAnswer(raw, mapper, "PARSE_FAILED_FALLBACK");
            System.out.println("answer=" + answer.answer());
            System.out.println("sections=" + answer.sections());
            System.out.println("metrics=" + answer.metrics());
            System.out.println("latencyMs=" + latencyMs);

            boolean parsedOk = !"PARSE_FAILED_FALLBACK".equals(answer.answer());
            boolean mentionsFive = answer.answer() != null && answer.answer().contains("5");
            boolean noFabricatedBuyer = answer.answer() == null
                    || !containsAnyOf(answer.answer(), "Jane Doe", "unknown buyer", "John Smith");
            System.out.println("CONTRACT_CHECK parsedOk=" + parsedOk + " mentionsCountFive=" + mentionsFive
                    + " noObviouslyFabricatedBuyerName=" + noFabricatedBuyer);

            System.out.println("--- USAGE (" + model + ") ---");
            for (var call : usage.calls) {
                System.out.println("  callType=" + call.callType() + " model=" + call.model()
                        + " promptTokens=" + call.promptTokens() + " cachedTokens=" + call.cachedTokens()
                        + " completionTokens=" + call.completionTokens());
            }
        }
    }

    private static boolean containsAnyOf(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ── PART B — 3 additional scenarios beyond the one above ────────────────────────────────────

    private record Scenario(String label, String question, List<InvestigationDataset> datasets) {}

    /** Aggregate answer — a single COUNT(*)-shaped dataset, no per-row detail to enumerate. */
    private static InvestigationDataset aggregateCountDataset() {
        return new InvestigationDataset(1, "Total purchase order count",
                List.of(row("total_purchase_orders", 12)), null, null, List.of());
    }

    /** Multi-dataset answer — step-1 raw rows + step-2 a supplier aggregation, both must be
     *  referenceable so the model has to correctly attribute facts to the right dataset_ref. */
    private static InvestigationDataset multiDatasetStepOne() {
        return new InvestigationDataset(1, "Open purchase orders", List.of(
                row("po_number", "PO-2001", "supplier", "Acme Supplies", "status", "open"),
                row("po_number", "PO-2002", "supplier", "Acme Supplies", "status", "open"),
                row("po_number", "PO-2003", "supplier", "Beacon Goods", "status", "open")
        ), null, null, List.of());
    }
    private static InvestigationDataset multiDatasetStepTwo() {
        return new InvestigationDataset(2, "Order count by supplier", List.of(
                row("supplier", "Acme Supplies", "order_count", 2),
                row("supplier", "Beacon Goods", "order_count", 1)
        ), null, null, List.of());
    }

    /** Sparse/single-record answer — exactly one row, the edge case the refined contract's
     *  "sections/metrics are optional" rule is meant to handle without forcing boilerplate. */
    private static InvestigationDataset singleRecordDataset() {
        return new InvestigationDataset(1, "Purchase order PO-3007", List.of(
                row("po_number", "PO-3007", "buyer", "Diane Osei", "status", "received",
                        "total_ordered_amount", 4820.00)
        ), null, null, List.of());
    }

    private static final List<Scenario> ADDITIONAL_SCENARIOS = List.of(
            new Scenario("B1. Aggregate answer", "How many purchase orders are there in total?",
                    List.of(aggregateCountDataset())),
            new Scenario("B2. Multi-dataset answer", "How many open purchase orders does each supplier have?",
                    List.of(multiDatasetStepOne(), multiDatasetStepTwo())),
            new Scenario("B3. Sparse/single-record answer", "Show me purchase order PO-3007",
                    List.of(singleRecordDataset()))
    );

    @Test
    void compareModelsAcrossAdditionalComposerScenarios() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        String systemPrompt = realDataAnswerSystemPrompt();
        ExecutionOutcomeInterpreter interpreter = new ExecutionOutcomeInterpreter();

        System.out.println("\n########## PHASE 2 PART B — ADDITIONAL COMPOSER SCENARIOS ##########");

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

            NaturalLanguageComposer composer = new NaturalLanguageComposer(aiClient);

            for (Scenario s : ADDITIONAL_SCENARIOS) {
                String datasetsBlock = ChatService.buildInvestigationDatasetsBlock(s.datasets(), interpreter);
                String userPrompt = "Question: " + s.question() + "\n\nQuery results:\n" + datasetsBlock;

                long t0 = System.nanoTime();
                String raw = composer.compose(NaturalLanguageComposer.CompositionRequest.strictJson(
                        userPrompt, systemPrompt, "data_answer", ChatService.dataAnswerJsonSchema(),
                        (String) null));
                long latencyMs = (System.nanoTime() - t0) / 1_000_000;

                StructuredAnswer answer = ChatService.parseStructuredAnswer(raw, mapper, "PARSE_FAILED_FALLBACK");
                boolean parsedOk = !"PARSE_FAILED_FALLBACK".equals(answer.answer());

                System.out.println("\n[" + s.label() + "] \"" + s.question() + "\"");
                System.out.println("  RAW: " + raw);
                System.out.println("  answer=" + answer.answer());
                System.out.println("  sections=" + answer.sections());
                System.out.println("  metrics=" + answer.metrics());
                System.out.println("  parsedOk=" + parsedOk + " latencyMs=" + latencyMs);
            }

            System.out.println("\n--- USAGE (" + model + ") ---");
            for (var call : usage.calls) {
                System.out.println("  callType=" + call.callType() + " model=" + call.model()
                        + " promptTokens=" + call.promptTokens() + " cachedTokens=" + call.cachedTokens()
                        + " completionTokens=" + call.completionTokens());
            }
        }
    }

    /**
     * PHASE 3 — POST-MIGRATION REAL-TENANT VALIDATION against the real deployed configuration
     * (Composer uses {@code composerModel}=gpt-4.1), run once (not A/B) — covers the original
     * grounding scenario (STRICT_JSON, 5 open purchase orders) plus the 3 additional scenarios
     * (aggregate, multi-dataset, sparse/single-record).
     */
    @Test
    void validateDeployedConfigurationAcrossAllComposerScenarios() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        String systemPrompt = realDataAnswerSystemPrompt();
        ExecutionOutcomeInterpreter interpreter = new ExecutionOutcomeInterpreter();

        System.out.println("\n########## PHASE 3 — POST-MIGRATION COMPOSER VALIDATION (real deployed config) ##########");

        RecordingUsageService usage = new RecordingUsageService();
        AzureOpenAiClient aiClient = new AzureOpenAiClient(mapper, usage);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "composerModel", "gpt-4.1");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        NaturalLanguageComposer composer = new NaturalLanguageComposer(aiClient);

        // Main scenario: 5 open purchase orders, dataset grounding.
        {
            String datasetsBlock = ChatService.buildInvestigationDatasetsBlock(
                    List.of(openPurchaseOrdersDataset()), interpreter);
            String userPrompt = "Question: Show me all open purchase orders\n\nQuery results:\n" + datasetsBlock;
            String raw = composer.compose(NaturalLanguageComposer.CompositionRequest.strictJson(
                    userPrompt, systemPrompt, "data_answer", ChatService.dataAnswerJsonSchema(), (String) null));
            StructuredAnswer answer = ChatService.parseStructuredAnswer(raw, mapper, "PARSE_FAILED_FALLBACK");
            System.out.println("\n[Main: open purchase orders] answer=" + answer.answer());
            assertTrue(!"PARSE_FAILED_FALLBACK".equals(answer.answer()), "must parse under the deployed configuration");
            assertTrue(answer.answer().contains("5"), "must correctly ground the count of 5");
        }

        // 3 additional scenarios.
        for (Scenario s : ADDITIONAL_SCENARIOS) {
            String datasetsBlock = ChatService.buildInvestigationDatasetsBlock(s.datasets(), interpreter);
            String userPrompt = "Question: " + s.question() + "\n\nQuery results:\n" + datasetsBlock;
            String raw = composer.compose(NaturalLanguageComposer.CompositionRequest.strictJson(
                    userPrompt, systemPrompt, "data_answer", ChatService.dataAnswerJsonSchema(), (String) null));
            StructuredAnswer answer = ChatService.parseStructuredAnswer(raw, mapper, "PARSE_FAILED_FALLBACK");
            boolean parsedOk = !"PARSE_FAILED_FALLBACK".equals(answer.answer());
            System.out.println("\n[" + s.label() + "] answer=" + answer.answer() + " parsedOk=" + parsedOk);
            assertTrue(parsedOk, "must parse under the deployed configuration: " + s.label());
        }

        System.out.println("\n--- USAGE (deployed config) ---");
        for (var call : usage.calls) {
            System.out.println("  callType=" + call.callType() + " model=" + call.model()
                    + " promptTokens=" + call.promptTokens() + " cachedTokens=" + call.cachedTokens()
                    + " completionTokens=" + call.completionTokens());
            assertEquals("gpt-4.1", call.model(), "every Composer call in the deployed configuration must use gpt-4.1");
        }
    }
}
