package com.sei.nexus.governance;

import com.sei.nexus.query.QueryGovernanceService;
import com.sei.nexus.query.QueryGovernanceService.GovernanceResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0003 A2 (Option C) — the shared {@link SqlGovernancePipeline}.
 *
 * <p>Verifies the pipeline reproduces the conversational path's govern → contract →
 * RLS → masking sequence as a pure function returning governance facts, with correct
 * verdict mapping and stage ordering. Hand-rolled fakes record their inputs so the
 * chaining (each stage feeds the next) is asserted, not assumed. No database, no
 * Spring, no Mockito.
 */
class SqlGovernancePipelineTest {

    // ── fakes ──────────────────────────────────────────────────────────────────

    static class FakeGovernance extends QueryGovernanceService {
        GovernanceResult result;
        FakeGovernance() { super(null, null, null, null, null, null); }
        @Override public GovernanceResult govern(String runKey, int stepNo, String agentKey,
                String connectionKey, String objectKeys, String sql, boolean forceAsync) {
            return result;
        }
    }

    static class FakeContract extends DataContractService {
        String receivedSql;
        List<String> receivedKeys;
        ContractResult result = ContractResult.passed(List.of("c1"));
        boolean invoked = false;
        FakeContract() { super(null); }
        @Override public ContractResult evaluate(String sql, List<String> objectKeys) {
            invoked = true; receivedSql = sql; receivedKeys = objectKeys;
            return result;
        }
    }

    static class FakeRls extends RowLevelSecurityService {
        String receivedSql;
        boolean invoked = false;
        FakeRls() { super(null, null); }
        @Override public RlsResult apply(String sql, String userEmail, List<String> objectKeys) {
            invoked = true; receivedSql = sql;
            return new RlsResult(sql + " /*rls*/", List.of("rls_pol"), List.of("region = 'X'"), true);
        }
    }

    static class FakeMask extends ColumnMaskingService {
        String receivedSql;
        boolean invoked = false;
        FakeMask() { super(null, null); }
        @Override public MaskResult apply(String sql, String userEmail, List<String> objectKeys) {
            invoked = true; receivedSql = sql;
            return new MaskResult(sql + " /*mask*/", List.of("email"), true);
        }
    }

    static class FakeUserAttrs extends UserAttributesRepository {
        FakeUserAttrs() { super(null, null); }
        @Override public String getRole(String userEmail) { return "analyst"; }
    }

    private final FakeGovernance governance = new FakeGovernance();
    private final FakeContract   contract   = new FakeContract();
    private final FakeRls        rls        = new FakeRls();
    private final FakeMask       mask       = new FakeMask();

    private SqlGovernancePipeline pipeline() {
        return new SqlGovernancePipeline(governance, contract, rls, mask, new FakeUserAttrs());
    }

    private static GovernanceResult gov(String route, String approvedSql) {
        return new GovernanceResult("exec-1", "BOUNDED_LIST", route, "LOW",
                approvedSql, "reason text", 5, 100, 30);
    }

    // ── EXECUTE: all stages run, in order ──────────────────────────────────────

    @Test
    void executeVerdictRunsAllStagesInOrderAndReturnsGovernedSql() {
        governance.result = gov("EXECUTE_SYNC", "SELECT id FROM orders LIMIT 100");

        GovernanceOutcome outcome = pipeline().governSql(
                "run-1", 1, "conn-1", "k1,k2", "SELECT id FROM orders", "u@x.com", false);

        assertEquals(GovernanceOutcome.Verdict.EXECUTE, outcome.verdict());
        assertEquals("SELECT id FROM orders LIMIT 100 /*rls*/ /*mask*/", outcome.governedSql());
        assertEquals("SELECT id FROM orders LIMIT 100", outcome.approvedSql(),
                "approvedSql is govern's approved SQL — the audit event's original_sql");
        assertEquals("analyst", outcome.resolvedRole());
        assertEquals("BOUNDED_LIST", outcome.classification());
        assertEquals("EXECUTE_SYNC", outcome.route());
        assertEquals(100, outcome.rowLimit());
        assertEquals("exec-1", outcome.executionKey());
        assertNotNull(outcome.contractOutcome());
        assertNotNull(outcome.rlsOutcome());
        assertNotNull(outcome.maskingOutcome());

        // Ordering: contract sees govern's approvedSql; rls sees contract.effectiveSql;
        // mask sees rls output.
        assertEquals("SELECT id FROM orders LIMIT 100", contract.receivedSql);
        assertEquals(List.of("k1", "k2"), contract.receivedKeys, "object keys parsed and passed");
        assertEquals("SELECT id FROM orders LIMIT 100", rls.receivedSql);
        assertEquals("SELECT id FROM orders LIMIT 100 /*rls*/", mask.receivedSql);
    }

    // ── BLOCK route: terminal before protective stages ─────────────────────────

    @Test
    void governBlockRouteReturnsBlockedAndSkipsContractRlsMasking() {
        governance.result = gov("BLOCK", "SELECT id FROM orders");
        // decisionReason on the fake result is "reason text"

        GovernanceOutcome outcome = pipeline().governSql(
                "run-1", 1, "conn-1", "", "UPDATE orders SET x=1", "u@x.com", false);

        assertEquals(GovernanceOutcome.Verdict.BLOCKED, outcome.verdict());
        assertEquals("reason text", outcome.reason());
        assertNull(outcome.governedSql());
        assertFalse(contract.invoked, "contract must not run after a govern BLOCK");
        assertFalse(rls.invoked);
        assertFalse(mask.invoked);
    }

    // ── contract block: blocked, contract outcome retained, RLS/mask skipped ────

    @Test
    void contractBlockReturnsBlockedWithContractOutcomeAndSkipsRlsMasking() {
        governance.result = gov("EXECUTE_SYNC", "SELECT id FROM orders LIMIT 100");
        contract.result = new ContractResult(ContractResult.ContractStatus.BLOCKED,
                List.of("c1"), List.of("c1"), List.of("PII contract violated"), null);

        GovernanceOutcome outcome = pipeline().governSql(
                "run-1", 1, "conn-1", "k1", "SELECT id FROM orders", "u@x.com", false);

        assertEquals(GovernanceOutcome.Verdict.BLOCKED, outcome.verdict());
        assertEquals("PII contract violated", outcome.reason());
        assertNotNull(outcome.contractOutcome(), "contract outcome kept for the audit event");
        assertNull(outcome.governedSql());
        assertTrue(contract.invoked);
        assertFalse(rls.invoked, "RLS must not run after a contract block");
        assertFalse(mask.invoked);
    }

    // ── ASYNC route: terminal, no protective stages ────────────────────────────

    @Test
    void asyncRouteReturnsAsyncVerdictAndSkipsProtectiveStages() {
        governance.result = gov("EXECUTE_ASYNC", "SELECT id FROM orders LIMIT 100");

        GovernanceOutcome outcome = pipeline().governSql(
                "run-1", 1, "conn-1", "k1", "SELECT id FROM orders", "u@x.com", true);

        assertEquals(GovernanceOutcome.Verdict.ASYNC, outcome.verdict());
        assertEquals("exec-1", outcome.executionKey());
        assertNull(outcome.governedSql());
        assertFalse(contract.invoked);
        assertFalse(rls.invoked);
        assertFalse(mask.invoked);
    }
}
