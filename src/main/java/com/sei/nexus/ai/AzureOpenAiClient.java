package com.sei.nexus.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.usage.UsageService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiClient.class);

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

    // Model tiering (pre-production cost optimization, see application.yml): each low-complexity
    // candidate gets its own configurable model, independent of chatModel and of each other, so
    // one can be tuned/reverted without touching the rest. Defaults to gpt-4o-mini — the intended
    // optimization — but each is a plain @Value like chatModel/routingModel above, so any one of
    // them can be pinned back to gpt-4o via its own env var without a code change.
    @Value("${nexus.openai.term-extractor-model:gpt-4o-mini}")
    private String termExtractorModel;

    // Live model-tiering evaluation (TermExtractorAndCorrectionDetectorModelTieringLiveValidation)
    // found gpt-4o-mini unsuitable for this one: it misclassified a plain new-dimension follow-up
    // ("what about sales by region") as a correction in 2 of 3 trials, which gpt-4o never did —
    // that would incorrectly decrease confidence on a correct learned mapping in production. Per
    // the audit's own rule ("if the cheaper model fails a contract, report unsuitable rather than
    // compensating with Java logic"), this candidate stays on gpt-4o; the property still exists so
    // it can be revisited independently later.
    @Value("${nexus.openai.correction-detector-model:gpt-4o}")
    private String correctionDetectorModel;

    @Value("${nexus.openai.onboarding-model:gpt-4o-mini}")
    private String onboardingModel;

    @Value("${nexus.openai.memory-selection-model:gpt-4o-mini}")
    private String memorySelectionModel;

    // Phase 3 controlled core model migration: Planner/Evaluator/Composer each get their own
    // configurable model, independent of chatModel and of each other. Defaults to gpt-4.1 per
    // Phase 2's acceptable-candidate finding for these workloads; each is a plain @Value like every
    // other tiering field above, so any one can be reverted independently without a code change.
    //
    // Stage 1 (concept selection + routing) has its own model too. It is an ordinary Responses call
    // over a short candidate list Zevra retrieves from Postgres (see ConceptRetrievalService) — no
    // search tool — so it has no dependence on a model's tool-calling behaviour and is free to use
    // the same family as the planner.
    @Value("${nexus.openai.stage1-model:gpt-4.1}")
    private String stage1Model;
    @Value("${nexus.openai.planner-model:gpt-4.1}")
    private String plannerModel;

    @Value("${nexus.openai.evaluator-model:gpt-4.1}")
    private String evaluatorModel;

    @Value("${nexus.openai.composer-model:gpt-4.1}")
    private String composerModel;

    // Phase 1 explicit prompt caching: deterministic, versioned prompt_cache_key values for the
    // calls whose telemetry (see nexus_usage_event by call_type) showed a large, byte-identical,
    // tenant-independent static prefix — purely a cache-routing HINT (helps OpenAI route repeat
    // requests with the same prefix to the same cache-holding backend machine); it never appears in
    // `instructions`/`input`, never influences what content is sent or reused, and carries no
    // tenant/question/conversation data. The version suffix lets a prompt-contract change roll to a
    // new key without silently sharing cache lookups with the old contract (Stage 1 is v2: it now
    // receives retrieved candidate concepts in the prompt).
    private static final String CACHE_KEY_STAGE1_CONCEPT_AND_ROUTING = "zevra:stage1-concept-and-routing:v2";
    private static final String CACHE_KEY_PLANNER                    = "zevra:planner:v1";
    private static final String CACHE_KEY_EVALUATOR                  = "zevra:evaluator:v1";
    private static final String CACHE_KEY_ANSWER_COMPOSER            = "zevra:answer-composer:v1";
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

        long startNanos = System.nanoTime();
        String responseBody = executeWithRetry(url, requestBody);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddingArray = root.path("data").get(0).path("embedding");
            float[] embedding = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                embedding[i] = (float) embeddingArray.get(i).asDouble();
            }
            int tokenCount = root.path("usage").path("total_tokens").asInt(0);
            // Cost baseline instrumentation (measurement-only): embeddings previously computed a
            // token count that every caller discarded — nothing about this call was ever logged
            // or billed. recordUsage() reads "usage.prompt_tokens" (embeddings responses put the
            // whole cost there, "completion_tokens" is simply absent/0) so this reuses the exact
            // same LLM_METRIC/nexus_usage_event path every other call already goes through.
            recordUsage(root, embeddingModel, latencyMs, text != null ? text.length() : 0);
            return new EmbeddingResult(embedding, tokenCount);
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse embedding response: " + e.getMessage());
        }
    }

    /** Extracts the final assistant message text from a Responses API envelope — the {@code
     *  message}-type output item's concatenated {@code output_text} content. Returns an empty
     *  string (never throws) when the shape doesn't match, so a malformed/unexpected response
     *  degrades to "no JSON found" for the caller's existing parser rather than an exception. */
    private String extractResponseText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : root.path("output")) {
                if (!"message".equals(item.path("type").asText())) continue;
                for (JsonNode content : item.path("content")) {
                    if ("output_text".equals(content.path("type").asText())) {
                        sb.append(content.path("text").asText());
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Responses API equivalent of {@link #chat}: a single model turn, no tools,
     * plain free-form text output (no {@code text.format} at all — the same "no response format"
     * shape {@link #doChat}'s non-JSON path already sends). One HTTP request to {@code
     * POST /v1/responses}, sharing the exact same retry/backoff/rate-awareness/timeout
     * infrastructure every other call on this client uses ({@link #executeWithRetry}).
     *
     * <p>Deliberately transport-only: no {@code previous_response_id} is sent or accepted, no
     * strict JSON Schema, no tools — this is the least-behavior-changing Responses API shape for
     * a caller that today gets an unconstrained text response back and parses it itself
     * (see {@link ReasoningEvaluator}, {@code ChatService#buildMemorySelectionContext},
     * {@link NaturalLanguageComposer} TEXT mode).
     */
    public String respond(List<ChatMessage> messages, String systemPrompt) {
        return doRespond(messages, systemPrompt, false, chatModel);
    }

    /**
     * Phase 4 Structured Outputs equivalent of {@link #respond} for {@code
     * ChatService.buildMemorySelectionContext} ("Memory Selection") — same {@code
     * nexus.openai.memory-selection-model}, now attaching a strict Responses API JSON Schema
     * (see {@code ChatService#memorySelectionJsonSchema}) instead of the prior unconstrained
     * free-form text. The API now guarantees the {@code entity_keys} array shape; Java's own
     * exact-roster membership validation is completely unchanged. Additive: {@link #respond} is
     * unchanged for every other existing caller (Composer's TEXT mode).
     */
    public String respondForMemorySelection(List<ChatMessage> messages, String systemPrompt,
                                             String jsonSchemaName, Map<String, Object> jsonSchema) {
        return respondWithStrictJson(messages, systemPrompt, jsonSchemaName, jsonSchema,
                memorySelectionModel, null);
    }

    /**
     * Phase 4 Structured Outputs equivalent of {@link #respond} for {@link
     * com.sei.nexus.reasoning.ReasoningPlanner} — same {@link #plannerModel} and {@code
     * prompt_cache_key="zevra:planner:v1"} as Phase 1/3, now attaching a strict Responses API
     * JSON Schema (see {@code ReasoningPlanner#plannerJsonSchema}) covering every currently
     * valid Planner response shape (SQL step, metadata request, clarification, done) as one flat
     * schema with per-shape fields nullable — the exact same "required but nullable" idiom
     * {@code ChatService#dataAnswerJsonSchema} already established for Composer. Java's existing
     * precedence-based parsing ({@code nextStep}'s own field checks) is completely unchanged: a
     * field the schema now guarantees present-as-null behaves identically to the same field being
     * absent, which is exactly how the existing {@code Map}-based parsing already treated it.
     */
    public String respondForPlanner(List<ChatMessage> messages, String systemPrompt,
                                     String jsonSchemaName, Map<String, Object> jsonSchema) {
        return respondWithStrictJson(messages, systemPrompt, jsonSchemaName, jsonSchema,
                plannerModel, CACHE_KEY_PLANNER);
    }

    /**
     * Phase 4 Structured Outputs equivalent of {@link #respond} for {@link
     * com.sei.nexus.reasoning.ReasoningEvaluator} — same {@link #evaluatorModel} and {@code
     * prompt_cache_key="zevra:evaluator:v1"}, now attaching a strict Responses API JSON Schema
     * (see {@code ReasoningEvaluator#evaluatorJsonSchema}) so {@code resultSetMatches}/{@code
     * decision}/{@code rationale} are always present — the safety-relevant {@code
     * resultSetMatches} clamp in {@code evaluate()} is unchanged, it simply can no longer be
     * skipped by a missing/malformed field.
     */
    public String respondForEvaluator(List<ChatMessage> messages, String systemPrompt,
                                       String jsonSchemaName, Map<String, Object> jsonSchema) {
        return respondWithStrictJson(messages, systemPrompt, jsonSchemaName, jsonSchema,
                evaluatorModel, CACHE_KEY_EVALUATOR);
    }

    /**
     * Stage 1 (business-concept selection + routing) for {@code ConceptScopedMetadataResolver} — an
     * ordinary Responses call with a strict JSON Schema, using {@link #stage1Model} and {@code
     * prompt_cache_key="zevra:stage1-concept-and-routing:v2"}. No tools: the candidate concepts the
     * model chooses from are retrieved by Zevra from Postgres (see {@code ConceptRetrievalService})
     * and sent in the prompt, so the model decides ONLY which of those candidates are relevant.
     */
    public String respondForStage1(List<ChatMessage> messages, String systemPrompt,
                                    String jsonSchemaName, Map<String, Object> jsonSchema) {
        return respondWithStrictJson(messages, systemPrompt, jsonSchemaName, jsonSchema,
                stage1Model, CACHE_KEY_STAGE1_CONCEPT_AND_ROUTING);
    }

    /**
     * Phase 1 explicit prompt caching equivalent of {@link #respond} for {@link
     * com.sei.nexus.response.NaturalLanguageComposer}'s TEXT mode — identical request shape,
     * additionally attaching {@code prompt_cache_key="zevra:answer-composer:v1"}.
     */
    public String respondForComposer(List<ChatMessage> messages, String systemPrompt) {
        return doRespond(messages, systemPrompt, false, composerModel, CACHE_KEY_ANSWER_COMPOSER);
    }

    /**
     * Same as {@link #respond}, with the Responses API's {@code text.format = json_object} mode
     * enabled — the direct equivalent of {@link #chatWithJson}'s Chat Completions {@code
     * response_format = json_object}, not the stricter {@code json_schema}/{@code strict:true}
     * mode {@link #respondWithStrictJson} supports. Deliberately the least-behavior-changing
     * structured-output equivalent: OpenAI still only guarantees syntactically valid JSON, not a
     * specific shape — callers keep their own existing tolerant parsing exactly as before.
     */
    public String respondWithJson(List<ChatMessage> messages, String systemPrompt) {
        return doRespond(messages, systemPrompt, true, chatModel);
    }

    /**
     * Responses API call with strict JSON-schema-enforced structured output ({@code
     * text.format={"type":"json_schema",...,"strict":true}}), no tools. Reserved for small,
     * fully-deterministic contracts (like the Teaching Proposal contract) that benefit from an
     * API-enforced exact shape rather than prose-only JSON instructions.
     *
     * @param jsonSchemaName a short identifier for the schema (Responses API requires a {@code
     *                       name} alongside {@code schema}) — purely diagnostic, not user-facing.
     * @param jsonSchema     a JSON Schema object (nested {@code Map}/{@code List}/primitive values)
     *                       describing the exact required response shape. Must be valid strict-mode
     *                       JSON Schema (every property required, {@code additionalProperties:false}
     *                       at every object level) or OpenAI rejects the request.
     */
    public String respondWithStrictJson(List<ChatMessage> messages, String systemPrompt,
                                         String jsonSchemaName, Map<String, Object> jsonSchema) {
        return respondWithStrictJson(messages, systemPrompt, jsonSchemaName, jsonSchema, chatModel, null);
    }

    /**
     * Phase 1 explicit prompt caching equivalent of {@link #respondWithStrictJson} for {@link
     * com.sei.nexus.response.NaturalLanguageComposer}'s STRICT_JSON mode — identical request
     * shape, additionally attaching {@code prompt_cache_key="zevra:answer-composer:v1"}.
     * Additive: the 4-arg {@link #respondWithStrictJson} (used by {@code TeachingService} and any
     * other existing caller) is unchanged. Phase 3: uses {@link #composerModel} (gpt-4.1 by
     * default) rather than {@link #chatModel} — the model, like the cache key, is scoped to this
     * one caller and does not affect the 4-arg overload's callers.
     */
    public String respondWithStrictJsonForComposer(List<ChatMessage> messages, String systemPrompt,
                                                    String jsonSchemaName, Map<String, Object> jsonSchema) {
        return respondWithStrictJson(messages, systemPrompt, jsonSchemaName, jsonSchema, composerModel,
                CACHE_KEY_ANSWER_COMPOSER);
    }

    /**
     * Same as the 4-arg {@link #respondWithStrictJson}, additionally taking an explicit {@code
     * model} (Phase 3: so this one Responses-API shape can serve both {@code chatModel}-using
     * callers and {@link #composerModel}-using callers without duplicating the request-building
     * code) and an explicit Responses API {@code prompt_cache_key} (Phase 1 explicit prompt
     * caching) when {@code promptCacheKey} is non-null/non-blank — added as the LAST top-level
     * request field, after the existing stable {@code instructions} / dynamic {@code input} /
     * schema construction below is unchanged and complete. Purely a cache-routing hint; never part
     * of the rendered instructions/input content, so it cannot cause one caller's dynamic content
     * to be served to another. {@code null} cache key reproduces the exact prior behavior.
     */
    private String respondWithStrictJson(List<ChatMessage> messages, String systemPrompt,
                                          String jsonSchemaName, Map<String, Object> jsonSchema,
                                          String model, String promptCacheKey) {
        String url = BASE_URL + "/responses";

        StringBuilder inputBuilder = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (inputBuilder.length() > 0) inputBuilder.append("\n\n");
            inputBuilder.append(msg.content() != null ? msg.content() : "");
        }
        String inputText = inputBuilder.toString() + "\n\n(Respond in JSON as instructed.)";

        Map<String, Object> textFormat = new HashMap<>();
        textFormat.put("type", "json_schema");
        textFormat.put("name", jsonSchemaName);
        textFormat.put("strict", true);
        textFormat.put("schema", jsonSchema);
        Map<String, Object> text = new HashMap<>();
        text.put("format", textFormat);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            requestBody.put("instructions", systemPrompt);
        }
        requestBody.put("input", inputText);
        requestBody.put("temperature", 0.2);
        requestBody.put("max_output_tokens", 4096);
        requestBody.put("text", text);
        if (promptCacheKey != null && !promptCacheKey.isBlank()) {
            requestBody.put("prompt_cache_key", promptCacheKey);
        }

        long startNanos = System.nanoTime();
        String responseBody = executeWithRetry(url, requestBody);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            int requestChars = systemPrompt != null ? systemPrompt.length() : 0;
            for (ChatMessage msg : messages) {
                requestChars += msg.content() != null ? msg.content().length() : 0;
            }
            recordResponsesUsage(root, model, latencyMs, requestChars);
            return extractResponseText(responseBody);
        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse responses output: " + e.getMessage());
        }
    }

    /**
     * Builds and sends one Responses API request, sharing {@link #executeWithRetry} with every
     * other call on this client (identical retry/backoff/rate-limit/timeout behavior to {@link
     * #doChat}). {@code messages} are flattened into the Responses API's plain-string {@code
     * input} field (double-newline-joined) — every current caller of {@link #respond}/{@link
     * #respondWithJson} passes exactly one message, so this is a lossless equivalent of what
     * {@link #doChat} already sends as a single-turn {@code messages} array.
     */
    private String doRespond(List<ChatMessage> messages, String systemPrompt, boolean jsonMode, String model) {
        return doRespond(messages, systemPrompt, jsonMode, model, null);
    }

    /**
     * Same as the 4-arg {@link #doRespond}, additionally attaching an explicit Responses API
     * {@code prompt_cache_key} (Phase 1 explicit prompt caching) when {@code promptCacheKey} is
     * non-null/non-blank. Added as the LAST top-level request field, after the existing stable
     * {@code instructions} / dynamic {@code input} construction below is unchanged and complete —
     * this is purely a cache-routing hint for OpenAI's backend, never part of the rendered
     * instructions/input content itself, so it cannot cause one caller's dynamic content to be
     * served to another. {@code null} (the 4-arg overload) reproduces the exact prior behavior.
     */
    private String doRespond(List<ChatMessage> messages, String systemPrompt, boolean jsonMode, String model,
                              String promptCacheKey) {
        String url = BASE_URL + "/responses";

        StringBuilder inputBuilder = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (inputBuilder.length() > 0) inputBuilder.append("\n\n");
            inputBuilder.append(msg.content() != null ? msg.content() : "");
        }
        String inputText = inputBuilder.toString();
        if (jsonMode) {
            // OpenAI API-compliance formality: text.format=json_object requires the literal word
            // "json" to appear in `input` itself, not only in `instructions`. Never shown to or
            // written by the user; does not change the question's meaning.
            inputText = inputText + "\n\n(Respond in JSON as instructed.)";
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            requestBody.put("instructions", systemPrompt);
        }
        requestBody.put("input", inputText);
        requestBody.put("temperature", 0.2);
        requestBody.put("max_output_tokens", 4096);
        if (jsonMode) {
            Map<String, Object> textFormat = new HashMap<>();
            textFormat.put("type", "json_object");
            Map<String, Object> text = new HashMap<>();
            text.put("format", textFormat);
            requestBody.put("text", text);
        }
        if (promptCacheKey != null && !promptCacheKey.isBlank()) {
            requestBody.put("prompt_cache_key", promptCacheKey);
        }

        long startNanos = System.nanoTime();
        String responseBody = executeWithRetry(url, requestBody);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            int requestChars = systemPrompt != null ? systemPrompt.length() : 0;
            for (ChatMessage msg : messages) {
                requestChars += msg.content() != null ? msg.content().length() : 0;
            }
            recordResponsesUsage(root, model, latencyMs, requestChars);
            return extractResponseText(responseBody);
        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse responses output: " + e.getMessage());
        }
    }

    /** The estimated USD cost of one call, formatted for the {@code LLM_METRIC} line — the same
     *  figure {@link UsageService} persists (one shared estimator), so a per-question cost can be
     *  summed straight from the log by {@code operationId}. Logging-only: never throws. */
    private static String costForLog(String model, int promptTokens, int cachedTokens, int completionTokens) {
        try {
            return String.format(java.util.Locale.ROOT, "%.5f",
                    UsageService.estimateCostUsd(model, promptTokens, cachedTokens, completionTokens));
        } catch (Exception e) {
            return "n/a";
        }
    }

    /**
     * Usage/cost observability for {@link #doRespond} — the same {@code LLM_METRIC} line shape
     * {@link #recordUsage} already produces for Chat Completions calls, reading the Responses
     * API's own usage field names ({@code input_tokens}/{@code output_tokens}/{@code
     * input_tokens_details.cached_tokens}) instead of Chat Completions' ({@code prompt_tokens}/
     * {@code completion_tokens}). Every Responses call — planner, evaluator, composer, Stage 1 —
     * records its usage here.
     */
    private void recordResponsesUsage(JsonNode root, String model, long latencyMs, int requestChars) {
        try {
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                int inputTokens  = usage.path("input_tokens").asInt(0);
                int outputTokens = usage.path("output_tokens").asInt(0);
                int cachedTokens = usage.path("input_tokens_details").path("cached_tokens").asInt(0);
                String callType  = LlmCallTag.get();
                usageService.record(model, inputTokens, outputTokens, cachedTokens, callType);
                log.info("LLM_METRIC callType={} model={} latencyMs={} promptTokens={} completionTokens={} "
                                + "cachedTokens={} requestChars={} operationId={} costUsd={}",
                        callType, model, latencyMs, inputTokens, outputTokens, cachedTokens, requestChars,
                        OperationCorrelationId.get(),
                        costForLog(model, inputTokens, cachedTokens, outputTokens));
            }
        } catch (Exception ignored) {
            // Usage tracking/metrics logging is measurement-only — never break the main flow
        } finally {
            LlmCallTag.clear();
        }
    }

    /**
     * Sends a chat completion request and returns the assistant's text response.
     */
    public String chat(List<ChatMessage> messages, String systemPrompt) {
        return doChat(messages, systemPrompt, false, chatModel);
    }

    /**
     * Model-tiering equivalent of {@link #chat} for {@code TermExtractor} — identical request
     * shape (free-text output, the caller extracts its own JSON array), using {@code
     * nexus.openai.term-extractor-model} (defaults to {@code gpt-4o-mini}) instead of {@link
     * #chatModel}. Additive: {@link #chat} is unchanged for every other existing caller.
     */
    public String chatForTermExtraction(List<ChatMessage> messages, String systemPrompt) {
        return doChat(messages, systemPrompt, false, termExtractorModel);
    }

    /**
     * Model-tiering equivalent of {@link #chat} for {@code CorrectionDetector} — identical
     * request shape, using {@code nexus.openai.correction-detector-model}. Currently defaults to
     * {@code gpt-4o}: a live evaluation found {@code gpt-4o-mini} unsuitable for this call (it
     * misclassified plain new-dimension follow-ups as corrections) — the property exists so this
     * can be retuned independently later. Additive: {@link #chat} is unchanged for every other
     * existing caller.
     */
    public String chatForCorrectionDetection(List<ChatMessage> messages, String systemPrompt) {
        return doChat(messages, systemPrompt, false, correctionDetectorModel);
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
     * Model-tiering equivalent of {@link #chatWithJson} for the onboarding/Discover analysis
     * family — {@code OnboardingService.recommendTables}, {@code
     * BusinessObjectBatchAnalyzer.analyzeBatch} (shared by Onboarding's batch analysis and
     * {@code EnterpriseMapService.analyzeForOnboarding}), and {@code
     * EnterpriseMapService.simulate}. Identical request shape (JSON object mode), using {@code
     * nexus.openai.onboarding-model} (defaults to {@code gpt-4o-mini}) instead of {@link
     * #chatModel}. Additive: {@link #chatWithJson} is unchanged for every other existing caller.
     */
    public String chatWithJsonForOnboarding(List<ChatMessage> messages, String systemPrompt) {
        return doChat(messages, systemPrompt, true, onboardingModel);
    }

    /**
     * Sends a tool-calling request over the OpenAI Responses API — {@code AgentRunner}'s ReAct
     * loop transport (Phase 9C). Takes {@link AgentMessage} history, returns an {@link
     * AgentToolResponse}: either a tool call the model wants to make, or a final text answer.
     * Fixed {@code temperature}/{@code max_output_tokens}/{@code tool_choice="auto"}, and —
     * deliberately preserved, not "fixed" — processes only the FIRST tool call the model returns
     * in one turn (see {@link #extractToolOrFinalAnswer}) even if the model returns several.
     * Posts to {@code /responses}, representing conversation history as a flat Responses API
     * {@code input} item array ({@code message} / {@code function_call} / {@code
     * function_call_output}), never Chat Completions' role-tagged messages.
     *
     * <p>Deliberately does NOT use {@code previous_response_id} — the full (pruned) history
     * {@code AgentRunner} already builds is replayed on every call, so none of {@code
     * AgentRunner}'s own history/pruning/caching/iteration-limit/salvage/exception-handling logic
     * needs to change (see the Phase 9A investigation, {@code
     * docs/architecture/phase-9-agent-runner-responses-investigation.md}, §11/§18, for why
     * chaining is a separate, not-yet-justified design decision). {@code tools} is still supplied
     * in {@link AgentToolRegistry}'s existing Chat-Completions-nested shape ({@code
     * {"type":"function","function":{"name","description","parameters"}}}) — unchanged, so the
     * tool schema itself never moves — and is flattened into the Responses API's own function-tool
     * shape only at this transport boundary, by {@link #flattenToolsForResponsesApi}.
     */
    public AgentToolResponse respondWithTools(List<AgentMessage> messages,
                                               String systemPrompt,
                                               List<Map<String, Object>> tools) {
        String url = BASE_URL + "/responses";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", chatModel);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            requestBody.put("instructions", systemPrompt);
        }
        requestBody.put("input", buildResponsesInputItems(messages));
        requestBody.put("tools", flattenToolsForResponsesApi(tools));
        requestBody.put("tool_choice", "auto");
        requestBody.put("temperature", 0.2);
        requestBody.put("max_output_tokens", 4096);

        long startNanos = System.nanoTime();
        String responseBody = executeWithRetry(url, requestBody);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        try {
            JsonNode root    = objectMapper.readTree(responseBody);
            int requestChars = systemPrompt != null ? systemPrompt.length() : 0;
            for (AgentMessage msg : messages) {
                requestChars += msg.content() != null ? msg.content().length() : 0;
            }
            recordResponsesUsage(root, chatModel, latencyMs, requestChars);
            return extractToolOrFinalAnswer(root);
        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse tool-call response: " + e.getMessage());
        }
    }

    /**
     * Builds the Responses API {@code input} item array from {@code AgentRunner}'s existing
     * {@link AgentMessage} history — a lossless, one-to-one structural translation, never a
     * reinterpretation: a plain user/assistant-text message becomes a {@code message} item; an
     * assistant tool-call message ({@link AgentMessage#assistantToolCall}) becomes one {@code
     * function_call} item per captured call (always exactly one today, since {@link
     * AgentToolResponse} itself can only ever carry a single tool call — see {@link
     * #extractToolOrFinalAnswer}); a tool-result message ({@link AgentMessage#toolResult})
     * becomes a {@code function_call_output} item keyed by the same {@code call_id}/{@code
     * toolCallId}, replacing Chat Completions' {@code role:"tool"}/{@code tool_call_id} linking.
     */
    @SuppressWarnings("unchecked")
    private List<Object> buildResponsesInputItems(List<AgentMessage> messages) {
        List<Object> items = new ArrayList<>();
        for (AgentMessage msg : messages) {
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                for (Map<String, Object> call : msg.toolCalls()) {
                    Map<String, Object> function = (Map<String, Object>) call.get("function");
                    Map<String, Object> item = new HashMap<>();
                    item.put("type", "function_call");
                    item.put("call_id", call.get("id"));
                    item.put("name", function.get("name"));
                    item.put("arguments", function.get("arguments"));
                    items.add(item);
                }
            } else if (msg.toolCallId() != null) {
                Map<String, Object> item = new HashMap<>();
                item.put("type", "function_call_output");
                item.put("call_id", msg.toolCallId());
                item.put("output", msg.content() != null ? msg.content() : "");
                items.add(item);
            } else {
                Map<String, Object> item = new HashMap<>();
                item.put("role", msg.role());
                item.put("content", msg.content() != null ? msg.content() : "");
                items.add(item);
            }
        }
        return items;
    }

    /**
     * Reshapes {@link AgentToolRegistry#getToolDefinitions}'s existing Chat-Completions-nested
     * tool definitions ({@code {"type":"function","function":{"name","description","parameters"}}})
     * into the Responses API's own flat function-tool shape ({@code
     * {"type":"function","name","description","parameters"}}) — a pure transport-envelope
     * reshaping. The tool schema content itself (name/description/parameters JSON Schema) is
     * relayed verbatim; {@link AgentToolRegistry} itself is not changed by this reshaping.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> flattenToolsForResponsesApi(List<Map<String, Object>> tools) {
        List<Map<String, Object>> flattened = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> function = (Map<String, Object>) tool.get("function");
            Map<String, Object> flat = new HashMap<>();
            flat.put("type", "function");
            flat.put("name", function.get("name"));
            flat.put("description", function.get("description"));
            flat.put("parameters", function.get("parameters"));
            flattened.add(flat);
        }
        return flattened;
    }

    /**
     * Parses a Responses API tool-calling turn: walks {@code output[]} in order and returns the
     * FIRST {@code function_call} item found (Phase 9A/9B baseline — deliberately preserved, not
     * "improved": any further {@code function_call} items in the same turn are silently ignored).
     * When no {@code function_call} item is present, falls back to the same {@code message}/{@code
     * output_text} walk {@link #extractResponseText} already uses, returning it as a final answer.
     */
    private AgentToolResponse extractToolOrFinalAnswer(JsonNode root) throws Exception {
        for (JsonNode item : root.path("output")) {
            if ("function_call".equals(item.path("type").asText())) {
                String toolCallId = item.path("call_id").asText();
                String toolName   = item.path("name").asText();
                String argsJson   = item.path("arguments").asText();

                @SuppressWarnings("unchecked")
                Map<String, Object> args = objectMapper.readValue(argsJson, Map.class);
                return AgentToolResponse.ofToolCall(toolName, toolCallId, args);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            if (!"message".equals(item.path("type").asText())) continue;
            for (JsonNode content : item.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    sb.append(content.path("text").asText());
                }
            }
        }
        return AgentToolResponse.ofFinal(sb.toString());
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

        long startNanos = System.nanoTime();
        String responseBody = executeWithRetry(url, requestBody);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            int requestChars = systemPrompt != null ? systemPrompt.length() : 0;
            for (ChatMessage msg : messages) {
                requestChars += msg.content() != null ? msg.content().length() : 0;
            }
            recordUsage(root, model, latencyMs, requestChars);
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

    /**
     * Extracts token counts from an OpenAI response and records them for billing — behavior
     * unchanged from before the Zevra Cognitive Runtime baseline instrumentation was added
     * (still a no-op when {@code usage} is absent, still silently swallows any parsing failure).
     *
     * <p>Additionally — purely observational, never feeding back into any decision — logs one
     * {@code LLM_METRIC} line per call for the measured baseline: the {@link LlmCallTag} the
     * caller set, the model, wall-clock latency for the {@link #executeWithRetry} call (network +
     * any retry/backoff time), prompt/completion tokens, {@code cached_tokens} when OpenAI's
     * automatic prompt caching reports one (absent/0 simply means this call's prefix wasn't
     * cache-eligible or didn't hit — never treated as an error), and the character count of what
     * was actually sent (system prompt + all message contents, computed by each call site before
     * this method runs). This logging is best-effort and independently guarded so a failure in it
     * can never affect the pre-existing usage-recording behavior above, or vice versa.
     */
    private void recordUsage(JsonNode root, String model, long latencyMs, int requestChars) {
        JsonNode usage = root.path("usage");
        String callType = LlmCallTag.get(); // captured before any recordUsage()-triggered clear() below
        try {
            if (!usage.isMissingNode()) {
                int prompt       = usage.path("prompt_tokens").asInt(0);
                int completion   = usage.path("completion_tokens").asInt(0);
                int cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
                usageService.record(model, prompt, completion, cachedTokens, callType);
            }
        } catch (Exception ignored) {
            // Usage tracking is non-fatal — never break the main flow
        }
        try {
            int prompt        = usage.path("prompt_tokens").asInt(0);
            int completion     = usage.path("completion_tokens").asInt(0);
            int cachedTokens   = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
            log.info("LLM_METRIC callType={} model={} latencyMs={} promptTokens={} completionTokens={} "
                            + "cachedTokens={} requestChars={} operationId={} costUsd={}",
                    callType, model, latencyMs, prompt, completion, cachedTokens, requestChars,
                    OperationCorrelationId.get(),
                    costForLog(model, prompt, cachedTokens, completion));
        } catch (Exception ignored) {
            // Metrics logging is measurement-only — never break the main flow
        } finally {
            LlmCallTag.clear();
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60))
                .build();
        return throttledSendWithRetry(request);
    }

    /**
     * Global throttle: acquired before the retry loop begins, released only after it finally
     * returns or throws — must wrap the whole retry+backoff sequence, not just the HTTP send, or
     * a 429-storm still lets unlimited threads pile up mid-backoff, defeating the point of a
     * concurrency cap. Shared by every request shape (JSON, multipart, GET) this client sends.
     */
    private String throttledSendWithRetry(HttpRequest request) {
        try {
            globalCallLimit.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "OpenAI call interrupted while waiting for capacity");
        }
        try {
            return sendWithRetryLocked(request);
        } finally {
            globalCallLimit.release();
        }
    }

    /**
     * The retry/backoff/rate-awareness loop itself, extracted from the JSON-only path this used
     * to be — behavior is unchanged, only parameterized by an already-built {@link HttpRequest}
     * instead of always constructing one from a JSON string. {@code HttpRequest.BodyPublishers}
     * (both {@code ofString} and {@code ofByteArray}) are repeatable, so reusing the same request
     * object across retry attempts is safe.
     */
    private String sendWithRetryLocked(HttpRequest request) {
        long backoffMs = INITIAL_BACKOFF_MS;
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            // Proactive pacing: if the last response told us we're essentially out of
            // budget for this window, wait for the provider's own reset time instead of
            // racing in and guaranteeing a 429. A stale/unknown reading is a no-op.
            waitForRateBudget();

            try {
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
