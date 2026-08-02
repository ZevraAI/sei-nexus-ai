package com.sei.nexus.strategy;

/**
 * The canonical front-door understanding of a request, produced exactly once by the
 * {@link ExecutionStrategySelector}. It is the single source of truth for how a request executes
 * ({@link #strategy}) and its shape ({@link #intentType}); the conversational pipeline consumes the
 * {@code intentType} carried here instead of independently re-deriving request shape.
 *
 * <p>Immutable. Designed for forward extension: new understanding can be added as additional fields
 * with backward-compatible factory methods, without changing the downstream APIs that read the
 * existing fields.
 *
 * @param strategy   how the request should execute (the selector's one decision)
 * @param intentType the request's shape, computed once and reused
 * @param confidence the selector's confidence in {@code [0.0, 1.0]}
 * @param reasoning  a one-line rationale (diagnostic; never user-facing)
 */
public record RequestAnalysis(ExecutionStrategy strategy,
                              IntentType intentType,
                              double confidence,
                              String reasoning) {

    public RequestAnalysis {
        // Null-safety: a malformed analysis must degrade to the safe CHAT default, never to null.
        if (strategy == null)   strategy = ExecutionStrategy.CHAT;
        if (intentType == null) intentType = IntentType.UNKNOWN;
        if (reasoning == null)  reasoning = "";
    }

    /** A deterministic CHAT analysis — the safe default for fallbacks and agent-less tenants. */
    public static RequestAnalysis chat(String reasoning) {
        return new RequestAnalysis(ExecutionStrategy.CHAT, IntentType.UNKNOWN, 1.0, reasoning);
    }
}
