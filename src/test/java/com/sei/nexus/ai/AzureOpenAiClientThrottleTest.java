package com.sei.nexus.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Foundation hardening — global AI-call throttle. Proves the {@code Semaphore}
 * added around {@code executeWithRetry} actually caps concurrency app-wide,
 * without touching a real AI provider or the network: {@link #sendHttp} is
 * overridden to block on a controllable gate while tracking live-call counts.
 *
 * <p>No DB, no HTTP — pure {@code java.util.concurrent}, unlike the COALESCE/
 * jsonb_set cases elsewhere in this codebase which genuinely need a real
 * Postgres engine. Semaphore semantics have no such dependency.
 */
class AzureOpenAiClientThrottleTest {

    /** Subclass overriding the HTTP seam to simulate slow, concurrent calls. */
    static class ThrottleProbeClient extends AzureOpenAiClient {
        final AtomicInteger liveCalls = new AtomicInteger(0);
        final AtomicInteger peakCalls = new AtomicInteger(0);
        final CountDownLatch releaseGate;

        ThrottleProbeClient(CountDownLatch releaseGate) {
            super(new ObjectMapper(), null);
            this.releaseGate = releaseGate;
        }

        @Override
        protected HttpResponse<String> sendHttp(HttpRequest request) throws Exception {
            int live = liveCalls.incrementAndGet();
            peakCalls.updateAndGet(prev -> Math.max(prev, live));
            try {
                releaseGate.await(10, TimeUnit.SECONDS);
            } finally {
                liveCalls.decrementAndGet();
            }
            return new FakeHttpResponse(200,
                    "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        }
    }

    private static void setLimit(AzureOpenAiClient client, int limit) throws Exception {
        Field field = AzureOpenAiClient.class.getDeclaredField("maxConcurrentCalls");
        field.setAccessible(true);
        field.set(client, limit);
        java.lang.reflect.Method m = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        m.setAccessible(true);
        m.invoke(client);
    }

    @Test
    void peakConcurrencyNeverExceedsConfiguredLimit() throws Exception {
        int limit = 4;
        int callers = 10;
        CountDownLatch releaseGate = new CountDownLatch(1);
        ThrottleProbeClient client = new ThrottleProbeClient(releaseGate);
        setLimit(client, limit);

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            futures.add(pool.submit(() ->
                    client.chat(List.of(ChatMessage.user("hi")), "system")));
        }

        // Give every caller a chance to reach the semaphore/HTTP seam before releasing.
        Thread.sleep(500);
        assertTrue(client.peakCalls.get() <= limit,
                "peak concurrent calls (" + client.peakCalls.get()
                        + ") must never exceed the configured limit (" + limit + ")");
        assertTrue(client.peakCalls.get() > 0, "sanity: at least one call should have started");

        releaseGate.countDown();
        for (var f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(limit, client.peakCalls.get(),
                "with 10 callers and a limit of 4, peak concurrency should reach exactly the limit");
    }

    @Test
    void excessCallersQueueRatherThanFail() throws Exception {
        int limit = 2;
        CountDownLatch releaseGate = new CountDownLatch(1);
        ThrottleProbeClient client = new ThrottleProbeClient(releaseGate);
        setLimit(client, limit);

        ExecutorService pool = Executors.newFixedThreadPool(5);
        AtomicInteger succeeded = new AtomicInteger(0);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            futures.add(pool.submit(() -> {
                client.chat(List.of(ChatMessage.user("hi")), "system");
                succeeded.incrementAndGet();
            }));
        }

        Thread.sleep(300);
        releaseGate.countDown();
        for (var f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(5, succeeded.get(), "every caller eventually completes — blocked, not rejected/dropped");
    }

    /** Minimal HttpResponse<String> stub — only statusCode()/body() are exercised by executeWithRetryLocked. */
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
}
