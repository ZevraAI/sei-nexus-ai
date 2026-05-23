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

/**
 * Extracts business term → SQL pattern pairs from a (question, SQL) pair using
 * an LLM call.
 *
 * <p>The system prompt is deliberately generic — it does not mention any specific
 * industry, domain, or business type.  The LLM infers business terms from
 * the actual question and SQL provided, making this work across any vertical.
 *
 * <p>Returns an empty list when the LLM call fails or finds no clear terms.
 * All failures are swallowed — extraction is best-effort and must never block
 * the response pipeline.
 */
@Component
public class TermExtractor {

    private static final Logger log = LoggerFactory.getLogger(TermExtractor.class);

    private static final String SYSTEM_PROMPT = """
            You are a semantic learning assistant for an enterprise data intelligence platform.

            Given a user's business question and the SQL query that answered it, extract
            up to 3 business terms the user implicitly defined through their question.
            A "business term" is a word or phrase used by this team that maps to a concrete
            SQL condition — e.g. "overdue" → "due_date < CURRENT_DATE AND status != 'CLOSED'".

            Rules:
            - Only extract terms that appear in the question AND have a matching SQL expression
            - The sql field must be a reusable SQL fragment (WHERE condition, expression, or subquery)
            - Do NOT extract generic terms like "total", "list", "show", "get"
            - Do NOT include table names, column names, or literal values as terms
            - Keep terms to 1–4 words maximum
            - Be industry-agnostic: the same logic applies to hospitals, hotels, logistics, finance

            Return a JSON array only (no markdown, no explanation):
            [{"term":"...","sql":"..."}]

            Return [] if no meaningful business terms can be extracted.
            """;

    private final AzureOpenAiClient aiClient;
    private final ObjectMapper      objectMapper;

    public TermExtractor(AzureOpenAiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient     = aiClient;
        this.objectMapper = objectMapper;
    }

    public record ExtractedTerm(String term, String sql) {}

    /**
     * @param question The user's raw question (no attachment content).
     * @param sql      The SQL that successfully answered the question.
     * @return Up to 3 extracted term-SQL pairs, or an empty list on failure.
     */
    public List<ExtractedTerm> extract(String question, String sql) {
        if (question == null || question.isBlank() || sql == null || sql.isBlank()) {
            return List.of();
        }
        try {
            String prompt = "Question: " + question + "\n\nSQL:\n" + truncate(sql, 1000);
            String raw    = aiClient.chat(List.of(ChatMessage.user(prompt)), SYSTEM_PROMPT);
            String json   = extractJsonArray(raw);
            List<Map<String, Object>> parsed = objectMapper.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {});

            return parsed.stream()
                    .filter(m -> m.containsKey("term") && m.containsKey("sql"))
                    .filter(m -> !blank(m.get("term")) && !blank(m.get("sql")))
                    .map(m -> new ExtractedTerm(
                            m.get("term").toString().trim().toLowerCase(),
                            m.get("sql").toString().trim()))
                    .limit(3)
                    .toList();
        } catch (Exception e) {
            log.debug("TermExtractor: no terms extracted ({})", e.getMessage());
            return List.of();
        }
    }

    private String extractJsonArray(String raw) {
        if (raw == null) return "[]";
        int start = raw.indexOf('[');
        int end   = raw.lastIndexOf(']');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : "[]";
    }

    private boolean blank(Object val) {
        return val == null || val.toString().isBlank();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
