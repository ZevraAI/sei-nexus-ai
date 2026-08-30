package com.sei.nexus.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal runtime observability for {@link AzureOpenAiClient#chatWithFileSearch} —
 * {@code FILE_SEARCH_METRIC}. Proves the log line is emitted with the right facts (tool
 * invoked/status/result count, vector store id, model, latency) and that it never logs the
 * question/instructions/retrieved content, using a real Logback {@link ListAppender} attached to
 * the client's own logger (same hand-rolled convention as the rest of this test file's siblings
 * — no Mockito, no network). Also proves {@link LlmCallTag} is correctly cleared afterward,
 * fixing the leak {@code chatWithFileSearch} previously had (it never reached {@code
 * recordUsage}, the only place that consumed/cleared the tag before this change).
 */
class AzureOpenAiClientFileSearchMetricTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger clientLogger;

    @BeforeEach
    void attachAppender() {
        clientLogger = (Logger) LoggerFactory.getLogger(AzureOpenAiClient.class);
        appender = new ListAppender<>();
        appender.start();
        clientLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppenderAndClearTag() {
        clientLogger.detachAppender(appender);
        LlmCallTag.clear();
    }

    static class ScriptedClient extends AzureOpenAiClient {
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
                java.lang.reflect.Field chatModel = AzureOpenAiClient.class.getDeclaredField("chatModel");
                chatModel.setAccessible(true);
                chatModel.set(this, "gpt-4o");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        protected HttpResponse<String> sendHttp(HttpRequest request) {
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

    private String lastMessage() {
        assertFalse(appender.list.isEmpty(), "expected at least one log line");
        return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    }

    // ── 1. Proves invocation + presence/status/result-count facts ───────────────────────────────

    @Test
    void fileSearchCallPresentWithResultsIsReportedAccurately() {
        ScriptedClient client = new ScriptedClient(200, """
                {"output":[
                  {"type":"file_search_call","status":"completed","queries":["purchase order"],
                   "results":[{"file_id":"file-1"},{"file_id":"file-2"}]},
                  {"type":"message","content":[{"type":"output_text","text":"{\\"metadataRequest\\":{\\"conceptKeys\\":[\\"purchase-order\\"]}}"}]}
                ]}
                """);
        LlmCallTag.set("STAGE1_FILE_SEARCH_CONCEPT_SELECTION");

        String result = client.chatWithFileSearch("vs_tenant_x", "instructions", "Show me all purchase orders");

        assertEquals("{\"metadataRequest\":{\"conceptKeys\":[\"purchase-order\"]}}", result,
                "the metric addition must not change the returned extracted text");

        String logged = lastMessage();
        assertTrue(logged.contains("FILE_SEARCH_METRIC"));
        assertTrue(logged.contains("callType=STAGE1_FILE_SEARCH_CONCEPT_SELECTION"));
        assertTrue(logged.contains("vectorStoreId=vs_tenant_x"));
        assertTrue(logged.contains("model=gpt-4o"));
        assertTrue(logged.contains("fileSearchCallPresent=true"));
        assertTrue(logged.contains("status=completed"));
        assertTrue(logged.contains("resultCount=2"));
    }

    @Test
    void fileSearchCallAbsentIsReportedAsAbsentNotAsAFalsePositive() {
        ScriptedClient client = new ScriptedClient(200, """
                {"output":[
                  {"type":"message","content":[{"type":"output_text","text":"{\\"metadataRequest\\":{\\"conceptKeys\\":[]}}"}]}
                ]}
                """);

        client.chatWithFileSearch("vs_tenant_x", "instructions", "unrelated question");

        String logged = lastMessage();
        assertTrue(logged.contains("fileSearchCallPresent=false"));
        assertTrue(logged.contains("status=absent"));
        assertTrue(logged.contains("resultCount=-1"));
    }

    @Test
    void resultsFieldAbsentFromFileSearchCallIsReportedAsUnavailableNotZero() {
        // Production's real requests never set include=file_search_call.results, so `results`
        // is normally absent even when the tool genuinely ran — must not be misreported as "0".
        ScriptedClient client = new ScriptedClient(200, """
                {"output":[
                  {"type":"file_search_call","status":"completed","queries":["q"]},
                  {"type":"message","content":[{"type":"output_text","text":"{}"}]}
                ]}
                """);

        client.chatWithFileSearch("vs_tenant_x", "instructions", "q");

        String logged = lastMessage();
        assertTrue(logged.contains("fileSearchCallPresent=true"));
        assertTrue(logged.contains("status=completed"));
        assertTrue(logged.contains("resultCount=-1"), "no `results` field present ⇒ unavailable (-1), never a false 0");
    }

    // ── 2. Never logs the question, instructions, or retrieved content ──────────────────────────

    @Test
    void theLoggedLineNeverContainsTheQuestionInstructionsOrRetrievedContent() {
        ScriptedClient client = new ScriptedClient(200, """
                {"output":[
                  {"type":"file_search_call","status":"completed",
                   "results":[{"file_id":"file-1","text":"SENSITIVE RETRIEVED DOCUMENT CONTENT"}]},
                  {"type":"message","content":[{"type":"output_text","text":"{\\"metadataRequest\\":{\\"conceptKeys\\":[]}}"}]}
                ]}
                """);

        client.chatWithFileSearch("vs_tenant_x", "SECRET_INSTRUCTIONS_TEXT", "SECRET_USER_QUESTION_TEXT");

        String logged = lastMessage();
        assertFalse(logged.contains("SECRET_INSTRUCTIONS_TEXT"));
        assertFalse(logged.contains("SECRET_USER_QUESTION_TEXT"));
        assertFalse(logged.contains("SENSITIVE RETRIEVED DOCUMENT CONTENT"));
    }

    // ── 3. LlmCallTag is correctly cleared afterward (fixes the pre-existing leak) ──────────────

    @Test
    void llmCallTagIsClearedAfterChatWithFileSearchEvenThoughItNeverReachesRecordUsage() {
        ScriptedClient client = new ScriptedClient(200,
                "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}]}");

        LlmCallTag.set("STAGE1_FILE_SEARCH_CONCEPT_SELECTION");
        client.chatWithFileSearch("vs_tenant_x", "instructions", "q");

        assertEquals("UNTAGGED", LlmCallTag.get(),
                "the tag must be cleared after chatWithFileSearch, exactly like recordUsage clears it "
                        + "for chat/chatWithJson — otherwise it leaks onto the next unrelated call on this thread");
    }

    // ── 4. Malformed response never throws, and still clears the tag ────────────────────────────

    @Test
    void malformedResponseBodyDoesNotThrowAndStillClearsTheTag() {
        ScriptedClient client = new ScriptedClient(200, "not json at all");
        LlmCallTag.set("STAGE1_FILE_SEARCH_CONCEPT_SELECTION");

        String result = assertDoesNotThrow(() -> client.chatWithFileSearch("vs_tenant_x", "instructions", "q"));

        assertEquals("", result);
        assertEquals("UNTAGGED", LlmCallTag.get());
    }

    // ── 5. Existing legacy chat()/chatWithJson() behavior is unaffected ─────────────────────────

    @Test
    void legacyChatWithJsonBehaviorIsUnaffectedByThisChange() {
        ScriptedClient client = new ScriptedClient(200,
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"metadataRequest\\\":{\\\"conceptKeys\\\":[]}}\"}}]}");

        String result = client.chatWithJson(java.util.List.of(ChatMessage.user("q")), "system");

        assertEquals("{\"metadataRequest\":{\"conceptKeys\":[]}}", result);
    }
}
