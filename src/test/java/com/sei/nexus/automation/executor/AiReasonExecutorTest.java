package com.sei.nexus.automation.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.automation.ExecutionContext;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.response.NaturalLanguageComposer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified Answer Engine, Phase 4 — Step 5. The AI_REASON node composes through the shared
 * {@link NaturalLanguageComposer}; this executor keeps its node policy — variable templating,
 * default system prompts, JSON→Map parsing, and propagate-on-failure. Wired to a hand-rolled fake
 * AI through a real composer (no network).
 */
class AiReasonExecutorTest {

    static class FakeAi extends AzureOpenAiClient {
        String seenSystem;
        String seenUser;
        String usedMethod;
        boolean fail = false;
        String jsonReturn = "{\"ok\":true}";
        FakeAi() { super(new ObjectMapper(), null); }
        @Override public String chat(List<ChatMessage> messages, String systemPrompt) {
            usedMethod = "chat"; return record(messages, systemPrompt, "plain text answer");
        }
        @Override public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
            usedMethod = "chatWithJson"; return record(messages, systemPrompt, jsonReturn);
        }
        private String record(List<ChatMessage> messages, String systemPrompt, String out) {
            this.seenSystem = systemPrompt;
            this.seenUser = messages.get(0).content();
            if (fail) throw new RuntimeException("model down");
            return out;
        }
    }

    private AiReasonExecutor executor(FakeAi ai) {
        return new AiReasonExecutor(new NaturalLanguageComposer(ai), new ObjectMapper());
    }

    @Test
    void textModeResolvesTemplatesAndReturnsPlainString() throws Exception {
        FakeAi ai = new FakeAi();
        ExecutionContext ctx = new ExecutionContext();
        ctx.set("name", "inventory");

        Object out = executor(ai).execute("n1",
                Map.of("userPrompt", "Summarize {{name}} status", "outputFormat", "text"), ctx);

        assertEquals("plain text answer", out);
        assertEquals("chat", ai.usedMethod, "text output uses the plain chat method");
        assertEquals("Summarize inventory status", ai.seenUser, "the {{variable}} template is resolved by the executor");
        assertEquals("You are a helpful assistant.", ai.seenSystem, "the executor's default text system prompt is preserved");
    }

    @Test
    void jsonModeParsesModelJsonIntoAMap() throws Exception {
        FakeAi ai = new FakeAi();
        Object out = executor(ai).execute("n1",
                Map.of("userPrompt", "give json", "outputFormat", "json"), new ExecutionContext());

        assertEquals("chatWithJson", ai.usedMethod, "json output uses the json response method");
        assertEquals("You are a helpful assistant. Respond in JSON.", ai.seenSystem, "the executor's default json system prompt is preserved");
        assertInstanceOf(Map.class, out);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) out).get("ok"), "valid model JSON is parsed into a Map");
    }

    @Test
    void jsonModeWrapsUnparseableOutputUnderRawKey() throws Exception {
        FakeAi ai = new FakeAi();
        ai.jsonReturn = "not json at all";
        Object out = executor(ai).execute("n1",
                Map.of("userPrompt", "q", "outputFormat", "json"), new ExecutionContext());

        assertInstanceOf(Map.class, out);
        assertEquals("not json at all", ((Map<?, ?>) out).get("raw"), "unparseable output falls back to {raw: ...}");
    }

    @Test
    void modelFailurePropagatesToFailTheStep() {
        FakeAi ai = new FakeAi();
        ai.fail = true;
        assertThrows(RuntimeException.class,
                () -> executor(ai).execute("n1", Map.of("userPrompt", "q"), new ExecutionContext()),
                "an AI step has no answer fallback — a model failure propagates and fails the automation");
    }

    @Test
    void missingUserPromptIsRejected() {
        assertThrows(NexusException.class,
                () -> executor(new FakeAi()).execute("n1", Map.of(), new ExecutionContext()),
                "userPrompt is required node config");
    }
}
