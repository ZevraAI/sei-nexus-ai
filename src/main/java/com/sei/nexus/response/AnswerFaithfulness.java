package com.sei.nexus.response;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Answer faithfulness check: is the composed answer consistent with the evidence it was written
 * from?
 *
 * <p>An answer-writing model can mis-summarise rows it was shown — say "three" when the evidence
 * has five, or quote a figure that differs from the returned value. A prompt rule cannot reliably
 * prevent that, so a second, narrow LLM judgment checks the answer against the evidence, and — only
 * when that judgment finds an inconsistency — the answer is composed once more with the findings
 * stated as an observation. Both judgments are the model's: Java only orchestrates. It never
 * edits an answer, never inspects the evidence or the answer text itself, and never decides what
 * is true.
 *
 * <p><b>Bounded and fail-open:</b> at most one extra check and one re-composition per answer, and
 * any failure of the check (model error, unparseable verdict) keeps the original answer — a
 * verification outage must never block or degrade answering.
 *
 * <p>Domain-neutral by construction: the prompt names no industry, concept or field.
 */
public final class AnswerFaithfulness {

    private static final Logger log = LoggerFactory.getLogger(AnswerFaithfulness.class);

    private AnswerFaithfulness() {}

    /** The checker's verdict. {@code issues} is empty when {@code consistent}. */
    public record Verdict(boolean consistent, String issues) {
        public static final Verdict OK = new Verdict(true, "");
    }

    static final String SYSTEM_PROMPT = """
            You are a strict fact-checker for an analytics assistant. You are given a QUESTION, the
            EVIDENCE (query results exactly as the answer writer saw them) and the ANSWER the writer
            produced.

            Decide ONE thing: is every factual claim in the ANSWER consistent with the EVIDENCE?
            Check counts, totals, figures, names, identifiers, lists, comparisons, rankings, and
            statements of "none", "all", "only" or "at least".

            Flag a claim ONLY when the EVIDENCE contradicts it, or when it states a count, total,
            figure or complete list that does not match what the EVIDENCE shows (for example saying
            "three" when the evidence shows five, or leaving out items from a list presented as
            complete). Do NOT flag: style, tone or usefulness; sensible summarisation; omission of
            detail the answer does not claim to be exhaustive about; or claims about specifics the
            EVIDENCE simply does not show either way (you cannot verify them, so do not guess).

            You do not know today's date unless TODAY is given below, and you must never infer it
            from the dates inside the EVIDENCE. Judge whether a date is in the past or the future
            ONLY against the TODAY value provided; if none is provided, do not judge it at all.

            Respond with JSON only: {"consistent": true|false, "issues": "<each contradicted or
            mismatched claim, briefly, quoting the claim and what the evidence shows; empty string
            when consistent>"}.
            """;

    /** Strict schema: both fields always present. Content is entirely the model's judgment. */
    static Map<String, Object> verdictJsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("consistent", Map.of("type", "boolean"));
        properties.put("issues", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.List.of("consistent", "issues"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /** The prompt the checker receives. A pure function of its inputs. {@code today} is the one
     *  runtime fact the checker cannot know itself (supplied by Java as plain input text, never
     *  reasoned from); {@code null} omits it, and the system prompt then forbids date judgments. */
    static String buildPrompt(String question, String evidence, String answer, java.time.LocalDate today) {
        return "QUESTION:\n" + question
                + (today != null ? "\n\nTODAY: " + today : "")
                + "\n\nEVIDENCE:\n" + evidence + "\n\nANSWER:\n" + answer;
    }

    /**
     * Parses the checker's JSON. Anything that is not a clear {@code consistent:false} verdict —
     * malformed JSON, a missing field, a model error — yields {@link Verdict#OK} (fail-open).
     */
    static Verdict parse(ObjectMapper mapper, String json) {
        try {
            if (json == null || json.isBlank()) return Verdict.OK;
            int start = json.indexOf('{'), end = json.lastIndexOf('}');
            if (start < 0 || end <= start) return Verdict.OK;
            Map<String, Object> m = mapper.readValue(json.substring(start, end + 1), new TypeReference<>() {});
            if (!Boolean.FALSE.equals(m.get("consistent"))) return Verdict.OK;
            Object issues = m.get("issues");
            String text = issues == null ? "" : issues.toString().strip();
            // An "inconsistent" verdict that names nothing gives the writer nothing to correct.
            return text.isEmpty() ? Verdict.OK : new Verdict(false, text);
        } catch (Exception e) {
            return Verdict.OK;
        }
    }

    /** One checker call. Never throws; any failure is {@link Verdict#OK}. */
    public static Verdict check(NaturalLanguageComposer composer, ObjectMapper mapper,
                                String question, String evidence, String answer) {
        return check(composer, mapper, question, evidence, answer, java.time.LocalDate.now());
    }

    /** As above with an explicit "today" (tests; {@code null} withholds it). */
    static Verdict check(NaturalLanguageComposer composer, ObjectMapper mapper,
                         String question, String evidence, String answer, java.time.LocalDate today) {
        if (composer == null || answer == null || answer.isBlank()) return Verdict.OK;
        try {
            String json = composer.compose(NaturalLanguageComposer.CompositionRequest.strictJson(
                    buildPrompt(question, evidence, answer, today), SYSTEM_PROMPT,
                    "answer_faithfulness", verdictJsonSchema(), "{\"consistent\":true,\"issues\":\"\"}"));
            return parse(mapper, json);
        } catch (RuntimeException e) {
            log.warn("Answer faithfulness check failed; keeping the original answer: {}", e.getMessage());
            return Verdict.OK;
        }
    }

    /** The observation appended to the writer's prompt when the check found a problem. */
    static String correctionNote(Verdict v) {
        // Advisory, not an order: the reviewer is itself a model and can be wrong (it once flagged a
        // correct answer because it did not know today's date). The writer re-checks each point
        // against the evidence and decides; it keeps any statement the evidence supports.
        return "\n\nVERIFICATION FEEDBACK (not from the user): a reviewer checked your previous answer "
                + "against the evidence above and flagged these possible problems: " + v.issues()
                + "\nThe reviewer can be mistaken. Check each point yourself against the evidence: "
                + "correct a statement ONLY if the evidence contradicts it, and keep any statement the "
                + "evidence supports. State counts, figures and lists exactly as the evidence has them.";
    }

    /**
     * Returns {@code first} when the check finds it consistent (or cannot run); otherwise asks
     * {@code recompose} — given the correction note — for a corrected answer, exactly once. The
     * corrected answer is returned as is; it is not checked again (bounded).
     */
    public static <T> T ensure(NaturalLanguageComposer composer, ObjectMapper mapper, String question,
                               String evidence, String firstAnswerText, T first,
                               Function<String, T> recompose) {
        Verdict v = check(composer, mapper, question, evidence, firstAnswerText);
        if (v.consistent()) return first;
        log.info("ANSWER_FAITHFULNESS verdict=INCONSISTENT recomposing issues={}", v.issues());
        try {
            T second = recompose.apply(correctionNote(v));
            return second != null ? second : first;
        } catch (RuntimeException e) {
            log.warn("Re-composition after a faithfulness finding failed; keeping the original: {}", e.getMessage());
            return first;
        }
    }
}
