package com.sei.nexus.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.usage.UsageService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AzureOpenAiClient {

    private static final int MAX_RETRIES = 4;
    private static final long INITIAL_BACKOFF_MS    = 1_000L;   // for general errors
    private static final long RATE_LIMIT_BACKOFF_MS = 20_000L;  // 20s first wait on 429

    private final HttpClient   httpClient;
    private final ObjectMapper objectMapper;
    private final UsageService usageService;

    private static final String BASE_URL = "https://api.openai.com/v1";

    @Value("${nexus.openai.api-key:}")
    private String apiKey;

    @Value("${nexus.openai.chat-model:gpt-4o}")
    private String chatModel;

    @Value("${nexus.openai.routing-model:gpt-4o-mini}")
    private String routingModel;

    @Value("${nexus.openai.embedding-model:text-embedding-ada-002}")
    private String embeddingModel;

    // Global backpressure valve: this one client instance is shared by EVERY
    // tenant (chat, onboarding, packs, semantic learning) — with no cap, one
    // tenant's burst (e.g. onboarding 15 tables at once) can rate-limit-storm
    // every other tenant's calls too. Caps concurrency, not rate — a token-
    // bucket limiter would be the follow-up if 429s persist after this.
    @Value("${nexus.openai.max-concurrent-calls:6}")
    private int maxConcurrentCalls;

    private Semaphore globalCallLimit;

    // Adaptive rate awareness: OpenAI returns x-ratelimit-remaining-requests /
    // x-ratelimit-reset-requests on EVERY response — success or 429, not just
    // failures. Tracking this means the client paces itself proactively before
    // ever hitting a 429, and self-adapts to whatever the account's real tier
    // is instead of a guessed static number. Updated from every response;
    // consulted before every subsequent attempt. Deliberately eventually-
    // consistent (no locking beyond the AtomicReference) — worst case a couple
    // of calls race past a slightly-stale reading, which the existing 429
    // retry path already handles.
    private static final Pattern RATELIMIT_DURATION =
            Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+(?:\\.\\d+)?)s)?");

    private record RateState(int remainingRequests, Instant resetAt) {
        static RateState unknown() { return new RateState(Integer.MAX_VALUE, Instant.EPOCH); }
    }

    private final AtomicReference<RateState> rateState = new AtomicReference<>(RateState.unknown());

    public AzureOpenAiClient(ObjectMapper objectMapper, UsageService usageService) {
        this.objectMapper  = objectMapper;
        this.usageService  = usageService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @PostConstruct
    void initThrottle() {
        this.globalCallLimit = new Semaphore(Math.max(1, maxConcurrentCalls), true);
    }

    /**
     * Embeds the given text using Azure OpenAI embeddings deployment.
     * Returns a float[] of the embedding vector.
     */
    public EmbeddingResult embed(String text) {
        String url = BASE_URL + "/embeddings";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", embeddingModel);
        requestBody.put("input", text);

        String responseBody = executeWithRetry(url, requestBody);

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddingArray = root.path("data").get(0).path("embedding");
            float[] embedding = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                embedding[i] = (float) embeddingArray.get(i).asDouble();
            }
            int tokenCount = root.path("usage").path("total_tokens").asInt(0);
            return new EmbeddingResult(embedding, tokenCount);
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse embedding response: " + e.getMessage());
        }
    }

    /**
     * Sends a chat completion request and returns the assistant's text response.
     */
    public String chat(List<ChatMessage> messages, String systemPrompt) {
        return doChat(messages, systemPrompt, false, chatModel);
    }

    /**
     * Analyses an image using GPT-4o vision and returns a structured description.
     * The model receives both the question and the base64-encoded image.
     *
     * <p>Works for any image type: receipts, invoices, charts, screenshots,
     * photos, scanned documents — the model describes what it sees in detail.
     *
     * @param question    what the user wants to know about the image
     * @param base64Image base64-encoded image bytes (no data URI prefix)
     * @param mimeType    e.g. "image/jpeg", "image/png", "image/webp"
     * @param systemPrompt additional instructions for the model
     * @return the model's analysis as plain text
     */
    public String analyzeImage(String question, String base64Image,
                                String mimeType, String systemPrompt) {
        String url = BASE_URL + "/chat/completions";

        // Build multimodal content: text + image
        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text",
                question != null && !question.isBlank() ? question
                        : "Describe everything you see in this image in detail."));
        userContent.add(Map.of(
                "type", "image_url",
                "image_url", Map.of(
                        "url",    "data:" + mimeType + ";base64," + base64Image,
                        "detail", "high")));

        List<Map<String, Object>> messageList = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messageList.add(Map.of("role", "system", "content", systemPrompt));
        }
        messageList.add(Map.of("role", "user", "content", userContent));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", chatModel);   // gpt-4o already supports vision
        requestBody.put("messages", messageList);
        requestBody.put("max_tokens", 4096);
        requestBody.put("temperature", 0.1);   // low temp for accurate extraction

        String responseBody = executeWithRetry(url, requestBody);
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse vision response: " + e.getMessage());
        }
    }

    /**
     * Sends a chat completion request with JSON response format enabled.
     * Returns the assistant's content as a raw JSON string.
     */
    public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
        return doChat(messages, systemPrompt, true, chatModel);
    }

    /**
     * Lightweight JSON call using the routing model (gpt-4o-mini by default).
     * Use for routing/classification tasks — same accuracy for simple decisions,
     * ~16x cheaper than gpt-4o.
     */
    public String chatWithJsonFast(List<ChatMessage> messages, String systemPrompt) {
        return doChat(messages, systemPrompt, true, routingModel);
    }

    /**
     * Sends a tool-calling request (OpenAI function calling).
     * Returns either a tool call the LLM wants to make, or a final text answer.
     *
     * @param messages    conversation history (user + assistant + tool results)
     * @param systemPrompt agent persona and goal
     * @param tools       list of tool definitions in OpenAI format
     */
    public AgentToolResponse chatWithTools(List<AgentMessage> messages,
                                            String systemPrompt,
                                            List<Map<String, Object>> tools) {
        String url = BASE_URL + "/chat/completions";

        List<Map<String, Object>> messageList = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messageList.add(Map.of("role", "system", "content", systemPrompt));
        }
        for (AgentMessage msg : messages) {
            Map<String, Object> m = new HashMap<>();
            m.put("role", msg.role());
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                m.put("tool_calls", msg.toolCalls());
            } else {
                m.put("content", msg.content() != null ? msg.content() : "");
            }
            if (msg.toolCallId() != null) {
                m.put("tool_call_id", msg.toolCallId());
            }
            messageList.add(m);
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", chatModel);
        requestBody.put("messages", messageList);
        requestBody.put("tools", tools);
        requestBody.put("tool_choice", "auto");
        requestBody.put("temperature", 0.2);
        requestBody.put("max_tokens", 4096);

        String responseBody = executeWithRetry(url, requestBody);

        try {
            JsonNode root    = objectMapper.readTree(responseBody);
            recordUsage(root, chatModel);
            JsonNode message = root.path("choices").get(0).path("message");
            JsonNode toolCalls = message.path("tool_calls");

            if (toolCalls.isArray() && toolCalls.size() > 0) {
                JsonNode call     = toolCalls.get(0);
                String toolCallId = call.path("id").asText();
                String toolName   = call.path("function").path("name").asText();
                String argsJson   = call.path("function").path("arguments").asText();

                @SuppressWarnings("unchecked")
                Map<String, Object> args = objectMapper.readValue(argsJson, Map.class);
                return AgentToolResponse.ofToolCall(toolName, toolCallId, args);
            }

            // No tool call — treat content as final answer
            String content = message.path("content").asText();
            return AgentToolResponse.ofFinal(content);

        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse tool-call response: " + e.getMessage());
        }
    }

    private String doChat(List<ChatMessage> messages, String systemPrompt,
                          boolean jsonMode, String model) {
        String url = BASE_URL + "/chat/completions";

        List<Map<String, String>> messageList = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            Map<String, String> sysMsg = new HashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messageList.add(sysMsg);
        }
        for (ChatMessage msg : messages) {
            Map<String, String> m = new HashMap<>();
            m.put("role", msg.role());
            m.put("content", msg.content());
            messageList.add(m);
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messageList);
        requestBody.put("temperature", 0.2);
        requestBody.put("max_tokens", 4096);

        if (jsonMode) {
            Map<String, String> responseFormat = new HashMap<>();
            responseFormat.put("type", "json_object");
            requestBody.put("response_format", responseFormat);
        }

        String responseBody = executeWithRetry(url, requestBody);

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            recordUsage(root, model);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse chat response: " + e.getMessage());
        }
    }

    /**
     * Opt-in diagnostic: dumps the exact transmitted request body to a file when
     * {@code -Dnexus.capture.payload.dir} is set. Used to verify precisely what text reaches
     * Azure OpenAI. When {@code -Dnexus.capture.abortBeforeSend=true} it throws right after
     * writing, so a capture harness never actually transmits the payload over the network.
     * Both flags are unset in production, so this is a pure no-op there.
     */
    private void capturePayload(String url, String jsonBody) {
        String dir = System.getProperty("nexus.capture.payload.dir");
        if (dir == null || dir.isBlank()) return;
        try {
            java.nio.file.Path out = java.nio.file.Path.of(dir,
                    "openai-request-" + System.nanoTime() + ".json");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, jsonBody);
            System.out.println("[capturePayload] url=" + url + " bytes=" + jsonBody.length()
                    + " -> " + out);
        } catch (Exception e) {
            System.out.println("[capturePayload] failed: " + e.getMessage());
        }
        if (Boolean.getBoolean("nexus.capture.abortBeforeSend")) {
            throw new NexusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "capture: payload written, send aborted by nexus.capture.abortBeforeSend");
        }
    }

    /** Extracts token counts from an OpenAI response and records them for billing. */
    private void recordUsage(JsonNode root, String model) {
        try {
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                int prompt     = usage.path("prompt_tokens").asInt(0);
                int completion = usage.path("completion_tokens").asInt(0);
                usageService.record(model, prompt, completion);
            }
        } catch (Exception ignored) {
            // Usage tracking is non-fatal — never break the main flow
        }
    }

    private String executeWithRetry(String url, Map<String, Object> requestBody) {
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize OpenAI request: " + e.getMessage());
        }

        // Diagnostic capture (opt-in): when -Dnexus.capture.payload.dir is set, write the exact
        // serialized request body — byte-for-byte what BodyPublishers.ofString(jsonBody) transmits
        // below — to a file, immediately before the HTTP send. The body carries no credentials
        // (the API key is an Authorization header, never in the payload). No-op in normal runs.
        capturePayload(url, jsonBody);

        // Global throttle: acquired before the retry loop begins, released only after it
        // finally returns or throws — must wrap the whole retry+backoff sequence, not just
        // the HTTP send, or a 429-storm still lets unlimited threads pile up mid-backoff,
        // defeating the point of a concurrency cap.
        try {
            globalCallLimit.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "OpenAI call interrupted while waiting for capacity");
        }
        try {
            return executeWithRetryLocked(url, jsonBody);
        } finally {
            globalCallLimit.release();
        }
    }

    private String executeWithRetryLocked(String url, String jsonBody) {
        long backoffMs = INITIAL_BACKOFF_MS;
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            // Proactive pacing: if the last response told us we're essentially out of
            // budget for this window, wait for the provider's own reset time instead of
            // racing in and guaranteeing a 429. A stale/unknown reading is a no-op.
            waitForRateBudget();

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .timeout(Duration.ofSeconds(60))
                        .build();

                HttpResponse<String> response = sendHttp(request);
                updateRateState(response);

                int statusCode = response.statusCode();

                if (statusCode == 200) {
                    return response.body();
                }

                if (statusCode == 429) {
                    if (attempt < MAX_RETRIES - 1) {
                        Duration wait = retryWaitFor(response);
                        if (wait == null) {
                            // Neither Retry-After nor a rate-limit-reset header was usable —
                            // fall back to the blind exponential ladder (20s → 40s → 80s).
                            wait = Duration.ofMillis(RATE_LIMIT_BACKOFF_MS * (1L << attempt));
                        }
                        Thread.sleep(wait.toMillis());
                        continue;
                    }
                    // Surface OpenAI's own reason (RPM vs TPM vs an account/project quota
                    // cap look identical at the HTTP-status level but read very differently
                    // in the error body) — previously discarded here, so every occurrence
                    // logged as an undifferentiated "rate limit exceeded" with no way to
                    // tell which budget was actually exhausted without dashboard access.
                    throw new NexusException(HttpStatus.TOO_MANY_REQUESTS,
                            "OpenAI rate limit exceeded after " + MAX_RETRIES + " retries: "
                                    + summarizeErrorBody(response.body()));
                }

                String errorBody = response.body();
                throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "OpenAI call failed: HTTP " + statusCode + " - " + errorBody);

            } catch (NexusException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "OpenAI call interrupted");
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(backoffMs);
                        backoffMs *= 2;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                "OpenAI call interrupted");
                    }
                }
            }
        }

        String reason = lastException != null ? lastException.getMessage() : "unknown error";
        throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "OpenAI call failed: " + reason);
    }

    /**
     * Extracts OpenAI's own {@code error.type}/{@code error.code}/{@code error.message}
     * from a 429 response body — this is what actually distinguishes "RPM exhausted",
     * "TPM exhausted", and "insufficient_quota" (an account/project spend cap, not a
     * transient rate window — retrying won't help), which are indistinguishable from
     * the HTTP status code alone. No secrets in this body — it's OpenAI's own error
     * description, never request/customer content. Falls back to a truncated raw body
     * if it isn't the expected shape, so a format change never hides the error entirely.
     */
    private String summarizeErrorBody(String body) {
        if (body == null || body.isBlank()) return "(no response body)";
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            if (!error.isMissingNode()) {
                String type    = error.path("type").asText(null);
                String code    = error.path("code").asText(null);
                String message = error.path("message").asText(null);
                StringBuilder sb = new StringBuilder();
                if (type != null)    sb.append("type=").append(type).append(' ');
                if (code != null)    sb.append("code=").append(code).append(' ');
                if (message != null) sb.append(message);
                if (sb.length() > 0) return sb.toString().trim();
            }
        } catch (Exception ignored) {
            // Not the expected shape — fall through to the raw-body fallback below.
        }
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    /** Overridable seam for tests — the real implementation just delegates to the JDK client. */
    protected HttpResponse<String> sendHttp(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ── Adaptive rate awareness ──────────────────────────────────────────────

    /** Blocks until the previously-observed rate window resets, if we're known to be exhausted. */
    private void waitForRateBudget() {
        RateState state = rateState.get();
        if (state.remainingRequests() > 1) return; // healthy budget, or unknown — don't block
        Duration until = Duration.between(Instant.now(), state.resetAt());
        if (until.isNegative() || until.isZero()) return; // reset already passed
        try {
            Thread.sleep(until.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Updates the shared rate state from whatever headers this response actually carries. */
    private void updateRateState(HttpResponse<String> response) {
        Integer remaining = firstHeaderInt(response, "x-ratelimit-remaining-requests");
        Duration resetIn  = firstHeaderDuration(response, "x-ratelimit-reset-requests");
        if (remaining == null && resetIn == null) return; // nothing usable — leave prior state
        int remainingValue = remaining != null ? remaining : rateState.get().remainingRequests();
        Instant resetAt    = resetIn != null ? Instant.now().plus(resetIn) : rateState.get().resetAt();
        rateState.set(new RateState(remainingValue, resetAt));
    }

    /** How long to wait before retrying a 429 — Retry-After first, then the rate-limit reset header. */
    private Duration retryWaitFor(HttpResponse<String> response) {
        Integer retryAfterSeconds = firstHeaderInt(response, "retry-after");
        if (retryAfterSeconds != null) return Duration.ofSeconds(retryAfterSeconds);
        return firstHeaderDuration(response, "x-ratelimit-reset-requests");
    }

    private Integer firstHeaderInt(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name)
                .map(v -> { try { return Integer.parseInt(v.trim()); } catch (Exception e) { return null; } })
                .orElse(null);
    }

    /** Parses OpenAI's rate-limit-reset duration format, e.g. "6m0s", "21s", "350ms". */
    private Duration firstHeaderDuration(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name)
                .map(this::parseRateLimitDuration)
                .orElse(null);
    }

    private Duration parseRateLimitDuration(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Matcher m = RATELIMIT_DURATION.matcher(value.trim());
            if (!m.matches()) return null;
            long hours   = m.group(1) != null ? Long.parseLong(m.group(1)) : 0;
            long minutes = m.group(2) != null ? Long.parseLong(m.group(2)) : 0;
            double secs  = m.group(3) != null ? Double.parseDouble(m.group(3)) : 0;
            Duration d = Duration.ofHours(hours).plusMinutes(minutes)
                    .plusMillis((long) (secs * 1000));
            return d.isZero() ? null : d;
        } catch (Exception e) {
            return null;
        }
    }
}
