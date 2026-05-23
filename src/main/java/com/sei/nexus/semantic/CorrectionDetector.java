package com.sei.nexus.semantic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Detects when a user's follow-up question corrects or refines a prior answer,
 * and extracts what specifically changed.
 *
 * <p>Used to:
 * <ol>
 *   <li>Decrease confidence on the learned mapping that was wrong.</li>
 *   <li>Store the correction as context injected into future SQL planning prompts.</li>
 * </ol>
 *
 * <p>All failures are swallowed — detection is best-effort.
 *
 * <p>correction_type values:
 * TIMEFRAME | ENTITY | FILTER | METRIC | DIRECTION | OTHER
 */
@Component
public class CorrectionDetector {

    private static final Logger log = LoggerFactory.getLogger(CorrectionDetector.class);

    private static final String SYSTEM_PROMPT = """
            You are analysing a two-turn conversation to detect whether the user's
            second message corrects, refines, or contradicts the first answer.

            A correction is when the user indicates the previous answer was wrong or
            incomplete — e.g. "no, I meant last week not last month", "actually filter
            by active status only", "that's the wrong metric, I want revenue not count".

            A refinement that adds detail is also a correction.
            A simple follow-up question asking for new data is NOT a correction.

            Respond with JSON only:
            {
              "is_correction": true,
              "correction_type": "TIMEFRAME|ENTITY|FILTER|METRIC|DIRECTION|OTHER",
              "original_interpretation": "brief phrase describing what Zevra assumed",
              "corrected_interpretation": "brief phrase describing what the user actually meant"
            }

            OR if not a correction:
            {"is_correction": false}
            """;

    private final AzureOpenAiClient aiClient;
    private final ObjectMapper      objectMapper;

    public CorrectionDetector(AzureOpenAiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient     = aiClient;
        this.objectMapper = objectMapper;
    }

    public record DetectedCorrection(
            String correctionType,
            String originalInterpretation,
            String correctedInterpretation
    ) {}

    /**
     * @param currentQuestion The user's latest question.
     * @param priorQuestion   The previous question in the same conversation.
     * @param priorAnswer     Zevra's previous answer (first 500 chars checked).
     * @return An Optional containing the correction details, or empty if not a correction.
     */
    public Optional<DetectedCorrection> detect(String currentQuestion,
                                               String priorQuestion,
                                               String priorAnswer) {
        if (isBlank(currentQuestion) || isBlank(priorQuestion) || isBlank(priorAnswer)) {
            return Optional.empty();
        }
        try {
            String prompt = "Previous question: " + priorQuestion + "\n" +
                    "Previous answer (summary): " + truncate(priorAnswer, 500) + "\n\n" +
                    "New message from user: " + currentQuestion;

            String raw  = aiClient.chat(List.of(ChatMessage.user(prompt)), SYSTEM_PROMPT);
            String json = extractJson(raw);
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            if (!Boolean.TRUE.equals(parsed.get("is_correction"))) {
                return Optional.empty();
            }
            String type    = strOr(parsed, "correction_type",         "OTHER");
            String origInt = strOr(parsed, "original_interpretation",  "");
            String corrInt = strOr(parsed, "corrected_interpretation", "");

            if (origInt.isBlank() && corrInt.isBlank()) return Optional.empty();

            log.debug("Correction detected ({}): '{}' → '{}'", type, origInt, corrInt);
            return Optional.of(new DetectedCorrection(type, origInt, corrInt));
        } catch (Exception e) {
            log.debug("CorrectionDetector: no correction detected ({})", e.getMessage());
            return Optional.empty();
        }
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : "{}";
    }

    private String strOr(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return (v != null && !v.toString().isBlank()) ? v.toString().trim() : def;
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
