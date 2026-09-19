package com.sei.nexus.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.response.NaturalLanguageComposer.CompositionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified Answer Engine, Phase 4 — Steps 2/3. The shared {@link NaturalLanguageComposer} owns
 * only the model-call mechanics. It passes the caller's prompt and composition policy through,
 * selects the response mode (TEXT vs JSON), returns the model output on success, and on failure
 * returns the caller's lazily-evaluated fallback — or propagates when no fallback is given. It
 * embeds no experience content. Hand-rolled fake; no network.
 */
class NaturalLanguageComposerTest {

    /** Records what the composer sent and which model method it used; can be made to fail. */
    static class FakeAi extends AzureOpenAiClient {
        String seenUser;
        String seenSystem;
        String calledMethod;      // "respond" (TEXT), "respondWithJson" (JSON), or "respondWithStrictJson"
        String seenSchemaName;
        Map<String, Object> seenSchema;
        boolean throwOnCall = false;
        FakeAi() { super(new ObjectMapper(), null); }
        @Override public String respond(List<ChatMessage> messages, String systemPrompt) {
            calledMethod = "respond"; return record(messages, systemPrompt, "TEXT OUTPUT");
        }
        @Override public String respondWithJson(List<ChatMessage> messages, String systemPrompt) {
            calledMethod = "respondWithJson"; return record(messages, systemPrompt, "{\"json\":true}");
        }
        @Override public String respondWithStrictJson(List<ChatMessage> messages, String systemPrompt,
                                                        String jsonSchemaName, Map<String, Object> jsonSchema) {
            calledMethod = "respondWithStrictJson";
            seenSchemaName = jsonSchemaName;
            seenSchema = jsonSchema;
            return record(messages, systemPrompt, "{\"strict\":true}");
        }
        // Phase 1 explicit prompt caching: NaturalLanguageComposer's TEXT/STRICT_JSON modes now
        // call respondForComposer/respondWithStrictJsonForComposer (both attaching
        // prompt_cache_key="zevra:answer-composer:v1") instead of respond/respondWithStrictJson.
        @Override public String respondForComposer(List<ChatMessage> messages, String systemPrompt) {
            return respond(messages, systemPrompt);
        }
        @Override public String respondWithStrictJsonForComposer(List<ChatMessage> messages, String systemPrompt,
                                                                   String jsonSchemaName, Map<String, Object> jsonSchema) {
            return respondWithStrictJson(messages, systemPrompt, jsonSchemaName, jsonSchema);
        }
        private String record(List<ChatMessage> messages, String systemPrompt, String out) {
            this.seenSystem = systemPrompt;
            this.seenUser = messages.get(0).content();
            if (throwOnCall) throw new RuntimeException("model unavailable");
            return out;
        }
    }

    @Test
    void textModePassesPromptAndPolicyThroughAndReturnsOutput() {
        FakeAi ai = new FakeAi();
        String out = new NaturalLanguageComposer(ai)
                .compose(CompositionRequest.text("USER PROMPT", "SYSTEM POLICY", "fallback"));

        assertEquals("TEXT OUTPUT", out);
        assertEquals("respond", ai.calledMethod, "TEXT mode uses the plain (non-JSON) response method");
        assertEquals("USER PROMPT", ai.seenUser);
        assertEquals("SYSTEM POLICY", ai.seenSystem, "the composition policy is passed through unchanged");
    }

    @Test
    void jsonModeUsesTheJsonMethod() {
        FakeAi ai = new FakeAi();
        String out = new NaturalLanguageComposer(ai)
                .compose(CompositionRequest.json("q", "sys", "fb"));

        assertEquals("{\"json\":true}", out);
        assertEquals("respondWithJson", ai.calledMethod, "JSON mode uses the json response method");
    }

    @Test
    void returnsFallbackOnFailure() {
        FakeAi ai = new FakeAi();
        ai.throwOnCall = true;
        assertEquals("safe fallback",
                new NaturalLanguageComposer(ai).compose(CompositionRequest.text("q", "sys", "safe fallback")),
                "composition returns the caller's fallback rather than breaking the response");
    }

    @Test
    void supplierFallbackIsLazy() {
        FakeAi ok = new FakeAi();
        AtomicBoolean evaluatedOnSuccess = new AtomicBoolean(false);
        new NaturalLanguageComposer(ok).compose(CompositionRequest.text("q", "sys",
                () -> { evaluatedOnSuccess.set(true); return "fb"; }));
        assertFalse(evaluatedOnSuccess.get(), "the fallback is never evaluated on success");

        FakeAi bad = new FakeAi();
        bad.throwOnCall = true;
        AtomicBoolean evaluatedOnFailure = new AtomicBoolean(false);
        String out = new NaturalLanguageComposer(bad).compose(CompositionRequest.text("q", "sys",
                () -> { evaluatedOnFailure.set(true); return "lazy fb"; }));
        assertEquals("lazy fb", out);
        assertTrue(evaluatedOnFailure.get(), "the lazy fallback is evaluated only when the call fails");
    }

    @Test
    void strictJsonModeUsesTheStrictJsonMethodAndPassesTheSchemaThrough() {
        FakeAi ai = new FakeAi();
        Map<String, Object> schema = Map.of("type", "object");
        String out = new NaturalLanguageComposer(ai)
                .compose(CompositionRequest.strictJson("q", "sys", "my_schema", schema, "fb"));

        assertEquals("{\"strict\":true}", out);
        assertEquals("respondWithStrictJson", ai.calledMethod,
                "STRICT_JSON mode uses the strict-schema response method");
        assertEquals("my_schema", ai.seenSchemaName);
        assertSame(schema, ai.seenSchema, "the schema object is passed through unchanged");
    }

    @Test
    void strictJsonModeReturnsFallbackOnFailure() {
        FakeAi ai = new FakeAi();
        ai.throwOnCall = true;
        assertEquals("safe fallback",
                new NaturalLanguageComposer(ai).compose(
                        CompositionRequest.strictJson("q", "sys", "schema", Map.of(), "safe fallback")),
                "a strict-schema call failure (e.g. a genuine API error) still degrades to the caller's fallback");
    }

    /** A null fallback (jsonPropagating) means the caller wants failures to propagate — e.g. Brief. */
    @Test
    void nullFallbackPropagatesFailure() {
        FakeAi ai = new FakeAi();
        ai.throwOnCall = true;
        NaturalLanguageComposer c = new NaturalLanguageComposer(ai);

        assertThrows(RuntimeException.class,
                () -> c.compose(CompositionRequest.jsonPropagating("q", "sys")),
                "with no fallback, the model failure propagates for the caller to handle");
    }
}
