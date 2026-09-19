package com.sei.nexus.response;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The single owner of the <b>mechanics</b> of evidence-to-natural-language generation
 * (Unified Answer Engine, Phase 4). Given a {@link CompositionRequest} — an already-assembled
 * prompt, a <b>composition policy</b> (system prompt), a {@link ResponseMode}, and a fallback —
 * it produces the answer via {@link AzureOpenAiClient}.
 *
 * <p>It owns only the model call and its failure handling. It holds <b>no</b> experience-specific
 * content: the prompt text, the system prompt (tone/format), the response mode, and the fallback
 * are all supplied by the calling experience. It emits no presentation — no markdown, HTML, JSON
 * shape, or slash-command text. Its responsibility is generating natural language from
 * already-interpreted evidence, not composing the overall response (each experience shapes that).
 */
@Component
public class NaturalLanguageComposer {

    private static final Logger log = LoggerFactory.getLogger(NaturalLanguageComposer.class);

    /**
     * The expected <b>protocol</b> of the model response — free text, a loose JSON object (the
     * model's {@code json_object} response format, syntactically valid JSON with no shape
     * guarantee), or a STRICT_JSON object (the Responses API's {@code json_schema}/{@code
     * strict:true} format — the model's content judgment is unconstrained, but the API itself
     * guarantees every schema-declared key is present in the shape the schema describes). This is
     * a wire/protocol concern only.
     *
     * <p><b>Invariant:</b> {@code ResponseMode} must never grow into a presentation format.
     * Presentation concerns — Markdown, HTML, Slack formatting, email layout, report layout — remain
     * owned by the consuming experience and never appear here. A new constant is justified only by a
     * new model <i>response protocol</i>, not by a new way of rendering an answer.
     */
    public enum ResponseMode { TEXT, JSON, STRICT_JSON }

    /**
     * A composition request. {@code fallback} is lazily evaluated on failure; a {@code null}
     * fallback means the caller wants failures to propagate (it handles them itself) rather than
     * being masked by a substitute answer. {@code jsonSchemaName}/{@code jsonSchema} are used only
     * when {@code mode == STRICT_JSON} (see {@link #strictJson}) — {@code null} for TEXT/JSON,
     * exactly mirroring {@link com.sei.nexus.ai.AzureOpenAiClient#respondWithStrictJson}'s own
     * parameters. Still holds no experience-specific content: the schema, like the prompt and
     * system prompt, is supplied by the calling experience — this stays the mechanics-only seam.
     */
    public record CompositionRequest(String userPrompt, String systemPrompt,
                                     ResponseMode mode, Supplier<String> fallback,
                                     String jsonSchemaName, Map<String, Object> jsonSchema) {

        public CompositionRequest(String userPrompt, String systemPrompt, ResponseMode mode,
                                   Supplier<String> fallback) {
            this(userPrompt, systemPrompt, mode, fallback, null, null);
        }

        public static CompositionRequest text(String userPrompt, String systemPrompt, Supplier<String> fallback) {
            return new CompositionRequest(userPrompt, systemPrompt, ResponseMode.TEXT, fallback);
        }
        public static CompositionRequest text(String userPrompt, String systemPrompt, String fallback) {
            return text(userPrompt, systemPrompt, () -> fallback);
        }
        public static CompositionRequest json(String userPrompt, String systemPrompt, Supplier<String> fallback) {
            return new CompositionRequest(userPrompt, systemPrompt, ResponseMode.JSON, fallback);
        }
        public static CompositionRequest json(String userPrompt, String systemPrompt, String fallback) {
            return json(userPrompt, systemPrompt, () -> fallback);
        }
        /** Composition (in the given mode) whose failure propagates to the caller (no fallback masking). */
        public static CompositionRequest propagating(String userPrompt, String systemPrompt, ResponseMode mode) {
            return new CompositionRequest(userPrompt, systemPrompt, mode, null);
        }
        /** JSON composition whose failure propagates to the caller (no fallback masking). */
        public static CompositionRequest jsonPropagating(String userPrompt, String systemPrompt) {
            return propagating(userPrompt, systemPrompt, ResponseMode.JSON);
        }
        /**
         * Strict-schema JSON composition — {@link AzureOpenAiClient#respondWithStrictJson}'s
         * exact calling convention (a diagnostic-only schema name plus the JSON Schema object
         * itself), with a lazily-evaluated fallback for the same graceful-degradation behavior
         * every other composition mode already has.
         */
        public static CompositionRequest strictJson(String userPrompt, String systemPrompt,
                                                      String jsonSchemaName, Map<String, Object> jsonSchema,
                                                      Supplier<String> fallback) {
            return new CompositionRequest(userPrompt, systemPrompt, ResponseMode.STRICT_JSON, fallback,
                    jsonSchemaName, jsonSchema);
        }
        public static CompositionRequest strictJson(String userPrompt, String systemPrompt,
                                                      String jsonSchemaName, Map<String, Object> jsonSchema,
                                                      String fallback) {
            return strictJson(userPrompt, systemPrompt, jsonSchemaName, jsonSchema, () -> fallback);
        }
    }

    private final AzureOpenAiClient aiClient;

    public NaturalLanguageComposer(AzureOpenAiClient aiClient) {
        this.aiClient = aiClient;
    }

    /**
     * Runs the model call described by {@code req}. On failure: returns the lazily-evaluated
     * fallback when one is provided, or re-throws when {@code fallback} is {@code null} (the
     * caller opted to handle the failure itself).
     */
    public String compose(CompositionRequest req) {
        List<ChatMessage> messages = List.of(ChatMessage.user(req.userPrompt()));
        try {
            com.sei.nexus.ai.LlmCallTag.set("ANSWER_COMPOSER");
            // Phase 1 Responses API migration: transport-only — same userPrompt/systemPrompt,
            // same TEXT (free-form) vs JSON (json_object, not strict schema) output contract.
            // Phase 1 explicit prompt caching: TEXT and STRICT_JSON modes additionally attach
            // prompt_cache_key="zevra:answer-composer:v1" (a pure cache-routing hint) — JSON
            // (loose json_object) mode is intentionally left unchanged, matching this phase's
            // telemetry-justified scope.
            return switch (req.mode()) {
                case TEXT -> aiClient.respondForComposer(messages, req.systemPrompt());
                case JSON -> aiClient.respondWithJson(messages, req.systemPrompt());
                case STRICT_JSON -> aiClient.respondWithStrictJsonForComposer(messages, req.systemPrompt(),
                        req.jsonSchemaName(), req.jsonSchema());
            };
        } catch (RuntimeException e) {
            if (req.fallback() == null) throw e;
            log.warn("Natural-language composition failed; using fallback: {}", e.getMessage());
            return req.fallback().get();
        }
    }
}
