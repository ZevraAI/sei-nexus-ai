package com.sei.nexus.governance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0003 A2 (Option C) — audit-content preservation for
 * {@link GovernanceAuditService#recordOutcome}.
 *
 * <p>Locks the behavior-preserving contract required by Commit 2: the audit event's
 * {@code original_sql} is the outcome's {@code approvedSql} (the post-govern,
 * pre-protection SQL) and {@code executed_sql} is the {@code governedSql} — exactly
 * as the previous inline {@code AuditContext} in ReasoningEngine produced. The latent
 * "original_sql should be the raw model SQL" fix is deliberately NOT applied here.
 *
 * <p>Uses a hand-rolled repository fake that captures the persisted event; @Async is a
 * no-op without a Spring proxy, so recordOutcome runs synchronously in-test.
 */
class GovernanceAuditRecordOutcomeTest {

    static class CapturingRepo extends AuditEventRepository {
        AuditEvent saved;
        CapturingRepo() { super(null); }
        @Override public void save(AuditEvent e) { this.saved = e; }
    }

    private final CapturingRepo repo = new CapturingRepo();
    private final GovernanceAuditService service =
            new GovernanceAuditService(repo, null, null);

    private static final String APPROVED = "SELECT id, email FROM users LIMIT 100";
    private static final String GOVERNED = "SELECT id, MD5(email) AS email FROM users LIMIT 100"
            + " WHERE (region = 'X')";

    // ── success: original_sql = approvedSql, executed_sql = governedSql ─────────

    @Test
    void successEventUsesApprovedSqlAsOriginalAndGovernedAsExecuted() {
        GovernanceOutcome outcome = new GovernanceOutcome(
                GovernanceOutcome.Verdict.EXECUTE, null, GOVERNED, APPROVED,
                "BOUNDED_LIST", "EXECUTE_SYNC", 100, "exec-1", "analyst",
                ContractResult.passed(List.of("c1")),
                new RlsResult(GOVERNED, List.of("rls_region"), List.of("region = 'X'"), true),
                new MaskResult(GOVERNED, List.of("email"), true));

        service.recordOutcome(outcome, "u@x.com", "run-1", "conn-1",
                List.of("k1"), 7, 42, false);

        AuditEvent e = repo.saved;
        assertNotNull(e, "an audit event is persisted");
        assertEquals(APPROVED, e.originalSql(), "original_sql preserved as approvedSql");
        assertEquals(GOVERNED, e.executedSql(), "executed_sql is governedSql");
        assertEquals("analyst", e.userRole());
        assertEquals("run-1", e.runKey());
        assertEquals(Integer.valueOf(7), e.rowCountReturned());
        assertEquals(Integer.valueOf(42), e.executionMs());
        assertArrayEquals(new String[]{"email"}, e.columnsMasked());
        assertArrayEquals(new String[]{"rls_region"}, e.rlsPoliciesApplied());
        assertEquals("COLUMN_MASKED", e.eventType());
    }

    // ── contract block: executed_sql falls back to approvedSql, ACCESS_DENIED ───

    @Test
    void contractBlockEventFallsBackToApprovedSqlAndIsAccessDenied() {
        GovernanceOutcome outcome = GovernanceOutcome.blocked(
                "PII contract violated", APPROVED, "BOUNDED_LIST", "EXECUTE_SYNC",
                100, "exec-1", "analyst",
                new ContractResult(ContractResult.ContractStatus.BLOCKED,
                        List.of("pii_contract"), List.of("pii_contract"),
                        List.of("PII contract violated"), null));

        service.recordOutcome(outcome, "u@x.com", "run-1", "conn-1",
                List.of("k1"), null, null, true);

        AuditEvent e = repo.saved;
        assertEquals(APPROVED, e.originalSql());
        assertEquals(APPROVED, e.executedSql(),
                "no SQL executed on a block → executed_sql falls back to approvedSql");
        assertNull(e.rowCountReturned());
        assertNull(e.executionMs());
        assertArrayEquals(new String[]{"pii_contract"}, e.contractsViolated());
        assertEquals(0, e.columnsMasked().length, "no masking applied on a contract block");
        assertEquals("ACCESS_DENIED", e.eventType());
    }
}
