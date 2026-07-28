package com.sei.nexus.strategy;

import java.util.Locale;

/**
 * How a request executes — the single decision owned by the {@link ExecutionStrategySelector},
 * the front door of the Unified Answer Engine. A strategy is a property of the request's
 * <em>execution characteristics</em> (reasoning complexity, autonomy) — never of data ownership,
 * tables, schema, or which agent could serve it.
 *
 * <p>Only {@link #CHAT} and {@link #AGENT} are conversationally wired today. {@link #REPORT},
 * {@link #EXECUTIVE_BRIEF} and {@link #WORKFLOW} exist in the type system for extensibility and
 * currently fall back to the CHAT seam until conversational routing is implemented for them.
 */
public enum ExecutionStrategy {
    CHAT,
    AGENT,
    REPORT,
    EXECUTIVE_BRIEF,
    WORKFLOW;

    /**
     * Parses a strategy label from an LLM/JSON value. Unknown or blank labels resolve to
     * {@link #CHAT} — the safe default (an unrecognised strategy must never escalate to AGENT).
     */
    public static ExecutionStrategy fromLabel(String raw) {
        if (raw == null || raw.isBlank()) return CHAT;
        try {
            return ExecutionStrategy.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CHAT;
        }
    }

    /** True when this strategy has a dedicated conversational execution path wired today. */
    public boolean isConversationallyWired() {
        return this == CHAT || this == AGENT;
    }
}
