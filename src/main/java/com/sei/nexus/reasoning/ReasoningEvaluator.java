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
 * After each executed step, asks the LLM whether the accumulated evidence is
 * sufficient to answer the user's question, or whether more data is needed.
 *
 * <p>Returns one of four decisions:
 * <ul>
 *   <li>{@code SUFFICIENT}              — stop; compose the answer now</li>
 *   <li>{@code NEED_MORE_DATA}          — continue; the planner will generate the next step</li>
 *   <li>{@code NEED_DIFFERENT_APPROACH} — abandon the current line and re-plan from scratch
 *                                         (treated the same as NEED_MORE_DATA for simplicity)</li>
 *   <li>{@code DEAD_END}                — no further queries will help; compose best-effort answer</li>
 * </ul>
 */
@Component
public class ReasoningEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ReasoningEvaluator.class);

    private static final String SYSTEM_PROMPT = """
            You are evaluating whether enough data has been gathered to answer a question.
            You will see the original question and a summary of all queries executed so far.

            Return JSON only:
            {
              "decision": "SUFFICIENT | NEED_MORE_DATA | DEAD_END",
              "rationale": "one sentence explaining your decision"
            }

            Decision guide:
            - SUFFICIENT    : the evidence collected can answer the question fully or substantively.
            - NEED_MORE_DATA: one more targeted query would materially improve the answer.
            - DEAD_END      : the queries have run but the data is not available or the question
                              cannot be answered from the accessible tables.

            Be decisive. Prefer SUFFICIENT over NEED_MORE_DATA when the evidence is good enough
            for a meaningful business answer, even if not exhaustive.
            """;

    private final AzureOpenAiClient aiClient;
    private final ObjectMapper      objectMapper;

    public ReasoningEvaluator(AzureOpenAiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient     = aiClient;
        this.objectMapper = objectMapper;
    }

    public record EvaluationResult(String decision, String rationale) {
        public boolean isSufficient() {
            return "SUFFICIENT".equals(decision);
        }
        public boolean shouldContinue() {
            return "NEED_MORE_DATA".equals(decision) || "NEED_DIFFERENT_APPROACH".equals(decision);
        }
    }

    /**
     * Evaluate whether to continue reasoning after the most recent step.
     *
     * @param question Original user question.
     * @param evidence All evidence gathered so far.
     * @return Evaluation result.  Defaults to SUFFICIENT on LLM failure to avoid infinite loops.
     */
    public EvaluationResult evaluate(String question, EvidenceStore evidence) {
        try {
            String prompt = "Question: " + question + "\n\n"
                    + "Evidence gathered so far:\n" + evidence.buildContextForLlm();

            String raw  = aiClient.chat(List.of(ChatMessage.user(prompt)), SYSTEM_PROMPT);
            String json = extractJson(raw);
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            String decision  = strOr(parsed, "decision",  "SUFFICIENT");
            String rationale = strOr(parsed, "rationale", "");
            log.debug("Evaluator decision after step {}: {} — {}", evidence.stepCount(), decision, rationale);
            return new EvaluationResult(decision, rationale);
        } catch (Exception e) {
            log.warn("ReasoningEvaluator failed: {}; defaulting to SUFFICIENT", e.getMessage());
            return new EvaluationResult("SUFFICIENT", "Defaulted due to evaluation error.");
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
        return (v != null && !v.toString().isBlank()) ? v.toString() : def;
    }
}
