package com.sei.nexus.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.NexusException;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Persistent Tenant Knowledge, Phase 1 — {@link AzureOpenAiClient#createVectorStore}.
 *
 * <p>Same hand-rolled-fakes / {@code sendHttp}-override convention as
 * {@link AzureOpenAiClientThrottleTest} / {@link AzureOpenAiClientMetricsTest} — no Mockito,
 * no network. Never calls real OpenAI and never creates a real Vector Store.
 */
class AzureOpenAiClientVectorStoreTest {

    /** Subclass that returns a scripted HTTP response instead of calling the real network. */
    static class ScriptedClient extends AzureOpenAiClient {
        final AtomicInteger sendCount = new AtomicInteger(0);
        final int statusCode;
        final String scriptedBody;

        ScriptedClient(int statusCode, String scriptedBody) {
            super(new ObjectMapper(), null);
            this.statusCode = statusCode;
            this.scriptedBody = scriptedBody;
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
            return new FakeHttpResponse(statusCode, scriptedBody);
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

    @Test
    void successResponseReturnsTheVectorStoreId() {
        ScriptedClient client = new ScriptedClient(200,
                "{\"id\":\"vs_abc123\",\"object\":\"vector_store\",\"name\":\"zevra-tenant-tenant_acme\"}");

        String id = client.createVectorStore("zevra-tenant-tenant_acme");

        assertEquals("vs_abc123", id);
        assertEquals(1, client.sendCount.get(), "exactly one HTTP call for one createVectorStore invocation");
    }

    @Test
    void responseMissingIdIsSurfacedAsAFailureRatherThanReturningNull() {
        ScriptedClient client = new ScriptedClient(200, "{\"object\":\"vector_store\"}");

        NexusException ex = assertThrows(NexusException.class,
                () -> client.createVectorStore("zevra-tenant-tenant_acme"));
        assertTrue(ex.getMessage().toLowerCase().contains("id"));
    }

    @Test
    void openAiErrorResponseIsSurfacedAsAnObservableFailure() {
        ScriptedClient client = new ScriptedClient(500,
                "{\"error\":{\"type\":\"server_error\",\"message\":\"boom\"}}");

        NexusException ex = assertThrows(NexusException.class,
                () -> client.createVectorStore("zevra-tenant-tenant_acme"));
        assertNotNull(ex.getMessage());
    }

    @Test
    void unparseableResponseBodyIsSurfacedAsAFailureRatherThanThrowingAnUncheckedException() {
        ScriptedClient client = new ScriptedClient(200, "not json at all");

        assertThrows(NexusException.class, () -> client.createVectorStore("zevra-tenant-tenant_acme"));
    }
}
