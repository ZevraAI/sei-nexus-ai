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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Postgres → Vector Store synchronization — {@link AzureOpenAiClient#detachFileFromVectorStore}.
 *
 * <p>This method exists because of a genuine defect this feature's own real-tenant validation
 * caught live against the real OpenAI API: deleting a file object ({@link
 * AzureOpenAiClient#deleteFile}) does NOT remove its entry from a vector store's file list — the
 * stale entry remains listed (now pointing at a nonexistent file) until this vector-store-scoped
 * detach call is made. These tests pin the correct HTTP shape; the real-tenant validation is what
 * proves it actually fixes the listVectorStoreFiles/file_search-visible staleness.
 */
class AzureOpenAiClientDetachFileTest {

    static class ScriptedClient extends AzureOpenAiClient {
        final AtomicInteger sendCount = new AtomicInteger(0);
        final AtomicReference<HttpRequest> lastRequest = new AtomicReference<>();
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
            lastRequest.set(request);
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
    void detachIssuesAnHttpDeleteAgainstTheVectorStoreScopedFilesEndpoint() {
        ScriptedClient client = new ScriptedClient(200,
                "{\"id\":\"file_abc\",\"object\":\"vector_store.file.deleted\",\"deleted\":true}");

        client.detachFileFromVectorStore("vs_123", "file_abc");

        assertEquals(1, client.sendCount.get());
        assertEquals("DELETE", client.lastRequest.get().method());
        assertTrue(client.lastRequest.get().uri().toString().endsWith("/vector_stores/vs_123/files/file_abc"),
                "must hit the vector-store-scoped endpoint, not the bare /files/{id} endpoint");
    }

    @Test
    void detachingAnAlreadyDetachedFileIsTreatedAsSuccessNotFailure() {
        ScriptedClient client = new ScriptedClient(404,
                "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"No such vector store file\"}}");

        client.detachFileFromVectorStore("vs_123", "file_gone");
    }

    @Test
    void aGenuineOpenAiFailureOtherThan404StillSurfacesAsAnException() {
        ScriptedClient client = new ScriptedClient(500,
                "{\"error\":{\"type\":\"server_error\",\"message\":\"boom\"}}");

        assertThrows(NexusException.class, () -> client.detachFileFromVectorStore("vs_123", "file_abc"));
    }
}
