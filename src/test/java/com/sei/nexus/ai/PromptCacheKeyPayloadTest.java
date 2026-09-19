package com.sei.nexus.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 explicit prompt caching — proves the EXACT transmitted request body for each of the
 * four cache-keyed calls (Stage 1 combined, Planner, Evaluator, Composer), using the same
 * capture-and-abort-before-send diagnostic seam as {@code AzurePayloadCaptureTest} (real request
 * construction, real serialization, no network call — {@code
 * nexus.capture.abortBeforeSend=true} throws immediately after writing the payload to disk).
 *
 * <p>What this proves per call: (a) the expected, exact {@code prompt_cache_key} value is present
 * as a top-level Responses API field, (b) that value is a fixed, versioned constant — never the
 * tenant-specific/question-specific/conversation-specific content injected into this test's own
 * markers, (c) the existing {@code instructions}/{@code input} content is transmitted byte-for-
 * byte unchanged, and (d) every other top-level request field present on the equivalent
 * non-cache-keyed method call is still present and unchanged — the only diff introduced by this
 * phase is the added {@code prompt_cache_key} field.
 */
class PromptCacheKeyPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearCaptureProperties() {
        System.clearProperty("nexus.capture.payload.dir");
        System.clearProperty("nexus.capture.abortBeforeSend");
    }

    private static AzureOpenAiClient client() throws Exception {
        AzureOpenAiClient client = new AzureOpenAiClient(new ObjectMapper(), null);
        setField(client, "apiKey", "unused-capture-only");
        setField(client, "chatModel", "gpt-4o");
        return client;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = AzureOpenAiClient.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Runs {@code action}, expects the capture-abort, and returns the single captured request JSON. */
    private static JsonNode capture(Runnable action) throws Exception {
        Path dir = Files.createTempDirectory("prompt-cache-key-capture");
        System.setProperty("nexus.capture.payload.dir", dir.toString());
        System.setProperty("nexus.capture.abortBeforeSend", "true");
        try {
            action.run();
            fail("expected abort-before-send");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("send aborted"), expected.getMessage());
        }
        Path payloadFile;
        try (var s = Files.list(dir)) {
            payloadFile = s.findFirst().orElseThrow();
        }
        return MAPPER.readTree(Files.readString(payloadFile));
    }

    // ── 1. Planner sends the expected cache key ─────────────────────────────────────────────────

    @Test
    void plannerSendsTheExpectedCacheKey() throws Exception {
        AzureOpenAiClient client = client();
        String systemPrompt = "PLANNER-SYSTEM-PROMPT-MARKER";
        String question = "TENANT-SPECIFIC-QUESTION-MARKER-conn-9f1a";

        JsonNode keyed = capture(() ->
                client.respondForPlanner(List.of(ChatMessage.user(question)), systemPrompt));

        assertEquals("zevra:planner:v1", keyed.path("prompt_cache_key").asText());
        assertEquals(systemPrompt, keyed.path("instructions").asText(), "instructions must be transmitted unchanged");
        assertTrue(keyed.path("input").asText().contains(question), "dynamic input must be transmitted unchanged");
        assertFalse(keyed.path("prompt_cache_key").asText().contains("TENANT-SPECIFIC-QUESTION-MARKER"),
                "the cache key must never contain the question or any dynamic request content");

        JsonNode unkeyed = capture(() ->
                client.respond(List.of(ChatMessage.user(question)), systemPrompt));
        assertFieldsIdenticalExceptCacheKey(unkeyed, keyed);
    }

    // ── 2. Evaluator sends the expected cache key ───────────────────────────────────────────────

    @Test
    void evaluatorSendsTheExpectedCacheKey() throws Exception {
        AzureOpenAiClient client = client();
        String systemPrompt = "EVALUATOR-SYSTEM-PROMPT-MARKER";
        String question = "TENANT-SPECIFIC-EVIDENCE-MARKER-conn-2b7c";

        JsonNode keyed = capture(() ->
                client.respondForEvaluator(List.of(ChatMessage.user(question)), systemPrompt));

        assertEquals("zevra:evaluator:v1", keyed.path("prompt_cache_key").asText());
        assertEquals(systemPrompt, keyed.path("instructions").asText());
        assertTrue(keyed.path("input").asText().contains(question));
        assertFalse(keyed.path("prompt_cache_key").asText().contains("TENANT-SPECIFIC-EVIDENCE-MARKER"));

        JsonNode unkeyed = capture(() ->
                client.respond(List.of(ChatMessage.user(question)), systemPrompt));
        assertFieldsIdenticalExceptCacheKey(unkeyed, keyed);
    }

    // ── 3. Composer sends the expected cache key (TEXT and STRICT_JSON modes) ──────────────────

    @Test
    void composerTextModeSendsTheExpectedCacheKey() throws Exception {
        AzureOpenAiClient client = client();
        String systemPrompt = "COMPOSER-SYSTEM-PROMPT-MARKER";
        String question = "TENANT-SPECIFIC-DATASET-MARKER-conn-4d81";

        JsonNode keyed = capture(() ->
                client.respondForComposer(List.of(ChatMessage.user(question)), systemPrompt));

        assertEquals("zevra:answer-composer:v1", keyed.path("prompt_cache_key").asText());
        assertEquals(systemPrompt, keyed.path("instructions").asText());
        assertTrue(keyed.path("input").asText().contains(question));
        assertFalse(keyed.path("prompt_cache_key").asText().contains("TENANT-SPECIFIC-DATASET-MARKER"));

        JsonNode unkeyed = capture(() ->
                client.respond(List.of(ChatMessage.user(question)), systemPrompt));
        assertFieldsIdenticalExceptCacheKey(unkeyed, keyed);
    }

    @Test
    void composerStrictJsonModeSendsTheExpectedCacheKey() throws Exception {
        AzureOpenAiClient client = client();
        String systemPrompt = "COMPOSER-STRICT-SYSTEM-PROMPT-MARKER";
        String question = "TENANT-SPECIFIC-ROWS-MARKER-conn-7ee0";
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("answer", Map.of("type", "string")),
                "required", List.of("answer"),
                "additionalProperties", false);

        JsonNode keyed = capture(() -> client.respondWithStrictJsonForComposer(
                List.of(ChatMessage.user(question)), systemPrompt, "data_answer", schema));

        assertEquals("zevra:answer-composer:v1", keyed.path("prompt_cache_key").asText());
        assertEquals(systemPrompt, keyed.path("instructions").asText());
        assertTrue(keyed.path("input").asText().contains(question));
        assertEquals("json_schema", keyed.path("text").path("format").path("type").asText(),
                "strict schema mode must be unaffected by the cache key addition");
        assertFalse(keyed.path("prompt_cache_key").asText().contains("TENANT-SPECIFIC-ROWS-MARKER"));

        JsonNode unkeyed = capture(() -> client.respondWithStrictJson(
                List.of(ChatMessage.user(question)), systemPrompt, "data_answer", schema));
        assertFieldsIdenticalExceptCacheKey(unkeyed, keyed);
    }

    // ── 4. Stage 1 combined File Search sends the expected cache key ───────────────────────────

    @Test
    void stage1CombinedSendsTheExpectedCacheKey() throws Exception {
        AzureOpenAiClient client = client();
        String instructions = "STAGE1-COMBINED-INSTRUCTIONS-MARKER";
        String question = "TENANT-SPECIFIC-QUESTION-MARKER-conn-a13f";
        String vectorStoreId = "vs_tenant_specific_marker_5591";
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("metadataRequest", Map.of("type", "object")),
                "required", List.of("metadataRequest"),
                "additionalProperties", false);

        JsonNode keyed = capture(() -> client.chatWithFileSearchForConceptAndRouting(
                vectorStoreId, instructions, question, null, schema));

        assertEquals("zevra:stage1-concept-and-routing:v1", keyed.path("prompt_cache_key").asText());
        assertEquals(instructions, keyed.path("instructions").asText());
        assertTrue(keyed.path("input").asText().contains(question));
        assertEquals(vectorStoreId, keyed.path("tools").get(0).path("vector_store_ids").get(0).asText(),
                "File Search tool configuration must be unaffected by the cache key addition");
        assertFalse(keyed.path("prompt_cache_key").asText().contains("TENANT-SPECIFIC-QUESTION-MARKER"));
        assertFalse(keyed.path("prompt_cache_key").asText().contains("tenant_specific_marker"));

        JsonNode unkeyed = capture(() -> client.chatWithFileSearch(
                vectorStoreId, instructions, question, null, schema));
        assertFieldsIdenticalExceptCacheKey(unkeyed, keyed);
    }

    // ── 5 / 7. Every field on the un-keyed request is still present and unchanged ──────────────

    /** Verifies the keyed and unkeyed requests are identical in every field except the additive
     *  {@code prompt_cache_key} — proving the change is purely additive, not a reordering or
     *  restructuring of the existing stable prefix / dynamic content. */
    private static void assertFieldsIdenticalExceptCacheKey(JsonNode unkeyed, JsonNode keyed) {
        var unkeyedFields = new java.util.TreeSet<String>();
        unkeyed.fieldNames().forEachRemaining(unkeyedFields::add);
        var keyedFields = new java.util.TreeSet<String>();
        keyed.fieldNames().forEachRemaining(keyedFields::add);

        assertFalse(unkeyedFields.contains("prompt_cache_key"),
                "the existing (non-cache-keyed) method must never itself gain a prompt_cache_key");
        keyedFields.remove("prompt_cache_key");
        assertEquals(unkeyedFields, keyedFields,
                "the cache-keyed call must add prompt_cache_key and nothing else");

        for (String field : unkeyedFields) {
            assertEquals(unkeyed.path(field), keyed.path(field),
                    "field '" + field + "' must be byte-for-byte identical between the keyed and unkeyed calls");
        }
    }
}
