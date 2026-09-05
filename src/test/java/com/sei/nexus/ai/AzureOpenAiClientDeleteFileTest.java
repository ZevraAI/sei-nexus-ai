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
 * Postgres → Vector Store synchronization — {@link AzureOpenAiClient#deleteFile}, the delete
 * capability that did not exist before this feature. Same hand-rolled-fakes/{@code sendHttp}
 * -override convention as {@link AzureOpenAiClientVectorStoreTest} — no Mockito, no network,
 * never calls real OpenAI.
 */
class AzureOpenAiClientDeleteFileTest {

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
    void deleteFileIssuesAnHttpDeleteAgainstTheFilesEndpoint() {
        ScriptedClient client = new ScriptedClient(200, "{\"id\":\"file_abc\",\"deleted\":true}");

        client.deleteFile("file_abc");

        assertEquals(1, client.sendCount.get());
        assertEquals("DELETE", client.lastRequest.get().method());
        assertTrue(client.lastRequest.get().uri().toString().endsWith("/files/file_abc"));
    }

    @Test
    void deletingAnAlreadyMissingFileIsTreatedAsSuccessNotFailure() {
        ScriptedClient client = new ScriptedClient(404,
                "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"No such File object: file_gone\"}}");

        // Must not throw — idempotent delete.
        client.deleteFile("file_gone");
    }

    @Test
    void aGenuineOpenAiFailureOtherThan404StillSurfacesAsAnException() {
        ScriptedClient client = new ScriptedClient(500,
                "{\"error\":{\"type\":\"server_error\",\"message\":\"boom\"}}");

        assertThrows(NexusException.class, () -> client.deleteFile("file_abc"));
    }
}
