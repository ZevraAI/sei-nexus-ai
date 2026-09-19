package com.sei.nexus.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.NexusException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 controlled core model migration — proves NO fallback from gpt-4.1 to gpt-4o exists for
 * the three migrated calls. {@link AzureOpenAiClient} has no model-fallback branch anywhere (confirmed
 * by reading the whole class this phase) — this test proves that behaviorally: a persistently
 * failing OpenAI response (a) still propagates as the existing {@link NexusException} failure
 * (never silently swallowed or retried with a different model) and (b) every retry attempt the
 * generic 5xx retry loop makes still requests the SAME model — never substitutes {@code
 * chatModel} for the configured {@code plannerModel}/{@code evaluatorModel}/{@code composerModel},
 * or vice versa.
 */
class ModelSelectionNoFallbackTest {

    static class RecordingFailingClient extends AzureOpenAiClient {
        final List<String> requestedModels = new ArrayList<>();
        final AtomicInteger sendCount = new AtomicInteger(0);

        RecordingFailingClient() {
            super(new ObjectMapper(), null);
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
            String body = request.bodyPublisher()
                    .map(p -> {
                        var sb = new StringBuilder();
                        p.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                            public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                            public void onNext(java.nio.ByteBuffer item) {
                                byte[] bytes = new byte[item.remaining()];
                                item.get(bytes);
                                sb.append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                            }
                            public void onError(Throwable throwable) { }
                            public void onComplete() { }
                        });
                        return sb.toString();
                    }).orElse("");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"model\":\"([^\"]+)\"").matcher(body);
            if (m.find()) requestedModels.add(m.group(1));
            return new FakeHttpResponse(500, "{\"error\":{\"message\":\"simulated persistent failure\"}}");
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

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = AzureOpenAiClient.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void plannerFailureIsNotSilentlySwitchedToGpt4o() throws Exception {
        RecordingFailingClient client = new RecordingFailingClient();
        setField(client, "apiKey", "unused");
        setField(client, "chatModel", "gpt-4o");
        setField(client, "plannerModel", "gpt-4.1");

        NexusException thrown = assertThrows(NexusException.class,
                () -> client.respondForPlanner(List.of(ChatMessage.user("q")), "system"));
        assertTrue(thrown.getMessage().contains("simulated persistent failure")
                        || thrown.getMessage().toLowerCase().contains("openai"),
                "the existing failure must propagate, not be masked: " + thrown.getMessage());

        assertTrue(client.sendCount.get() >= 1, "at least one real HTTP attempt must have been made");
        assertFalse(client.requestedModels.isEmpty(), "must have observed the model field on at least one attempt");
        for (String model : client.requestedModels) {
            assertEquals("gpt-4.1", model,
                    "every retry attempt must request the SAME configured model — never fall back to gpt-4o");
        }
    }

    @Test
    void evaluatorFailureIsNotSilentlySwitchedToGpt4o() throws Exception {
        RecordingFailingClient client = new RecordingFailingClient();
        setField(client, "apiKey", "unused");
        setField(client, "chatModel", "gpt-4o");
        setField(client, "evaluatorModel", "gpt-4.1");

        assertThrows(NexusException.class,
                () -> client.respondForEvaluator(List.of(ChatMessage.user("q")), "system"));

        assertFalse(client.requestedModels.isEmpty());
        for (String model : client.requestedModels) {
            assertEquals("gpt-4.1", model, "no silent fallback to gpt-4o for Evaluator");
        }
    }

    @Test
    void composerFailureIsNotSilentlySwitchedToGpt4o() throws Exception {
        RecordingFailingClient client = new RecordingFailingClient();
        setField(client, "apiKey", "unused");
        setField(client, "chatModel", "gpt-4o");
        setField(client, "composerModel", "gpt-4.1");

        assertThrows(NexusException.class,
                () -> client.respondForComposer(List.of(ChatMessage.user("q")), "system"));

        assertFalse(client.requestedModels.isEmpty());
        for (String model : client.requestedModels) {
            assertEquals("gpt-4.1", model, "no silent fallback to gpt-4o for Composer");
        }
    }
}
