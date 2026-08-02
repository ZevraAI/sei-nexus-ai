package com.sei.nexus.response;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
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
     * The expected <b>protocol</b> of the model response — free text, or a JSON object (the model's
     * json response format). This is a wire/protocol concern only.
     *
     * <p><b>Invariant:</b> {@code ResponseMode} must never grow into a presentation format.
     * Presentation concerns — Markdown, HTML, Slack formatting, email layout, report layout — remain
     * owned by the consuming experience and never appear here. A new constant is justified only by a
     * new model <i>response protocol</i>, not by a new way of rendering an answer.
     */
    public enum ResponseMode { TEXT, JSON }

    /**
     * A composition request. {@code fallback} is lazily evaluated on failure; a {@code null}
     * fallback means the caller wants failures to propagate (it handles them itself) rather than
     * being masked by a substitute answer.
     */
    public record CompositionRequest(String userPrompt, String systemPrompt,
                                     ResponseMode mode, Supplier<String> fallback) {

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
            return switch (req.mode()) {
                case TEXT -> aiClient.chat(messages, req.systemPrompt());
                case JSON -> aiClient.chatWithJson(messages, req.systemPrompt());
            };
        } catch (RuntimeException e) {
            if (req.fallback() == null) throw e;
            log.warn("Natural-language composition failed; using fallback: {}", e.getMessage());
            return req.fallback().get();
        }
    }
}
