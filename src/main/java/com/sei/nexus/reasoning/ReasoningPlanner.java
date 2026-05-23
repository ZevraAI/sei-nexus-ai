package com.sei.nexus.reasoning;

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
 * Generates the single next SQL step for an investigation, given:
 * <ul>
 *   <li>the user's original question,</li>
 *   <li>the approved schema context (tables, columns, relationships),</li>
 *   <li>a compact summary of every step executed so far.</li>
 * </ul>
 *
 * <p>The key difference from the original single-shot planner: the LLM sees
 * <em>actual result summaries</em> from prior steps before deciding what to
 * query next. This enables genuine multi-hop causal reasoning — each step's
 * findings inform the next step's SQL.
 *
 * <p>Returns {@code null} when the planner determines no further queries are
 * needed (the evidence accumulated is already sufficient).
 */
@Component
public class ReasoningPlanner {

    private static final Logger log = LoggerFactory.getLogger(ReasoningPlanner.class);

    private static final String SYSTEM_PROMPT = """
            You are a SQL investigation planner building a case step by step.
            The user's question and all evidence gathered so far are provided.
            Your job: generate the SINGLE next SQL query that will most advance the investigation.

            Rules:
            - Use only the tables and columns listed under "Approved schema".
            - Use the exact connection_key shown for each table.
            - Do NOT repeat a query that has already been executed (check "Evidence so far").
            - If the evidence already answers the question, return: {"done": true}
            - Write focused SQL — a targeted SELECT, not SELECT *.
            - Joins, aggregations, GROUP BY, ORDER BY, LIMIT are all allowed.
            - Extract filter values from the attached file content when present.

            Return JSON only (no markdown, no explanation):
            {"done":false,"description":"one-line goal","sql":"SELECT ...","connection_key":"...","object_keys":"key1,key2","rationale":"why this step advances the investigation"}

            OR if no further queries are needed:
            {"done":true}
            """;

    private final AzureOpenAiClient aiClient;
    private final ObjectMapper      objectMapper;

    public ReasoningPlanner(AzureOpenAiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient     = aiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * @param question    The raw user question.
     * @param schemaCtx   Approved schema context string (tables, columns, relationships).
     * @param evidence    Accumulated evidence from prior steps.
     * @return The next step plan, or {@code null} if the planner says it's done.
     */
    public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
        try {
            String prompt = buildPrompt(question, schemaCtx, evidence);
            String raw    = aiClient.chat(List.of(ChatMessage.user(prompt)), SYSTEM_PROMPT);
            String json   = extractJson(raw);
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            if (Boolean.TRUE.equals(parsed.get("done"))) return null;

            String sql     = (String) parsed.get("sql");
            String connKey = (String) parsed.get("connection_key");
            if (sql == null || sql.isBlank() || connKey == null || connKey.isBlank()) return null;

            return new StepPlan(
                    strOr(parsed, "description", "Investigation step " + (evidence.stepCount() + 1)),
                    sql.strip(),
                    connKey.strip(),
                    strOr(parsed, "object_keys", ""),
                    strOr(parsed, "rationale", ""));
        } catch (Exception e) {
            log.warn("ReasoningPlanner failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String question, String schemaCtx, EvidenceStore evidence) {
        return "Question: " + question + "\n\n"
                + "Approved schema:\n" + schemaCtx + "\n\n"
                + "Evidence so far:\n" + evidence.buildContextForLlm();
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    private String strOr(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return (v != null && !v.toString().isBlank()) ? v.toString() : def;
    }

    /** Immutable value object representing a planned SQL step. */
    public record StepPlan(
            String description,
            String sql,
            String connectionKey,
            String objectKeys,
            String rationale
    ) {}
}
