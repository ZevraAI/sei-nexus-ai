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
 * <p>Sufficiency has always implicitly required the evidence to be about the question asked —
 * evidence gathered for a different subject was never truly "sufficient," it just never had
 * the chance to be evidence for the wrong subject until follow-up questions began seeding this
 * evaluator with a prior turn's results (see the Luxury Peptide/purchase-order investigation).
 * The prompt below makes that requirement explicit rather than introducing a second axis of
 * judgment — this remains a single responsibility: is the evidence in front of me, whatever
 * its origin, sufficient to answer THIS question.
 *
 * <p><b>Result-set shape (conversational evidence-reuse correctness).</b> Being on-topic and
 * being the CORRECT RESULT SET are not the same thing, and conflating them was a second,
 * distinct defect from the one above: seeded evidence for the same subject as the current
 * question (e.g. "purchase orders" both times) can still be the wrong ROWS for it — the
 * question may ask for a filtered subset, a different grouping, a different sort order, a
 * different limit, or a different date range than what was already gathered. An LLM can often
 * compute or describe a correct-sounding answer by reading through such evidence anyway (e.g.
 * counting a value in a distribution) — that is exactly the trap: an answerable-sounding
 * response with the wrong result set displayed to the user.
 *
 * <p>A prose-only instruction against this was tried first and, live-validated against the real
 * model, was NOT reliably followed: asked to fold both checks into a single holistic {@code
 * decision} label, the model still answered {@code SUFFICIENT} for a filtered follow-up while
 * its own rationale repeated exactly the conflation the prose forbade ("...which is already
 * present in the result set"). The model is asked instead to report {@code resultSetMatches} as
 * its own separate, narrow yes/no judgment — decomposing the holistic decision into two explicit
 * sub-answers measurably improves compliance over trusting one label to encode both — and {@link
 * #evaluate} deterministically forces {@code NEED_MORE_DATA} whenever the model itself reports
 * {@code resultSetMatches=false}, regardless of what {@code decision} it also produced. This is a
 * narrow, explainable clamp on the model's own self-report, not a keyword/rule engine: it
 * inspects one boolean the model already computed, never the question or evidence text itself.
 * See {@code zevra-docs/docs/ai/conversational-evidence-reuse.md} for the full rule, the live
 * evidence that motivated this design, and worked examples.
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
            Some of that evidence may have been carried over from an earlier question in the
            same conversation, not gathered for the question you are evaluating now.

            Return JSON only:
            {
              "resultSetMatches": true,
              "decision": "SUFFICIENT | NEED_MORE_DATA | DEAD_END",
              "rationale": "one sentence explaining your decision"
            }

            Answer these as TWO SEPARATE questions — do not let one influence the other:

            1. resultSetMatches — are the rows ALREADY GATHERED literally the correct rows for
               THIS question, with no different filter, additional filter, subset, grouping,
               aggregation, sort order, top-N/limit, or date range/dimension needed? Answer
               false the moment the question implies ANY different scope of rows than what's
               already there — even if you personally could read through the existing rows and
               work out the right number or description. Being able to COMPUTE an answer from
               the existing rows is not the test; whether those exact rows are what should be
               RETURNED to the user is the test. A count, total, or description OF the existing
               rows, completely unchanged, is the only case where resultSetMatches is true for a
               follow-up that narrows, reorders, or otherwise reshapes what was gathered before.

            2. decision — is the evidence answerable and enough to proceed?

            A correct result-set shape is not the whole test. The evidence must also be
            SEMANTICALLY ANSWERABLE: sufficient to answer the question as phrased, not merely
            shaped like the right rows. An identifier that locates a record or entity is not
            necessarily sufficient to identify that entity in the form the user asked for. If
            the question asks you to identify, name, or describe an entity, and the evidence
            gathered contains only an opaque identifier for that entity — with no human-readable
            descriptive value for it — the evidence is not yet sufficient, even when its row
            shape is otherwise exactly correct. This applies however that identifier was
            learned, including from a JOIN/relationship reference — an identifier surfaced that
            way is still just an identifier, never a substitute for that entity's own descriptive
            evidence. Do not assume a conventional column name exists or invent one to fill this
            gap; this is about whether the evidence you actually have contains a presentable
            answer, not about guessing at a column. Do not require further evidence merely because
            an identifier is present — only require it when the question specifically asks to
            identify, name, or describe that entity and no descriptive value for it has actually
            been gathered yet.

            Decision guide:
            - SUFFICIENT    : the evidence collected is actually about what THIS question asks
                              (same entity/metric/business process), AND the rows already
                              gathered are themselves the correct result set for this question —
                              nothing about the question implies a different, filtered,
                              re-grouped, re-sorted, re-limited, or re-scoped set of rows than
                              what's already there — AND, when the question asks to identify,
                              name, or describe an entity, the evidence includes a presentable
                              value for it, not merely an opaque identifier. A pure count, total,
                              description, or explanation OF the existing rows, unchanged, still
                              qualifies.
            - NEED_MORE_DATA: the evidence is on-topic but either (a) a further targeted query
                              would materially improve the answer, (b) no evidence gathered so
                              far actually addresses this question's subject yet, so a first
                              query toward it is needed, (c) the question requires a different
                              result set (a filter, subset, grouping, sort order, limit, or scope
                              different from what's already gathered) even though the existing
                              evidence is about the right subject and could be read to compute an
                              answer, OR (d) the question asks to identify, name, or describe an
                              entity and the evidence gathered contains only an opaque identifier
                              for it, with no descriptive value yet gathered.
            - DEAD_END      : queries have been run TOWARD THIS QUESTION's subject — including,
                              when applicable, toward the specific result set or descriptive
                              evidence it requires — and the data needed is confirmed not
                              available or not accessible. Evidence that is simply about a
                              different subject, that has the right subject but the wrong
                              result-set shape, or that identifies an entity only by an opaque
                              identifier and has not yet been re-queried for its descriptive
                              evidence, is not a dead end — that has not been queried yet.

            Be decisive. Prefer SUFFICIENT over NEED_MORE_DATA when evidence that is actually
            about this question, AND is already the correct result set for it, is good enough
            for a meaningful business answer, even if not exhaustive. Being decisive is never a
            reason to accept off-topic evidence, the wrong result-set shape, or — when the
            question asks to identify, name, or describe an entity — an opaque identifier in
            place of that entity's own descriptive evidence. If resultSetMatches is false,
            decision can never be SUFFICIENT — say NEED_MORE_DATA (or DEAD_END only if that
            different result set is confirmed unobtainable).
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

            com.sei.nexus.ai.LlmCallTag.set("EVALUATOR");
            String raw  = aiClient.chat(List.of(ChatMessage.user(prompt)), SYSTEM_PROMPT);
            String json = extractJson(raw);
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            String  decision         = strOr(parsed, "decision",  "SUFFICIENT");
            String  rationale        = strOr(parsed, "rationale", "");
            Boolean resultSetMatches = boolOrNull(parsed, "resultSetMatches");

            // Deterministic clamp (conversational evidence-reuse correctness): the model's own
            // holistic `decision` label was live-validated to still say SUFFICIENT for a
            // filtered follow-up even when explicitly instructed not to — its rationale showed
            // it was computing an answer from the existing rows rather than checking whether
            // those rows are the ones that should be returned. Decomposing the judgment into a
            // separate, narrower `resultSetMatches` boolean and enforcing it here in code (never
            // trusting `decision` alone to have honored it) closes that gap without a second LLM
            // call, a keyword rule, or inspecting the question/evidence text itself — only the
            // model's own explicit self-report on this one question is read.
            if ("SUFFICIENT".equals(decision) && Boolean.FALSE.equals(resultSetMatches)) {
                log.debug("Evaluator said SUFFICIENT but resultSetMatches=false at step {}; "
                        + "overriding to NEED_MORE_DATA", evidence.stepCount());
                decision = "NEED_MORE_DATA";
            }

            log.debug("Evaluator decision after step {}: {} (resultSetMatches={}) — {}",
                    evidence.stepCount(), decision, resultSetMatches, rationale);
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

    /** {@code null} when the field is absent/unparseable — treated as "unknown" by {@link
     *  #evaluate}, which never overrides {@code decision} on an unknown value (only on an
     *  explicit {@code false}), so a model response that omits this field behaves exactly as
     *  it did before this field existed. */
    private Boolean boolOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        }
        return null;
    }
}
