package com.sei.nexus.ai;

/**
 * Zevra Cognitive Runtime baseline — measurement-only instrumentation, no behavior change.
 *
 * <p>A thread-local label the caller sets immediately before invoking {@link AzureOpenAiClient},
 * so the client's single shared usage-recording path can attribute its
 * {@code LLM_METRIC} log line to the correct call type (e.g. {@code "PLANNER"},
 * {@code "STAGE1_CONCEPT_SELECTION"}) without adding a parameter to every call-site method
 * signature on the hot path. This is purely observational: nothing reads the tag to make a
 * decision, route a request, or alter behavior — it only labels a metrics log line.
 *
 * <p>Every chat request in this codebase's current call graph is synchronous on the request
 * thread (confirmed by the Zevra Cognitive Runtime investigation report), so a plain
 * {@link ThreadLocal} is sufficient — no propagation across executor threads is attempted or
 * needed. {@link AzureOpenAiClient} clears the tag after every call (success or failure) so it
 * never leaks into an unrelated subsequent call on the same thread.
 */
public final class LlmCallTag {

    private static final ThreadLocal<String> TAG = new ThreadLocal<>();
    private static final String UNTAGGED = "UNTAGGED";

    private LlmCallTag() { }

    /** Sets the label for the next {@link AzureOpenAiClient} call on this thread. */
    public static void set(String callType) {
        TAG.set(callType);
    }

    /** The current label, or {@code "UNTAGGED"} if no caller set one (never null). */
    public static String get() {
        String tag = TAG.get();
        return tag != null ? tag : UNTAGGED;
    }

    /** Clears the label. Always called by {@link AzureOpenAiClient} after recording a call. */
    public static void clear() {
        TAG.remove();
    }
}
