package com.sei.nexus.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9C — parity check: {@link AzureOpenAiClient#respondWithTools} (the new Responses API
 * transport {@code AgentRunner} now calls) must preserve the exact "only the first tool call in
 * one turn is ever processed" behavior pinned for the old Chat Completions transport by {@link
 * ChatWithToolsMultipleToolCallsBaselineTest} (Phase 9B). The migration is transport-only — this
 * proves the pinned baseline survived it, using a hand-crafted Responses API response body
 * containing TWO {@code function_call} output items (no live OpenAI call, no Mockito).
 */
class RespondWithToolsMultipleFunctionCallsParityTest {

    static class TwoFunctionCallsClient extends AzureOpenAiClient {
        TwoFunctionCallsClient() {
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
            String body = """
                    {
                      "output": [
                        {
                          "type": "function_call",
                          "call_id": "call-FIRST",
                          "name": "describe_schema",
                          "arguments": "{\\"connection_key\\":\\"conn-1\\"}"
                        },
                        {
                          "type": "function_call",
                          "call_id": "call-SECOND",
                          "name": "query_database",
                          "arguments": "{\\"connection_key\\":\\"conn-1\\",\\"sql\\":\\"SELECT 1\\"}"
                        }
                      ],
                      "usage": {"input_tokens": 10, "output_tokens": 5}
                    }
                    """;
            return new FakeHttpResponse(200, body);
        }
    }

    private record FakeHttpResponse(int statusCode, String body) implements HttpResponse<String> {
        @Override public int statusCode() { return statusCode; }
        @Override public String body() { return body; }
        @Override public HttpRequest request() { throw new UnsupportedOperationException(); }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public java.net.URI uri() { throw new UnsupportedOperationException(); }
        @Override public HttpClient.Version version() { throw new UnsupportedOperationException(); }
    }

    @Test
    void respondWithToolsAlsoOnlyProcessesTheFirstOfTwoFunctionCalls() {
        TwoFunctionCallsClient client = new TwoFunctionCallsClient();

        AgentToolResponse response = client.respondWithTools(
                List.of(AgentMessage.user("does this matter")), "system prompt", List.of());

        assertFalse(response.finalAnswer());
        assertEquals("describe_schema", response.toolName(),
                "parity with the pre-migration Chat Completions baseline: only the FIRST "
                        + "function_call output item is ever surfaced");
        assertEquals("call-FIRST", response.toolCallId());
        assertEquals("conn-1", response.args().get("connection_key"));
        assertNotEquals("query_database", response.toolName(),
                "the second function_call must never surface as the chosen tool — this is the "
                        + "SAME pinned baseline, not a new decision made during the migration");
    }
}
