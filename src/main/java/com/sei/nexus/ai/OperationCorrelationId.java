package com.sei.nexus.ai;

/**
 * Zevra Cognitive Runtime baseline — measurement-only instrumentation, no behavior change.
 *
 * <p>A thread-local correlation id, set by the calling service from an identifier it already
 * owns (an onboarding {@code jobId}, a chat {@code runKey}, etc. — never a newly-minted id: see
 * the baseline report's Section 7), so every {@code LLM_METRIC}/{@code FILE_SEARCH_METRIC} log
 * line emitted while handling one operation can be grepped/grouped together after the fact.
 * Purely observational — nothing reads this value to make a decision or alter behavior.
 *
 * <p>Unlike {@link LlmCallTag} (set immediately before each individual call, on the same thread
 * that makes it), this is set once per logical operation and may need re-setting per thread when
 * that operation fans out onto a worker-pool thread (e.g. one onboarding job's per-batch
 * {@code CompletableFuture.runAsync} tasks) — callers that already re-establish {@code
 * TenantContext} on a new thread are the same call sites that should re-set this alongside it.
 */
public final class OperationCorrelationId {

    private static final ThreadLocal<String> ID = new ThreadLocal<>();

    private OperationCorrelationId() { }

    /** Sets the correlation id for every {@link AzureOpenAiClient} call on this thread until cleared. */
    public static void set(String id) {
        ID.set(id);
    }

    /** The current correlation id, or {@code null} if none was set (never fabricated). */
    public static String get() {
        return ID.get();
    }

    public static void clear() {
        ID.remove();
    }
}
