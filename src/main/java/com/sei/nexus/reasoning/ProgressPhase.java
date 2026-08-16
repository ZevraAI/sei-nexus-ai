package com.sei.nexus.reasoning;

/**
 * Business-facing milestones of a chat turn, projected to the frontend as phase_started /
 * phase_completed events via {@link ReasoningEventBus}. This is the Runtime Progress
 * Projection: every value here must correspond to a real orchestration milestone in
 * ChatService — it is a projection of what actually happened, never a simulated or timed
 * animation. Internal names (Agent Brain, Execution Contract, SqlGovernancePipeline, Prompt
 * Assembly, ...) never appear here; those stay in developer diagnostics (evidence / audit
 * trail) only.
 *
 * <p>Not every phase fires on every turn — e.g. EXECUTION only fires for decisions that run
 * the governed SQL loop (QUERY_LIVE_DATA / HYBRID_DOC_AND_DATA). A skipped phase is not a
 * fabricated one: the frontend only ever sees phases that genuinely ran.
 *
 * <p>Future phases (evidence, provenance, technical diagnostics) can attach additional data
 * to these same phase_started / phase_completed events without changing this event model.
 */
public enum ProgressPhase {
    UNDERSTANDING("understanding", "Understanding your business question..."),
    METADATA("metadata", "Identifying relevant business concepts..."),
    RETRIEVAL("retrieval", "Finding enterprise information..."),
    EXECUTION("execution", "Reviewing enterprise data..."),
    REASONING("reasoning", "Forming business judgment..."),
    COMPOSITION("composition", "Preparing your answer...");

    public final String id;
    public final String label;

    ProgressPhase(String id, String label) {
        this.id = id;
        this.label = label;
    }
}
