package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DIAGNOSTIC ONLY — real OpenAI, real tenant {@code persistent-ai-test} Vector Store. Excluded
 * from Surefire's default run (same {@code *RealTenantValidation} naming convention).
 *
 * <p>Replicates the exact production combined Stage-1 request shape (same model, same real
 * {@code PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT}/{@code COMBINED_STAGE1_JSON_SCHEMA}
 * constants read by reflection, verbatim, same {@code input}/runtime-facts construction) built
 * inline via raw HTTP — the same pattern already used elsewhere in this file family (e.g. {@code
 * diagnosticRawResponseForPurchaseOrderQuestion}) — with exactly ONE additive, read-only request
 * field beyond what production sends: {@code include=["file_search_call.results"]}, so the raw
 * retrieved evidence (queries issued, files/scores/snippets) can be inspected instead of being
 * discarded before this diagnostic ever sees it. No production code, prompt, schema, or model
 * parameter is changed — this exact additive field was already used, and accepted, in an earlier
 * diagnostic this session for the same reason. Every call is fresh ({@code previous_response_id}
 * omitted) — never chained.
 */
class OpenPurchaseOrdersStage1RetrievalVarianceRealTenantValidation {

    private static final String VECTOR_STORE_ID = "vs_6a933a9fbdf481919c228d36e0b6a320";
    private static final int RUNS = 20;

    @Test
    void repeatedFreshCallsCaptureRetrievalVsInterpretationVariance() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }

        java.lang.reflect.Field promptField = com.sei.nexus.agentbrain.ConceptScopedMetadataResolver.class
                .getDeclaredField("PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT");
        promptField.setAccessible(true);
        String instructions = (String) promptField.get(null);

        java.lang.reflect.Field schemaField = com.sei.nexus.agentbrain.ConceptScopedMetadataResolver.class
                .getDeclaredField("COMBINED_STAGE1_JSON_SCHEMA");
        schemaField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> jsonSchema = (Map<String, Object>) schemaField.get(null);

        String question = "Show me all open purchase orders";
        String input = question + "\n\n(Respond in JSON as instructed.)"
                + "\n\nRuntime facts (for the routing decision only, never for concept resolution):\n"
                + "- Document memory available for this question: false";

        ObjectMapper mapper = new ObjectMapper();
        HttpClient client = HttpClient.newHttpClient();

        int successA = 0, successB = 0, successC = 0, otherD = 0;
        List<String> summary = new ArrayList<>();

        for (int run = 1; run <= RUNS; run++) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "file_search");
            tool.put("vector_store_ids", List.of(VECTOR_STORE_ID));
            Map<String, Object> textFormat = new LinkedHashMap<>();
            textFormat.put("type", "json_schema");
            textFormat.put("name", "persistent_knowledge_response");
            textFormat.put("strict", true);
            textFormat.put("schema", jsonSchema);
            Map<String, Object> text = Map.of("format", textFormat);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", "gpt-4o");
            requestBody.put("instructions", instructions);
            requestBody.put("input", input);
            requestBody.put("tools", List.of(tool));
            requestBody.put("text", text);
            // Additive, read-only diagnostic field — never present in the production request —
            // so the raw retrieved evidence is visible instead of discarded.
            requestBody.put("include", List.of("file_search_call.results"));

            String jsonBody = mapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/responses"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                System.out.println("Run " + run + ": HTTP error — " + e.getMessage());
                summary.add("Run " + run + ": HTTP_ERROR");
                continue;
            }

            if (response.statusCode() != 200) {
                System.out.println("Run " + run + ": HTTP " + response.statusCode() + " — " + truncate(response.body(), 300));
                summary.add("Run " + run + ": HTTP_" + response.statusCode());
                // Basic pacing backoff on rate limit before continuing.
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                continue;
            }

            JsonNode root = mapper.readTree(response.body());
            String responseId = root.path("id").asText(null);

            boolean fileSearchPresent = false;
            String fileSearchStatus = "absent";
            List<String> queries = new ArrayList<>();
            List<String> retrievedFileIds = new ArrayList<>();
            List<String> retrievedSnippets = new ArrayList<>();
            String finalText = "";

            for (JsonNode item : root.path("output")) {
                if ("file_search_call".equals(item.path("type").asText())) {
                    fileSearchPresent = true;
                    fileSearchStatus = item.path("status").asText("unknown");
                    for (JsonNode q : item.path("queries")) queries.add(q.asText());
                    for (JsonNode r : item.path("results")) {
                        retrievedFileIds.add(r.path("file_id").asText(r.path("filename").asText("?")));
                        String snippet = r.path("text").asText("");
                        if (!snippet.isBlank()) retrievedSnippets.add(truncate(snippet, 200));
                    }
                }
                if ("message".equals(item.path("type").asText())) {
                    for (JsonNode content : item.path("content")) {
                        if ("output_text".equals(content.path("type").asText())) {
                            finalText += content.path("text").asText();
                        }
                    }
                }
            }

            boolean poEvidenceRetrieved = containsIgnoreCase(retrievedFileIds, "purchase")
                    || containsIgnoreCase(retrievedSnippets, "purchase")
                    || containsIgnoreCase(queries, "purchase");
            boolean openVocabRetrieved = containsIgnoreCase(retrievedSnippets, "open purchase")
                    || containsIgnoreCase(retrievedSnippets, "submitted")
                    || containsIgnoreCase(queries, "open");

            String conceptKeys = "UNPARSED";
            String routing = "UNPARSED";
            try {
                JsonNode parsed = mapper.readTree(finalText);
                conceptKeys = parsed.path("metadataRequest").path("conceptKeys").toString();
                routing = parsed.path("routing").toString();
            } catch (Exception ignored) { }

            boolean conceptSelected = conceptKeys.contains("purchase-order");

            String classification;
            if (poEvidenceRetrieved && conceptSelected) { classification = "A"; successA++; }
            else if (!poEvidenceRetrieved && !conceptSelected) { classification = "B"; successB++; }
            else if (poEvidenceRetrieved && !conceptSelected) { classification = "C"; successC++; }
            else { classification = "D"; otherD++; }

            System.out.println("\n===== RUN " + run + " (responseId=" + responseId + ") =====");
            System.out.println("fileSearchPresent=" + fileSearchPresent + " status=" + fileSearchStatus);
            System.out.println("queries=" + queries);
            System.out.println("retrievedFileIds=" + retrievedFileIds);
            System.out.println("retrievedSnippets=" + retrievedSnippets);
            System.out.println("poEvidenceRetrieved=" + poEvidenceRetrieved + " openVocabRetrieved=" + openVocabRetrieved);
            System.out.println("conceptKeys=" + conceptKeys);
            System.out.println("routing=" + routing);
            System.out.println("classification=" + classification);

            summary.add("Run " + run + ": conceptKeys=" + conceptKeys + " classification=" + classification
                    + " poEvidence=" + poEvidenceRetrieved + " queries=" + queries);

            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
        }

        System.out.println("\n\n================ SUMMARY ================");
        for (String s : summary) System.out.println(s);
        System.out.println("A (evidence + correct concept) = " + successA);
        System.out.println("B (no evidence + empty concept) = " + successB);
        System.out.println("C (evidence + empty concept) = " + successC);
        System.out.println("D (other) = " + otherD);
    }

    private static boolean containsIgnoreCase(List<String> list, String needle) {
        for (String s : list) {
            if (s != null && s.toLowerCase().contains(needle.toLowerCase())) return true;
        }
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
