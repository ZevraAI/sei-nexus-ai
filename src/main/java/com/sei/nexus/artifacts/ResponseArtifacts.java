package com.sei.nexus.artifacts;

import java.util.List;
import java.util.Map;

/**
 * Zevra Intelligence Response Artifacts — a generalized, optional decomposition of what Zevra
 * actually produced while answering a question, shared by every execution path (direct
 * conversational chat, a Zevra Agent, and any future workflow). This is deliberately NOT an
 * "investigation" model tied to one page: a simple factual question may populate only
 * `understanding`; a multi-step investigation may populate findings, evidence, metrics, and a
 * full trail. Every field is optional (null or empty when not applicable) — nothing here is
 * fabricated to fill a UI section. See {@link ResponseArtifactsBuilder} for how each field is
 * derived, always from information the execution path already produced.
 *
 * Additive to {@code ChatResponse} — a nullable field. Existing consumers that don't read
 * {@code artifacts} are completely unaffected; a caller with no meaningful artifacts to report
 * (e.g. a knowledge-gap acknowledgement) simply omits this field (null).
 */
public record ResponseArtifacts(
        // Zevra's own paraphrase of what it found — the answer's opening statement, not the raw
        // question restated. Null only when there is no answer text to derive it from.
        String understanding,

        // Genuine discoveries backed by the answer's own text or the evaluator's own judgment
        // calls — never invented. Empty when the answer/trace didn't state anything quantified.
        List<String> keyFindings,

        // Remaining declarative statements from the answer not already surfaced as
        // understanding or a key finding. Empty when there's nothing left over.
        List<String> relatedFacts,

        // The single best "you should..." sentence the answer itself stated, verbatim. Null
        // when the answer never made a recommendation.
        String recommendation,

        // Actionable follow-up prompts the platform already computed for this decision type
        // (e.g. "Show exceptions only") — never fabricated per-answer, just carried through.
        List<Recommendation> nextSteps,

        // The model's own UI-content plan (see StructuredAnswer.Section javadoc), resolved: each
        // DATASET-type entry carries the actual rows of the investigation dataset the model
        // referenced (by its Java-assigned step-N identifier), looked up by exact match only —
        // see ChatService#resolveSections. Empty when the model didn't populate `sections` (a
        // response that never went through the sections-based contract, or the Zevra Agent path).
        // Distinct from `evidence`/`metrics` below: this is explicitly LLM-authored content;
        // `evidence`/`metrics` remain 100% deterministic/runtime-owned, as documented on this
        // class already — the two are never blended.
        List<Section> sections,

        // What kind of evidence exists to support the answer, and (for a chart) enough of a
        // hint that the frontend doesn't have to reverse-engineer chart type from raw rows.
        List<Evidence> evidence,

        // Generic, real-data metric tiles — every figure here is literally present in the
        // dataset; nothing inferred about what the columns mean.
        List<Metric> metrics,

        // The investigation/execution trail, normalized across the conversational reasoning
        // path (resolution / literal / SQL step) and the Zevra Agent ReAct loop (context
        // resolve / tool call / final answer) into one shared shape.
        List<TrailStep> trail,

        // Present only when this answer came from a routed Zevra Agent; null for direct chat.
        AgentContext agentContext
) {
    /** An actionable follow-up the user can trigger directly (label shown, prompt sent as-is). */
    public record Recommendation(String label, String prompt) {}

    /**
     * One resolved entry of the model's UI-content plan — see {@code StructuredAnswer.Section}
     * for the field-by-field semantics (this is that same shape, post-resolution). The one
     * difference: {@code datasetRefs} (bare {@code step-N} strings, internal identifiers) is
     * replaced by {@code datasets} (the actual resolved datasets, each still under its own step
     * identity) — the frontend renders content, it does not need to know about step identifiers,
     * but it DOES need to know which rows belong to which investigation dataset, since a section
     * may be grounded in more than one and they are never merged.
     *
     * <p>{@code datasets} is non-empty whenever the model's section carried one or more {@code
     * datasetRefs} — not only for {@code type=DATASET}: a {@code HIGHLIGHT} section grounding its
     * narrative ({@code content}) in two datasets carries both {@code content} and both resolved
     * {@code ResolvedDataset} entries together, each with its own rows, so the UI/audit trail can
     * show exactly what the claim is backed by without flattening them into one table. {@code
     * datasets} is empty only for a section that never had a {@code datasetRefs} to begin with.
     * A section any of whose {@code datasetRefs} did not match a real investigation dataset is
     * dropped ENTIRELY (narrative content included) before reaching this record — see {@code
     * ChatService#resolveSections} — it never appears here partially resolved or with a
     * substituted dataset.
     */
    public record Section(String type, String title, String purpose, Boolean display,
                           List<String> items, String content, List<ResolvedDataset> datasets) {

        /** One dataset a section is grounded in, preserved under its own step identity — never
         *  merged with another dataset even when a section references more than one. */
        public record ResolvedDataset(int stepNo, List<Map<String, Object>> rows) {}
    }

    /**
     * A piece of evidence backing the answer. {@code kind} is one of DATASET | CHART | METRIC.
     * {@code chartType}/{@code xKey}/{@code yKeys} are populated only for kind=CHART, mirroring
     * the same chart-shape decision (stats / area / bar) the platform already makes.
     */
    public record Evidence(String kind, String label, String chartType, String xKey,
                            List<String> yKeys, int rowCount) {}

    /** A single real figure drawn directly from returned data — label plus its literal value. */
    public record Metric(String label, String value) {}

    /**
     * One normalized step of the trail. {@code type} is one of RESOLUTION | LITERAL | SQL_STEP
     * (conversational path) or TOOL_CALL | FINAL_ANSWER (Zevra Agent path) — the same
     * vocabulary regardless of which execution path produced the trail.
     */
    public record TrailStep(String type, String label, String detail, String outcome) {}

    /** Which agent handled this turn, and its session — lets the UI fetch the full tool trace. */
    public record AgentContext(String agentKey, String agentName, String sessionId,
                                Integer iterationsUsed) {}
}
