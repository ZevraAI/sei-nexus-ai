package com.sei.nexus.strategy;

/**
 * The single front door of the Unified Answer Engine. Owns exactly one decision — the execution
 * {@link ExecutionStrategy} — and produces the canonical {@link RequestAnalysis} for a request.
 *
 * <p><b>Separation of responsibilities.</b> The selector decides <em>how</em> a request executes,
 * classifying on execution characteristics only (reasoning complexity, autonomy requirements). It
 * must never classify on data ownership, tables, schema, or agent capabilities — those belong to
 * the Agent Router, which decides <em>which</em> agent executes and runs only after the selector
 * has chosen {@link ExecutionStrategy#AGENT}.
 */
public interface ExecutionStrategySelector {

    /**
     * Produces the canonical analysis of a request. Never returns {@code null}; on any failure it
     * degrades to a safe {@link ExecutionStrategy#CHAT} analysis.
     *
     * @param question     the raw user question (no attachment/file content — intent only)
     * @param tenantSchema the active tenant schema, used only to check whether AGENT is even
     *                     reachable (an agent-less tenant can only be CHAT)
     */
    RequestAnalysis analyze(String question, String tenantSchema);
}
