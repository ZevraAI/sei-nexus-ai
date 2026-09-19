package com.sei.nexus.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE model-tiering evaluation (opt-in, real OpenAI calls): compares gpt-4o against gpt-4o-mini
 * (the candidate {@code nexus.openai.memory-selection-model} default) against the REAL {@link
 * ChatService#MEMORY_SELECTION_SYSTEM_PROMPT} — same prompt/roster shape {@code
 * buildMemorySelectionContext} builds, exercised directly against {@link
 * AzureOpenAiClient#respondForMemorySelection} rather than the full {@code ChatService} (which
 * needs a live tenant DB/Spring context to construct) — same "real prompt, real parsing, no
 * fuzzy scoring" approach as the sibling TermExtractor/CorrectionDetector evaluation.
 *
 * <p>Guarded by {@code -Dnexus.live.openai=true} and {@code OPENAI_API_KEY}, same convention as
 * {@code IdentifierFidelityLiveProbe}.
 *
 * <p>Contract checked, per {@code MEMORY_SELECTION_SYSTEM_PROMPT}'s own rules: only entity_keys
 * that appear EXACTLY in the supplied roster are ever returned (no hallucinated keys), a key is
 * returned only when the question actually needs it, and an empty roster/no-match question
 * yields an empty list — never an error, never invented keys.
 */
class MemorySelectionModelTieringLiveValidation {

    private static AzureOpenAiClient liveClient(String model) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");
        AzureOpenAiClient client = new AzureOpenAiClient(new ObjectMapper(), null);
        Field apiKeyField = AzureOpenAiClient.class.getDeclaredField("apiKey");
        apiKeyField.setAccessible(true);
        apiKeyField.set(client, apiKey);
        Field modelField = AzureOpenAiClient.class.getDeclaredField("memorySelectionModel");
        modelField.setAccessible(true);
        modelField.set(client, model);
        Method init = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        init.setAccessible(true);
        init.invoke(client);
        return client;
    }

    private record Case(String label, String roster, String question, List<String> allowedKeys,
                        boolean expectNonEmpty) {}

    private static final List<Case> CASES = List.of(
            new Case("exact match needed",
                    "supplier-acme | Acme Supplies | entity\n"
                            + "warehouse-east | East Warehouse | entity",
                    "how much do we owe Acme Supplies",
                    List.of("supplier-acme", "warehouse-east"), true),
            new Case("nothing in roster is relevant",
                    "supplier-acme | Acme Supplies | entity",
                    "what is our total revenue this quarter",
                    List.of("supplier-acme"), false)
    );

    @Test
    void memorySelectionContractHoldsOnBothModels() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live evaluation disabled");

        AzureOpenAiClient gpt4o = liveClient("gpt-4o");
        AzureOpenAiClient gpt4oMini = liveClient("gpt-4o-mini");
        ObjectMapper mapper = new ObjectMapper();

        for (Case c : CASES) {
            List<String> baseline = select(gpt4o, mapper, c);
            List<String> candidate = select(gpt4oMini, mapper, c);

            System.out.println("[" + c.label() + "] gpt-4o      -> " + baseline);
            System.out.println("[" + c.label() + "] gpt-4o-mini -> " + candidate);

            assertMemorySelectionContract(c.label() + " (gpt-4o)", c, baseline);
            assertMemorySelectionContract(c.label() + " (gpt-4o-mini)", c, candidate);
        }
    }

    private static List<String> select(AzureOpenAiClient client, ObjectMapper mapper, Case c) throws Exception {
        String prompt = "Question: " + c.question() + "\n\nAlready known in this conversation:\n" + c.roster();
        String resp = client.respondForMemorySelection(
                List.of(ChatMessage.user(prompt)), ChatService.MEMORY_SELECTION_SYSTEM_PROMPT);
        String json = extractJson(resp);
        Map<String, Object> parsed = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        Object keys = parsed.get("entity_keys");
        return keys instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static void assertMemorySelectionContract(String label, Case c, List<String> keys) {
        for (String k : keys) {
            assertTrue(c.allowedKeys().contains(k),
                    label + ": returned a key not in the supplied roster: '" + k + "'");
        }
        if (c.expectNonEmpty()) {
            assertTrue(!keys.isEmpty(), label + ": expected at least one selected key, got none");
        } else {
            assertTrue(keys.isEmpty(), label + ": expected an empty selection, got " + keys);
        }
    }

    private static String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : "{}";
    }
}
