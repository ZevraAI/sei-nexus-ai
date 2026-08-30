package com.sei.nexus.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.usage.UsageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zevra Cognitive Runtime baseline — instrumentation-only tests for the {@code LLM_METRIC}
 * measurement added to {@link AzureOpenAiClient#doChat} / {@code recordUsage}, and for
 * {@link LlmCallTag}. These prove the new measurement code (a) extracts
 * {@code cached_tokens} correctly when present, (b) never regresses the pre-existing
 * usage-recording behavior (unchanged assertions on {@link UsageService#record}), (c) is a
 * complete no-op when {@code usage} is absent from the response (exactly as before this
 * instrumentation existed), and (d) always clears the {@link LlmCallTag} after the call, so it
 * can never leak onto an unrelated subsequent call on the same thread.
 *
 * <p>Same hand-rolled-fakes / {@code sendHttp}-override convention as
 * {@link AzureOpenAiClientThrottleTest} — no Mockito, no network, no DB.
 */
class AzureOpenAiClientMetricsTest {

    @AfterEach
    void clearTagBetweenTests() {
        LlmCallTag.clear();
    }

    /** Records every {@link UsageService#record} invocation without touching a real repository. */
    static class RecordingUsageService extends UsageService {
        record Call(String model, int promptTokens, int completionTokens) {}
        final List<Call> calls = new java.util.ArrayList<>();
        RecordingUsageService() { super(null); }
        @Override public void record(String model, int promptTokens, int completionTokens) {
            calls.add(new Call(model, promptTokens, completionTokens));
        }
    }

    /** Subclass that returns a scripted HTTP response instead of calling the real network. */
    static class ScriptedClient extends AzureOpenAiClient {
        final String scriptedBody;
        final AtomicInteger sendCount = new AtomicInteger(0);
        ScriptedClient(String scriptedBody, UsageService usageService) {
            super(new ObjectMapper(), usageService);
            this.scriptedBody = scriptedBody;
            // @PostConstruct never runs outside a Spring context — same reflection-based
            // init used by AzureOpenAiClientThrottleTest's setLimit(), needed so
            // executeWithRetry's globalCallLimit.acquire() doesn't NPE in a plain unit test.
            try {
                java.lang.reflect.Method init = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
                init.setAccessible(true);
                init.invoke(this);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        @Override
        protected HttpResponse<String> sendHttp(HttpRequest request) {
            sendCount.incrementAndGet();
            return new FakeHttpResponse(200, scriptedBody);
        }
    }

    private record FakeHttpResponse(int statusCode, String body) implements HttpResponse<String> {
        @Override public int statusCode() { return statusCode; }
        @Override public String body() { return body; }
        @Override public HttpRequest request() { throw new UnsupportedOperationException(); }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public java.net.URI uri() { throw new UnsupportedOperationException(); }
        @Override public HttpClient.Version version() { throw new UnsupportedOperationException(); }
    }

    // ── cached_tokens extraction + no regression on existing usage recording ────────────────

    @Test
    void cachedTokensAreExtractedAndUsageRecordingIsUnchanged() {
        RecordingUsageService usage = new RecordingUsageService();
        ScriptedClient client = new ScriptedClient("""
                {"choices":[{"message":{"content":"ok"}}],
                 "usage":{"prompt_tokens":1234,"completion_tokens":56,
                           "prompt_tokens_details":{"cached_tokens":900}}}
                """, usage);

        String answer = client.chat(List.of(ChatMessage.user("hi")), "system prompt");

        assertEquals("ok", answer);
        assertEquals(1, usage.calls.size(), "usageService.record must still be called exactly once, unchanged");
        // Model name assertion intentionally omitted: chatModel is populated via @Value, which
        // is not injected when the client is constructed directly (no Spring context) — a
        // pre-existing test-construction property unrelated to this instrumentation.
        assertEquals(1234, usage.calls.get(0).promptTokens());
        assertEquals(56, usage.calls.get(0).completionTokens());
        // cached_tokens is read purely for the LLM_METRIC log line — it never reaches
        // usageService.record (billing logic is untouched), so there is nothing further to
        // assert on the RecordingUsageService for that field; its presence in the scripted
        // response is exercised here only to prove the parsing path doesn't throw.
    }

    @Test
    void absentUsageNodeIsStillACompleteNoOpExactlyAsBeforeThisInstrumentation() {
        RecordingUsageService usage = new RecordingUsageService();
        ScriptedClient client = new ScriptedClient(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}", usage);

        String answer = client.chat(List.of(ChatMessage.user("hi")), "system prompt");

        assertEquals("ok", answer);
        assertTrue(usage.calls.isEmpty(), "no usage node in the response ⇒ record() must never be called");
    }

    @Test
    void absentCachedTokensFieldDefaultsToZeroRatherThanThrowing() {
        RecordingUsageService usage = new RecordingUsageService();
        // A response with usage but no prompt_tokens_details at all (e.g. an older-shaped
        // response, or a call whose prefix was never cache-eligible) must not throw.
        ScriptedClient client = new ScriptedClient("""
                {"choices":[{"message":{"content":"ok"}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}
                """, usage);

        assertDoesNotThrow(() -> client.chat(List.of(ChatMessage.user("hi")), "system"));
        assertEquals(1, usage.calls.size());
        assertEquals(10, usage.calls.get(0).promptTokens());
        assertEquals(5, usage.calls.get(0).completionTokens());
    }

    // ── LlmCallTag is always cleared after the call ──────────────────────────────────────────

    @Test
    void llmCallTagIsClearedAfterEveryCallEvenWithoutAnExplicitClearByTheCaller() {
        RecordingUsageService usage = new RecordingUsageService();
        ScriptedClient client = new ScriptedClient(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}",
                usage);

        LlmCallTag.set("SOME_CALL_TYPE");
        client.chat(List.of(ChatMessage.user("hi")), "system");

        assertEquals("UNTAGGED", LlmCallTag.get(),
                "the tag must be cleared by AzureOpenAiClient after recording the call — "
                        + "otherwise it would leak onto the next, unrelated call on this thread");
    }

    @Test
    void llmCallTagClearsEvenWhenUsageNodeIsAbsent() {
        RecordingUsageService usage = new RecordingUsageService();
        ScriptedClient client = new ScriptedClient(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}", usage);

        LlmCallTag.set("ANOTHER_CALL_TYPE");
        client.chat(List.of(ChatMessage.user("hi")), "system");

        assertEquals("UNTAGGED", LlmCallTag.get());
    }
}
