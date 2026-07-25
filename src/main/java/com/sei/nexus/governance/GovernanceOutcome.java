package com.sei.nexus.governance;

/**
 * Immutable result of {@link SqlGovernancePipeline#governSql}: the governance facts
 * for one SQL step, and nothing else.
 *
 * <p>This model deliberately carries <em>only governance facts</em> — it does not
 * carry an {@link AuditContext}. {@link GovernanceAuditService} is the sole owner of
 * AuditContext construction; it builds one from this outcome plus the caller's runtime
 * facts (row count, execution time) before persisting. The pipeline neither executes
 * SQL nor persists audit; callers own both.
 *
 * @param verdict        coarse decision, mapped to preserve the conversational path's
 *                       route branching (BLOCK → {@code BLOCKED}, EXECUTE_ASYNC →
 *                       {@code ASYNC}, everything else → {@code EXECUTE})
 * @param reason         human-readable block reason; non-null only when {@code BLOCKED}
 * @param governedSql    SQL after govern → contract → RLS → masking; the string the
 *                       caller executes. {@code null} when {@code verdict != EXECUTE}
 * @param approvedSql    the post-govern, pre-protection SQL (row-limit applied); the
 *                       audit event's {@code original_sql}, preserving current behavior
 * @param classification governance classification (POINT_LOOKUP, BOUNDED_LIST, …)
 * @param route          raw governance route (EXECUTE_SYNC / EXECUTE_ASYNC / BLOCK /
 *                       ASK_FOR_FILTER), exposed so a caller may apply its own policy
 *                       without changing what was enforced
 * @param rowLimit       governance-computed row cap the caller passes to execution
 * @param executionKey   key of the {@code nexus_query_execution} row govern created;
 *                       the caller owns its RUNNING/SUCCESS/FAILED transitions
 * @param resolvedRole   the user's role resolved once, for the audit context
 * @param contractOutcome contract evaluation result; {@code null} when not reached
 * @param rlsOutcome      row-level-security result; {@code null} when not reached
 * @param maskingOutcome  column-masking result; {@code null} when not reached
 */
public record GovernanceOutcome(
        Verdict        verdict,
        String         reason,
        String         governedSql,
        String         approvedSql,
        String         classification,
        String         route,
        int            rowLimit,
        String         executionKey,
        String         resolvedRole,
        ContractResult contractOutcome,
        RlsResult      rlsOutcome,
        MaskResult     maskingOutcome
) {

    /** Coarse governance decision. */
    public enum Verdict { EXECUTE, BLOCKED, ASYNC }

    public boolean isExecute() { return verdict == Verdict.EXECUTE; }
    public boolean isBlocked() { return verdict == Verdict.BLOCKED; }
    public boolean isAsync()   { return verdict == Verdict.ASYNC; }

    // ── package-private factories (used by SqlGovernancePipeline) ───────────────

    static GovernanceOutcome blocked(String reason, String approvedSql, String classification,
                                     String route, int rowLimit, String executionKey,
                                     String resolvedRole, ContractResult contractOutcome) {
        return new GovernanceOutcome(Verdict.BLOCKED, reason, null, approvedSql, classification,
                route, rowLimit, executionKey, resolvedRole, contractOutcome, null, null);
    }

    static GovernanceOutcome async(String approvedSql, String classification, String route,
                                   int rowLimit, String executionKey, String resolvedRole) {
        return new GovernanceOutcome(Verdict.ASYNC, null, null, approvedSql, classification,
                route, rowLimit, executionKey, resolvedRole, null, null, null);
    }

    static GovernanceOutcome execute(String governedSql, String approvedSql, String classification,
                                     String route, int rowLimit, String executionKey,
                                     String resolvedRole, ContractResult contractOutcome,
                                     RlsResult rlsOutcome, MaskResult maskingOutcome) {
        return new GovernanceOutcome(Verdict.EXECUTE, null, governedSql, approvedSql, classification,
                route, rowLimit, executionKey, resolvedRole, contractOutcome, rlsOutcome, maskingOutcome);
    }
}
