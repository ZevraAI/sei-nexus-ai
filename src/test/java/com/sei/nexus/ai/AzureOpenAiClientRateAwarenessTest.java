package com.sei.nexus.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.NexusException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Foundation hardening — adaptive rate awareness. OpenAI returns
 * {@code x-ratelimit-remaining-requests}/{@code x-ratelimit-reset-requests} on
 * EVERY response, success or 429 — reading and acting on them means the client
 * paces itself before ever hitting a 429, and honors the provider's own
 * {@code Retry-After}/reset duration on a 429 instead of the blind 20s/40s/80s
 * ladder. That blind ladder remains only as the fallback when neither header
 * is present/parseable.
 *
 * <p>Decision-logic (header parsing, which wait to prefer) is tested directly
 * via reflection — fast, no real waiting. The proactive-pacing and
 * honor-Retry-After behaviors are also proven end-to-end through the real
 * {@code chat()} call path with short (1-2s) real waits — the blind-backoff
 * fallback itself is NOT re-exercised end-to-end here since its real constants
 * (20s/40s/80s) would make the test unacceptably slow; {@link AzureOpenAiClientThrottleTest}
 * already covers the concurrency-cap dimension this test doesn't touch.
 */
class AzureOpenAiClientRateAwarenessTest {

    private static Object invokePrivate(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = AzureOpenAiClient.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static final String CHAT_BODY = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

    private static FakeHttpResponse response(int status, Map<String, List<String>> headers) {
        String body = status == 200 ? CHAT_BODY : "{}";
        return new FakeHttpResponse(status, body, headers);
    }

    // ── header-parsing decision logic (fast, reflection-based) ────────────────

    @Test
    void retryWaitForPrefersRetryAfterOverResetHeader() throws Exception {
        AzureOpenAiClient client = new AzureOpenAiClient(new ObjectMapper(), null);
        FakeHttpResponse resp = response(429, Map.of(
                "retry-after", List.of("5"),
                "x-ratelimit-reset-requests", List.of("30s")));

        Duration wait = (Duration) invokePrivate(client, "retryWaitFor",
                new Class[]{HttpResponse.class}, resp);

        assertEquals(Duration.ofSeconds(5), wait, "Retry-After must win over the rate-limit reset header");
    }

    @Test
    void retryWaitForFallsBackToResetHeaderWhenNoRetryAfter() throws Exception {
        AzureOpenAiClient client = new AzureOpenAiClient(new ObjectMapper(), null);
        FakeHttpResponse resp = response(429, Map.of("x-ratelimit-reset-requests", List.of("6m0s")));

        Duration wait = (Duration) invokePrivate(client, "retryWaitFor",
                new Class[]{HttpResponse.class}, resp);

        assertEquals(Duration.ofMinutes(6), wait);
    }

    @Test
    void retryWaitForReturnsNullWhenNeitherHeaderPresent() throws Exception {
        AzureOpenAiClient client = new AzureOpenAiClient(new ObjectMapper(), null);
        FakeHttpResponse resp = response(429, Map.of());

        Duration wait = (Duration) invokePrivate(client, "retryWaitFor",
                new Class[]{HttpResponse.class}, resp);

        assertNull(wait, "null signals the caller to fall back to the blind exponential ladder");
    }

    @Test
    void parseRateLimitDurationHandlesOpenAiFormats() throws Exception {
        AzureOpenAiClient client = new AzureOpenAiClient(new ObjectMapper(), null);

        assertEquals(Duration.ofSeconds(21),
                invokePrivate(client, "parseRateLimitDuration", new Class[]{String.class}, "21s"));
        assertEquals(Duration.ofMinutes(6),
                invokePrivate(client, "parseRateLimitDuration", new Class[]{String.class}, "6m0s"));
        assertEquals(Duration.ofHours(1).plusMinutes(2).plusSeconds(3),
                invokePrivate(client, "parseRateLimitDuration", new Class[]{String.class}, "1h2m3s"));
        assertNull(invokePrivate(client, "parseRateLimitDuration", new Class[]{String.class}, ""));
        assertNull(invokePrivate(client, "parseRateLimitDuration", new Class[]{String.class}, "garbage"));
        assertNull(invokePrivate(client, "parseRateLimitDuration", new Class[]{String.class}, (Object) null));
    }

    // ── end-to-end: proactive pacing before hitting a 429 ──────────────────────

    /** Subclass overriding the HTTP seam so headers/status are fully controllable per call. */
    static class ScriptedClient extends AzureOpenAiClient {
        final AtomicInteger callCount = new AtomicInteger(0);
        final List<FakeHttpResponse> script;
        final List<Instant> callTimestamps = new java.util.concurrent.CopyOnWriteArrayList<>();

        ScriptedClient(List<FakeHttpResponse> script) throws Exception {
            super(new ObjectMapper(), null);
            this.script = script;
            // @PostConstruct doesn't fire outside a Spring container — initialize the
            // global-throttle Semaphore manually, same as AzureOpenAiClientThrottleTest does.
            Method init = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
            init.setAccessible(true);
            init.invoke(this);
        }

        @Override
        protected HttpResponse<String> sendHttp(HttpRequest request) {
            callTimestamps.add(Instant.now());
            int i = callCount.getAndIncrement();
            return script.get(Math.min(i, script.size() - 1));
        }
    }

    @Test
    void proactivelyWaitsForResetBeforeTheNextCallWhenBudgetIsExhausted() throws Exception {
        FakeHttpResponse exhausted = response(200, Map.of(
                "x-ratelimit-remaining-requests", List.of("0"),
                "x-ratelimit-reset-requests", List.of("1s")));
        FakeHttpResponse healthy = response(200, Map.of("x-ratelimit-remaining-requests", List.of("50")));

        ScriptedClient client = new ScriptedClient(List.of(exhausted, healthy));

        client.chat(List.of(ChatMessage.user("hi")), "system"); // consumes budget to 0, reset in 1s
        Instant beforeSecondCall = Instant.now();
        client.chat(List.of(ChatMessage.user("hi")), "system"); // must wait ~1s before its sendHttp fires

        Instant secondSendHttpAt = client.callTimestamps.get(1);
        Duration waited = Duration.between(beforeSecondCall, secondSendHttpAt);
        assertTrue(waited.toMillis() >= 800,
                "expected the second call to wait ~1s for the reset, actually waited " + waited.toMillis() + "ms");
    }

    // ── end-to-end: honoring Retry-After on an actual 429 ──────────────────────

    @Test
    void honorsRetryAfterOnA429InsteadOfTheBlindBackoffLadder() throws Exception {
        FakeHttpResponse rateLimited = response(429, Map.of("retry-after", List.of("1")));
        FakeHttpResponse success = response(200, Map.of());

        ScriptedClient client = new ScriptedClient(List.of(rateLimited, success));

        Instant start = Instant.now();
        String result = client.chat(List.of(ChatMessage.user("hi")), "system");
        Duration elapsed = Duration.between(start, Instant.now());

        assertNotNull(result);
        assertEquals(2, client.callCount.get(), "one 429 then one success — exactly two attempts");
        // Retry-After: 1 means ~1s, not the blind ladder's 20s first wait.
        assertTrue(elapsed.toMillis() < 5000,
                "honoring Retry-After:1 should take ~1s, not the blind ladder's 20s — took " + elapsed.toMillis() + "ms");
        assertTrue(elapsed.toMillis() >= 800,
                "should still have actually waited close to the requested 1s, not skipped it — took " + elapsed.toMillis() + "ms");
    }

    // ── end-to-end: OpenAI's own error reason survives retry exhaustion ───────

    @Test
    void exhaustedRetriesSurfaceOpenAisOwnErrorReasonNotAGenericMessage() throws Exception {
        // RPM exhaustion, TPM exhaustion, and an account/project quota cap all
        // return HTTP 429 — indistinguishable without the error body. Every
        // retry here fails with a distinct OpenAI-shaped body ("insufficient_quota"
        // is not a transient rate window at all — retrying it can never help)
        // to prove that body is surfaced, not discarded.
        String quotaBody = "{\"error\":{\"message\":\"You exceeded your current quota, please check your plan and billing details.\","
                + "\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}";
        FakeHttpResponse rateLimited = new FakeHttpResponse(429, quotaBody,
                Map.of("retry-after", List.of("1")));
        ScriptedClient client = new ScriptedClient(List.of(rateLimited)); // every attempt fails the same way

        NexusException ex = assertThrows(NexusException.class,
                () -> client.chat(List.of(ChatMessage.user("hi")), "system"));

        assertEquals(4, client.callCount.get(), "all 4 retries were exhausted");
        assertTrue(ex.getMessage().contains("insufficient_quota"),
                "the real OpenAI reason must survive into the thrown exception: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("exceeded your current quota"),
                "the human-readable reason must also survive: " + ex.getMessage());
    }

    /** Controllable HttpResponse<String> stub — status, body, and headers all settable. */
    private record FakeHttpResponse(int statusCode, String body,
                                     Map<String, List<String>> headerMap) implements HttpResponse<String> {
        @Override public int statusCode() { return statusCode; }
        @Override public String body() { return body; }
        @Override public HttpRequest request() { throw new UnsupportedOperationException(); }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(headerMap, (a, b) -> true); }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { throw new UnsupportedOperationException(); }
        @Override public HttpClient.Version version() { throw new UnsupportedOperationException(); }
    }
}
