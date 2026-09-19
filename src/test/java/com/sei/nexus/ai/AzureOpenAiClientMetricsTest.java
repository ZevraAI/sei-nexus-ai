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
        record Call(String model, int promptTokens, int completionTokens, int cachedTokens, String callType) {}
        final List<Call> calls = new java.util.ArrayList<>();
        RecordingUsageService() { super(null); }
        // Cost/call-type observability: AzureOpenAiClient.recordUsage() now calls the 5-arg
        // overload (added so call_type can be persisted alongside cached_tokens) instead of the
        // 4-arg one — override that actual seam, not the one it delegates from, or these calls
        // fall through to the real (un-fakeable, DB-backed) implementation and go unrecorded here.
        @Override
        public void record(String model, int promptTokens, int completionTokens, int cachedTokens, String callType) {
            calls.add(new Call(model, promptTokens, completionTokens, cachedTokens, callType));
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
        // Cost baseline instrumentation: cached_tokens now also reaches usageService.record (so
        // the discounted cached-input rate can be applied), in addition to the LLM_METRIC log line.
        assertEquals(900, usage.calls.get(0).cachedTokens());
    }

    @Test
    void callTypeReachesUsageServiceFromTheExistingLlmCallTag() {
        RecordingUsageService usage = new RecordingUsageService();
        ScriptedClient client = new ScriptedClient("""
                {"choices":[{"message":{"content":"ok"}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":2,
                           "prompt_tokens_details":{"cached_tokens":0}}}
                """, usage);

        LlmCallTag.set("PLANNER");
        client.chat(List.of(ChatMessage.user("hi")), "system prompt");

        assertEquals(1, usage.calls.size());
        assertEquals("PLANNER", usage.calls.get(0).callType(),
                "the call site's existing LlmCallTag must reach UsageService.record as call_type");
    }

    @Test
    void noLlmCallTagSetYieldsTheUntaggedSafeRepresentationNotAGuess() {
        RecordingUsageService usage = new RecordingUsageService();
        ScriptedClient client = new ScriptedClient("""
                {"choices":[{"message":{"content":"ok"}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":2}}
                """, usage);

        // No LlmCallTag.set(...) call before this — deliberately simulating a caller that never tagged.
        client.chat(List.of(ChatMessage.user("hi")), "system prompt");

        assertEquals("UNTAGGED", usage.calls.get(0).callType(),
                "an untagged call must report the existing safe 'UNTAGGED' representation, "
                        + "never an inferred/guessed call type");
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

    // ── Phase 0 telemetry hardening: Responses API path (recordResponsesUsage) ─────────────────
    // Chat Completions (doChat/recordUsage) is covered above; the Responses API path
    // (doRespond/recordResponsesUsage) reads different field names (input_tokens/output_tokens/
    // input_tokens_details.cached_tokens vs prompt_tokens/completion_tokens/
    // prompt_tokens_details.cached_tokens) and is exercised by a different set of core calls
    // (Stage 1, Memory Selection, Planner, Evaluator, Composer, Teaching) — verified separately
    // so a regression in one shape can never hide behind the other's passing tests.

    @Test
    void responsesApiUsageWithCachedTokensIsPersistedCorrectly() {
        RecordingUsageService usage = new RecordingUsageService();
        ScriptedClient client = new ScriptedClient("""
                {"output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}],
                 "usage":{"input_tokens":2000,"output_tokens":80,
                           "input_tokens_details":{"cached_tokens":1500}}}
                """, usage);

        LlmCallTag.set("PLANNER");
        String answer = client.respond(List.of(ChatMessage.user("hi")), "system prompt");

        assertEquals("ok", answer);
        assertEquals(1, usage.calls.size());
        assertEquals(2000, usage.calls.get(0).promptTokens());
        assertEquals(80, usage.calls.get(0).completionTokens());
        assertEquals(1500, usage.calls.get(0).cachedTokens());
        assertEquals("PLANNER", usage.calls.get(0).callType());
    }

    @Test
    void responsesApiUsageWithoutCachedTokensDefaultsToZeroRatherThanThrowing() {
        RecordingUsageService usage = new RecordingUsageService();
        // A Responses payload with usage but no input_tokens_details at all — must not throw,
        // and must not be confused with "cache hit of 0" being anything other than "unknown/none".
        ScriptedClient client = new ScriptedClient("""
                {"output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}],
                 "usage":{"input_tokens":500,"output_tokens":40}}
                """, usage);

        assertDoesNotThrow(() -> client.respond(List.of(ChatMessage.user("hi")), "system prompt"));
        assertEquals(1, usage.calls.size());
        assertEquals(500, usage.calls.get(0).promptTokens());
        assertEquals(40, usage.calls.get(0).completionTokens());
        assertEquals(0, usage.calls.get(0).cachedTokens());
    }

    @Test
    void responsesApiWithAbsentUsageNodeNeverRecordsAPhantomRow() {
        RecordingUsageService usage = new RecordingUsageService();
        ScriptedClient client = new ScriptedClient(
                "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}",
                usage);

        client.respond(List.of(ChatMessage.user("hi")), "system prompt");

        assertTrue(usage.calls.isEmpty(), "no usage node in a Responses payload ⇒ record() must never be called");
    }

    // ── Phase 0 telemetry hardening: embeddings are not misclassified as chat usage ─────────────
    // embed() reuses the same recordUsage() seam Chat Completions calls do (embeddings responses
    // shape their usage object the same way: prompt_tokens/completion_tokens, no
    // prompt_tokens_details at all since OpenAI does not cache embedding inputs). This proves (a)
    // an embedding response's absent prompt_tokens_details still defaults cached_tokens to 0
    // without throwing, and (b) when the call site tags itself (as DocumentMemoryService now does
    // at both its ingest and query-time embed() call sites), that tag reaches call_type exactly
    // like every other call — an embedding call is therefore identifiable by call_type even though
    // it shares the default feature="chat" bucket (a separate, pre-existing, out-of-scope-for-this-
    // phase column not touched here).

    @Test
    void embeddingUsageIsTaggedAndCachedTokensDefaultToZero() {
        RecordingUsageService usage = new RecordingUsageService();
        ScriptedClient client = new ScriptedClient("""
                {"data":[{"embedding":[0.1,0.2,0.3]}],
                 "usage":{"prompt_tokens":42,"total_tokens":42}}
                """, usage);

        LlmCallTag.set("MEMORY_EMBEDDING");
        EmbeddingResult result = client.embed("some chunk of document text");

        assertEquals(3, result.embedding().length);
        assertEquals(1, usage.calls.size());
        assertEquals(42, usage.calls.get(0).promptTokens());
        assertEquals(0, usage.calls.get(0).completionTokens(),
                "embeddings responses have no completion_tokens field — must default to 0, not throw");
        assertEquals(0, usage.calls.get(0).cachedTokens(),
                "OpenAI does not report prompt_tokens_details for embeddings — must default to 0");
        assertEquals("MEMORY_EMBEDDING", usage.calls.get(0).callType(),
                "an embedding call must be identifiable by call_type, distinct from chat/reasoning calls");
    }

    @Test
    void untaggedEmbeddingCallStillYieldsTheSafeUntaggedRepresentation() {
        RecordingUsageService usage = new RecordingUsageService();
        ScriptedClient client = new ScriptedClient("""
                {"data":[{"embedding":[0.1]}],
                 "usage":{"prompt_tokens":10,"total_tokens":10}}
                """, usage);

        // No LlmCallTag.set(...) — proves the plumbing itself never invents a classification.
        client.embed("text");

        assertEquals("UNTAGGED", usage.calls.get(0).callType());
    }
}
