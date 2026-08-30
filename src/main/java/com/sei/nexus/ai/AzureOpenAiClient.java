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
     * Creates an OpenAI Vector Store and returns its id (e.g. {@code "vs_..."}).
     *
     * <p>Phase 1 of the Persistent Tenant Knowledge migration: provisioning only.
     * The store is created empty — no files are uploaded, and nothing here performs
     * File Search. Reuses the same request/retry/throttle/rate-awareness path as
     * every other call on this client ({@link #executeWithRetry}), so a transient
     * failure or rate limit is retried exactly like any other OpenAI call before
     * surfacing to the caller.
     *
     * @param name deterministic, non-sensitive store name (e.g. derived from the
     *             tenant's schema name — never a tenant's display name or email)
     * @return the OpenAI-assigned vector store id
     */
    public String createVectorStore(String name) {
        String url = BASE_URL + "/vector_stores";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("name", name);

        String responseBody = executeWithRetry(url, requestBody);
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String id = root.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "OpenAI vector store creation response did not contain an id");
            }
            return id;
        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse vector store response: " + e.getMessage());
        }
    }

    /**
     * Uploads a small in-memory file to OpenAI ({@code purpose=assistants}, required for Vector
     * Store attachment) and returns its file id. Phase 2A: the caller builds the file content as a
     * {@code byte[]} — never a filesystem path — so nothing here ever touches disk.
     *
     * @param content  the raw file bytes (e.g. UTF-8 JSON)
     * @param filename a deterministic name for the uploaded file (shown in the OpenAI dashboard
     *                 and available to File Search citations — carries no tenant-sensitive data)
     * @param mimeType e.g. {@code "application/json"}
     * @return the OpenAI-assigned file id (e.g. {@code "file_..."})
     */
    public String uploadFile(byte[] content, String filename, String mimeType) {
        String boundary = "zevra-" + java.util.UUID.randomUUID();
        byte[] body = buildMultipartUploadBody(boundary, content, filename, mimeType);
        String responseBody = executeMultipartWithRetry(BASE_URL + "/files", body, boundary);
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String id = root.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "OpenAI file upload response did not contain an id");
            }
            return id;
        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse file upload response: " + e.getMessage());
        }
    }

    private byte[] buildMultipartUploadBody(String boundary, byte[] content, String filename, String mimeType) {
        String crlf = "\r\n";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            out.write(("--" + boundary + crlf).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"purpose\"" + crlf + crlf)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(("assistants" + crlf).getBytes(java.nio.charset.StandardCharsets.UTF_8));

            out.write(("--" + boundary + crlf).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"" + crlf)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(("Content-Type: " + mimeType + crlf + crlf).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(content);
            out.write(crlf.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            out.write(("--" + boundary + "--" + crlf).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            // ByteArrayOutputStream never actually throws IOException — kept for the write() signature.
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to build upload body: " + e.getMessage());
        }
        return out.toByteArray();
    }

    /**
     * Attaches an already-uploaded file to a Vector Store, optionally carrying string attributes
     * (Phase 2A uses this for provenance: {@code concept_uid}, {@code concept_key}, {@code
     * knowledge_type}, {@code pack_key}, {@code connection_key} — see {@code
     * ConceptKnowledgeMaterializationService}). Does not poll for indexing completion — the caller
     * decides whether/how long to wait via {@link #getVectorStoreFileStatus}.
     */
    public void attachFileToVectorStore(String vectorStoreId, String fileId, Map<String, String> attributes) {
        String url = BASE_URL + "/vector_stores/" + vectorStoreId + "/files";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("file_id", fileId);
        if (attributes != null && !attributes.isEmpty()) {
            requestBody.put("attributes", attributes);
        }
        executeWithRetry(url, requestBody);
    }

    /** The {@code status} field of one vector-store-file attachment (e.g. {@code "in_progress"}, {@code "completed"}, {@code "failed"}). */
    public String getVectorStoreFileStatus(String vectorStoreId, String fileId) {
        String url = BASE_URL + "/vector_stores/" + vectorStoreId + "/files/" + fileId;
        String responseBody = executeGetWithRetry(url);
        try {
            return objectMapper.readTree(responseBody).path("status").asText(null);
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse vector store file status response: " + e.getMessage());
        }
    }

    /** One file currently attached to a Vector Store, with whatever string attributes it carries. */
    public record VectorStoreFileRef(String fileId, Map<String, String> attributes) {}

    /**
     * Lists every file currently attached to a Vector Store — used only for Phase 2A's
     * idempotency check (skip re-uploading a concept whose {@code concept_uid} attribute is
     * already present). Not paginated beyond OpenAI's default page size; Phase 2A tenants have at
     * most a few dozen concepts, well under that, so pagination is intentionally not implemented
     * here — see the Phase 2A report's Known Limitations.
     */
    public List<VectorStoreFileRef> listVectorStoreFiles(String vectorStoreId) {
        String url = BASE_URL + "/vector_stores/" + vectorStoreId + "/files";
        String responseBody = executeGetWithRetry(url);
        List<VectorStoreFileRef> refs = new ArrayList<>();
        try {
            JsonNode data = objectMapper.readTree(responseBody).path("data");
            for (JsonNode item : data) {
                String fileId = item.path("id").asText(null);
                Map<String, String> attrs = new HashMap<>();
                JsonNode attributes = item.path("attributes");
                attributes.fields().forEachRemaining(entry -> attrs.put(entry.getKey(), entry.getValue().asText()));
                refs.add(new VectorStoreFileRef(fileId, attrs));
            }
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse vector store file list response: " + e.getMessage());
        }
        return refs;
    }

    /**
     * File Search query against a Vector Store via {@code POST /v1/responses} — returns the raw
     * response JSON body as text, unparsed.
     *
     * <p><strong>Phase 2A validation use only.</strong> Nothing in the production Chat path
     * ({@code ChatService}/{@code AgentBrain}/{@code ConceptScopedMetadataResolver}) calls this —
     * it exists solely for {@code ConceptKnowledgeRetrievalRealTenantValidation} (a real-tenant,
     * real-OpenAI manual validation class, never run by {@code mvn test}) to prove uploaded
     * concept knowledge is actually retrievable. Chat File Search integration is explicitly out of
     * scope for this phase.
     */
    public String fileSearchQuery(String vectorStoreId, String query) {
        String url = BASE_URL + "/responses";
        Map<String, Object> tool = new HashMap<>();
        tool.put("type", "file_search");
        tool.put("vector_store_ids", List.of(vectorStoreId));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", chatModel);
        requestBody.put("input", query);
        requestBody.put("tools", List.of(tool));

        return executeWithRetry(url, requestBody);
    }

    /**
     * A single model turn with OpenAI's <b>native</b> {@code file_search} capability enabled
     * against one tenant Vector Store, with the model's final answer constrained to a JSON
     * object (Responses API {@code text.format = json_object}).
     *
     * <p><b>Java performs no retrieval anywhere in this method.</b> This is one HTTP request to
     * {@code POST /v1/responses}, exactly like {@link #chat}/{@link #chatWithJson} are one
     * request to {@code /chat/completions} — the only difference is which OpenAI endpoint/tool
     * is used. There is no Java-implemented search algorithm, no downloaded file content, no
     * filename or citation parsing, no multi-turn tool-calling loop, and no custom function/tool
     * exposed to the model (contrast with {@link #chatWithTools}, which genuinely does implement
     * an OpenAI function-calling loop where the caller supplies tool results back to the model —
     * that pattern is deliberately NOT used here). OpenAI's own infrastructure executes the
     * search against the named Vector Store server-side, entirely within this one round trip;
     * Java's role starts and ends at sending the request and reading the model's final text.
     *
     * <p>This is a general-purpose capability, not a concept-selection-specific one — it is named
     * and shaped symmetrically with {@link #chat}/{@link #chatWithJson}/{@link #chatWithTools}
     * precisely so it can be reused by any future caller that needs the model to consult a
     * tenant's persistent knowledge, not only {@code ConceptScopedMetadataResolver}.
     *
     * @param vectorStoreId the tenant's own {@code ai_knowledge_vector_store_id} — resolved by
     *                      the caller from {@code TenantContext}, never from client input
     * @param instructions  the system-prompt-equivalent instructions (Responses API {@code
     *                      instructions} field)
     * @param question      the user's question, verbatim
     * @return the model's final message text (expected to be the requested JSON object) — the
     *         same string shape {@link #chatWithJson} returns, ready for the caller's existing
     *         JSON parsing
     */
    public String chatWithFileSearch(String vectorStoreId, String instructions, String question) {
        return chatWithFileSearch(vectorStoreId, instructions, question, null).text();
    }

    /**
     * Result of a {@link #chatWithFileSearch} call that also needs the OpenAI response id —
     * e.g. to persist it for a later {@code previous_response_id}-chained turn. Additive: the
     * original 3-arg {@link #chatWithFileSearch} delegates here and simply discards {@link
     * #responseId}, so every existing caller/test keeps compiling and behaving identically.
     *
     * @param text       the model's final message text — identical to what the 3-arg overload returns
     * @param responseId the OpenAI-assigned response id ({@code resp_...}), or {@code null} if the
     *                   response envelope didn't contain one (never expected in practice, but the
     *                   caller must not assume non-null)
     */
    public record FileSearchResult(String text, String responseId) {}

    /**
     * Same native-{@code file_search} single-turn call as the 3-arg {@link #chatWithFileSearch},
     * with one additive capability: OpenAI conversation chaining via {@code previous_response_id}.
     *
     * <p>Passing a non-null {@code previousResponseId} asks OpenAI to reconstruct the prior turn's
     * context server-side — Java never resends prior question/answer text for this. Passing
     * {@code null} (or using the 3-arg overload) makes an ordinary, unchained turn — the existing,
     * unmodified behavior. This does not change what native {@code file_search} is or how it's
     * configured (still {@code tools=[{"type":"file_search","vector_store_ids":[vectorStoreId]}]});
     * {@code previous_response_id} is a separate, additive top-level request field.
     *
     * @param previousResponseId the prior turn's OpenAI response id for this same conversation, or
     *                           {@code null} for a fresh/unchained turn. The caller is responsible
     *                           for resolving this from Zevra-owned, tenant/conversation-scoped
     *                           state — never from client input — and for falling back to a fresh
     *                           call if OpenAI rejects an invalid/expired id (this method does not
     *                           retry internally; see {@code ConceptScopedMetadataResolver} for the
     *                           fallback-to-fresh discipline)
     * @return the model's final text plus the new response id, for the caller to persist as this
     *         conversation's new "latest" id
     */
    public FileSearchResult chatWithFileSearch(String vectorStoreId, String instructions, String question,
                                                String previousResponseId) {
        return chatWithFileSearch(vectorStoreId, instructions, question, previousResponseId, null);
    }

    /**
     * Same as the 4-arg {@link #chatWithFileSearch}, with one additive capability: strict
     * JSON-schema-enforced structured output (Responses API {@code text.format={"type":
     * "json_schema",...,"strict":true}}) instead of the looser {@code json_object} mode, when
     * {@code jsonSchema} is non-null. Passing {@code null} (or using the 4-arg overload)
     * reproduces the existing {@code json_object} behavior exactly — this parameter is purely
     * additive.
     *
     * <p>Used for the Persistent Knowledge combined concept-selection + routing-decision
     * contract (see {@code ConceptScopedMetadataResolver#selectConceptsAndRoutingViaPersistentKnowledge}),
     * where the {@code routing.type} field must be constrained to an exact enum at the API level
     * rather than relying on prose alone — the same discipline the old Decision Router's
     * {@code chat()} call never had.
     *
     * @param jsonSchema a JSON Schema object (as nested {@code Map}/{@code List}/primitive
     *                   values — the same shape {@code ObjectMapper} would produce from parsing
     *                   a JSON Schema document) describing the exact required response shape, or
     *                   {@code null} for the existing {@code json_object} mode. The caller is
     *                   responsible for the schema being valid strict-mode JSON Schema (every
     *                   property required, {@code additionalProperties:false} at every object
     *                   level) — OpenAI rejects the request otherwise.
     */
    public FileSearchResult chatWithFileSearch(String vectorStoreId, String instructions, String question,
                                                String previousResponseId, Map<String, Object> jsonSchema) {
        String url = BASE_URL + "/responses";
        Map<String, Object> tool = new HashMap<>();
        tool.put("type", "file_search");
        tool.put("vector_store_ids", List.of(vectorStoreId));

        Map<String, Object> textFormat = new HashMap<>();
        if (jsonSchema != null) {
            textFormat.put("type", "json_schema");
            textFormat.put("name", "persistent_knowledge_response");
            textFormat.put("strict", true);
            textFormat.put("schema", jsonSchema);
        } else {
            textFormat.put("type", "json_object");
        }
        Map<String, Object> text = new HashMap<>();
        text.put("format", textFormat);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", chatModel);
        requestBody.put("instructions", instructions);
        // OpenAI requires the literal word "json" to appear in `input` itself (not just
        // `instructions`) to use text.format=json_object — confirmed via a real 400 response:
        // "Response input messages must contain the word 'json' in some form...". This appended
        // line is a pure API-compliance formality, never shown to or written by the user, and
        // does not change the question's meaning.
        requestBody.put("input", question + "\n\n(Respond in JSON as instructed.)");
        requestBody.put("tools", List.of(tool));
        requestBody.put("text", text);
        if (previousResponseId != null && !previousResponseId.isBlank()) {
            requestBody.put("previous_response_id", previousResponseId);
        }

        long startNanos = System.nanoTime();
        String responseBody = executeWithRetry(url, requestBody);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        recordFileSearchUsage(responseBody, vectorStoreId, latencyMs);
        String newResponseId = extractResponseId(responseBody);
        return new FileSearchResult(extractResponseText(responseBody), newResponseId);
    }

    /** Extracts the top-level {@code id} field from a Responses API envelope. {@code null} (never
     *  throws) when absent/malformed — the same defensive posture as {@link #extractResponseText}. */
    private String extractResponseId(String responseBody) {
        try {
            return objectMapper.readTree(responseBody).path("id").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Diagnostic-only observability for {@link #chatWithFileSearch}, read from the raw response
     * BEFORE {@link #extractResponseText} discards everything except the final message text.
     * Proves whether OpenAI's native {@code file_search} tool was actually invoked and completed
     * for this call — never logs the question, {@code instructions}, or any retrieved document
     * content; only the {@code vectorStoreId} (diagnostic, non-sensitive), model, latency, and
     * the {@code file_search_call} item's own presence/status/result-count.
     *
     * <p>Mirrors the existing {@code LLM_METRIC} convention's {@link LlmCallTag} consume-and-clear
     * discipline (same pattern as {@link #recordUsage}), as its own {@code FILE_SEARCH_METRIC}
     * line rather than reusing {@code recordUsage} itself — the Responses API's {@code usage}
     * shape ({@code input_tokens}/{@code output_tokens}) and this call's actually-interesting
     * fields (tool invocation/status/result count, not token counts) are different enough that
     * forcing them through the Chat-Completions-shaped {@code recordUsage} would either silently
     * report zero tokens or require reshaping that method for every other caller — this dedicated,
     * additive method is the smaller change.
     */
    private void recordFileSearchUsage(String responseBody, String vectorStoreId, long latencyMs) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            boolean fileSearchCallPresent = false;
            String status = "absent";
            int resultCount = -1; // -1 = results not requested/unavailable, distinct from 0 = requested and empty
            for (JsonNode item : root.path("output")) {
                if ("file_search_call".equals(item.path("type").asText())) {
                    fileSearchCallPresent = true;
                    status = item.path("status").asText("unknown");
                    JsonNode results = item.path("results");
                    if (results.isArray()) resultCount = results.size();
                    break;
                }
            }
            log.info("FILE_SEARCH_METRIC callType={} model={} vectorStoreId={} latencyMs={} "
                            + "fileSearchCallPresent={} status={} resultCount={}",
                    LlmCallTag.get(), chatModel, vectorStoreId, latencyMs,
                    fileSearchCallPresent, status, resultCount);
        } catch (Exception ignored) {
            // Metrics logging is measurement-only — never break the main flow
        } finally {
            LlmCallTag.clear();
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

        long startNanos = System.nanoTime();
        String responseBody = executeWithRetry(url, requestBody);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        try {
            JsonNode root    = objectMapper.readTree(responseBody);
            int requestChars = systemPrompt != null ? systemPrompt.length() : 0;
            for (AgentMessage msg : messages) {
                requestChars += msg.content() != null ? msg.content().length() : 0;
            }
            recordUsage(root, chatModel, latencyMs, requestChars);
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
        try {
            if (!usage.isMissingNode()) {
                int prompt     = usage.path("prompt_tokens").asInt(0);
                int completion = usage.path("completion_tokens").asInt(0);
                usageService.record(model, prompt, completion);
            }
        } catch (Exception ignored) {
            // Usage tracking is non-fatal — never break the main flow
        }
        try {
            int prompt        = usage.path("prompt_tokens").asInt(0);
            int completion     = usage.path("completion_tokens").asInt(0);
            int cachedTokens   = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
            log.info("LLM_METRIC callType={} model={} latencyMs={} promptTokens={} completionTokens={} "
                            + "cachedTokens={} requestChars={}",
                    LlmCallTag.get(), model, latencyMs, prompt, completion, cachedTokens, requestChars);
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
     * Multipart file upload (e.g. {@code POST /files}) — the one shape {@link #executeWithRetry}
     * cannot express (it always serializes a {@code Map} as a JSON body). Shares the exact same
     * throttle/retry/backoff/rate-awareness path via {@link #throttledSendWithRetry}; only how the
     * {@link HttpRequest} body/content-type are built differs from the JSON path above.
     *
     * @param multipartBody the already-encoded {@code multipart/form-data} bytes (built in memory —
     *                       never written to disk, so there is nothing here for a caller to clean up)
     */
    private String executeMultipartWithRetry(String url, byte[] multipartBody, String boundary) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .timeout(Duration.ofSeconds(60))
                .build();
        return throttledSendWithRetry(request);
    }

    /** {@code GET} variant — no request body, same throttle/retry/backoff/rate-awareness path. */
    private String executeGetWithRetry(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(60))
                .GET()
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
