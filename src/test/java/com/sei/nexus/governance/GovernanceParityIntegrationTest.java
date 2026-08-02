package com.sei.nexus.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.connection.NexusConnection;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.query.QueryExecution;
import com.sei.nexus.query.QueryExecutionRepository;
import com.sei.nexus.query.QueryGovernanceService;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.sql.SqlSafetyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0003 A2 (Option C) — governance parity between the agent and conversational
 * paths, proven end-to-end through the <b>real</b> {@link SqlGovernancePipeline} wired
 * to the real {@link QueryGovernanceService}, {@link DataContractService},
 * {@link RowLevelSecurityService}, and {@link ColumnMaskingService}. Only the policy
 * sources (repositories) and the connection/execution/enterprise stores are faked.
 *
 * <p>Because both engines now invoke the identical {@code governSql}, parity is
 * structural. These tests demonstrate it concretely: for the same SQL, identity, and
 * policies, the agent input shape ({@code objectKeys=""}) and the conversational input
 * shape ({@code objectKeys="obj-orders"}) yield equivalent {@link GovernanceOutcome}
 * values across classification, routing, row limit, contract enforcement, RLS, masking,
 * and audit attribution.
 *
 * <p>Note (verified): contract, RLS, and masking are object-key-independent (they load
 * all active tenant policies and self-guard by SQL content), and classification here is
 * SQL-shape-driven, so the two input shapes are equivalent. Object metadata can only
 * make the conversational path <em>more</em> restrictive (e.g. large-table →
 * HIGH_RISK_SCAN); it never makes the agent less governed on enforcement.
 */
class GovernanceParityIntegrationTest {

    private static final String CONN = "conn-1";
    private static final String USER = "u@x.com";

    // ── faked policy sources / stores ──────────────────────────────────────────

    static class FakeDynamicSql extends DynamicSqlService {
        FakeDynamicSql() { super(null); }
        @Override public long estimateRowCount(String connectionKey, String sql) { return 10L; }
    }

    static class FakeExecRepo extends QueryExecutionRepository {
        FakeExecRepo() { super(null); }
        @Override public void save(QueryExecution qe) { /* no-op */ }
    }

    static class FakeConnRepo extends ConnectionRepository {
        FakeConnRepo() { super(null); }
        @Override public Optional<NexusConnection> findByKeyOrName(String value) {
            return Optional.of(new NexusConnection(CONN, "DB", "POSTGRES", "",
                    "jdbc:postgresql://x/db", null, "u", "s", "", "", true,
                    null, null, null, "ACTIVE", Instant.now(), Instant.now()));
        }
    }

    static class FakeEnterpriseRepo extends EnterpriseMapRepository {
        FakeEnterpriseRepo() { super(null); }
        @Override public Optional<com.sei.nexus.enterprise.DataObject> findDataObjectByKey(String key) {
            return Optional.empty();   // object metadata does not refine this SQL's classification
        }
    }

    static class FakeRlsRepo extends RlsPolicyRepository {
        List<RlsPolicy> policies = new ArrayList<>();
        FakeRlsRepo() { super(null); }
        @Override public List<RlsPolicy> findAll() { return policies; }
    }

    static class FakeColRepo extends ColumnPolicyRepository {
        List<ColumnPolicy> policies = new ArrayList<>();
        FakeColRepo() { super(null); }
        @Override public List<ColumnPolicy> findAll() { return policies; }
    }

    static class FakeContractRepo extends DataContractRepository {
        List<DataContract> contracts = new ArrayList<>();
        FakeContractRepo() { super(null, null); }
        @Override public List<DataContract> findAll() { return contracts; }
    }

    static class FakeUserAttrs extends UserAttributesRepository {
        FakeUserAttrs() { super(null, null); }
        @Override public String getRole(String userEmail) { return "analyst"; }
        @Override public Map<String, String> getAttributes(String userEmail) { return Map.of(); }
    }

    static class CapturingAuditRepo extends AuditEventRepository {
        AuditEvent saved;
        CapturingAuditRepo() { super(null); }
        @Override public void save(AuditEvent e) { this.saved = e; }
    }

    // ── real chain under test ──────────────────────────────────────────────────

    private FakeRlsRepo         rlsRepo;
    private FakeColRepo         colRepo;
    private FakeContractRepo    contractRepo;
    private CapturingAuditRepo  auditRepo;
    private SqlGovernancePipeline pipeline;
    private GovernanceAuditService auditService;

    @BeforeEach
    void setUp() throws Exception {
        FakeUserAttrs userAttrs = new FakeUserAttrs();
        rlsRepo      = new FakeRlsRepo();
        colRepo      = new FakeColRepo();
        contractRepo = new FakeContractRepo();
        auditRepo    = new CapturingAuditRepo();

        QueryGovernanceService govern = new QueryGovernanceService(
                new SqlSafetyService(), new FakeDynamicSql(), new FakeExecRepo(),
                new FakeConnRepo(), new FakeEnterpriseRepo(), new ObjectMapper());
        // @Value fields are not injected outside Spring — set realistic defaults.
        setLong(govern, "maxSyncRows", 500L);
        setLong(govern, "maxAsyncRows", 10000L);
        setInt(govern, "syncTimeout", 30);
        setInt(govern, "asyncTimeout", 90);
        setInt(govern, "pointLookupTimeout", 10);
        setInt(govern, "maxJoins", 4);
        setInt(govern, "defaultRowLimit", 100);
        setInt(govern, "maxRowLimit", 500);

        pipeline = new SqlGovernancePipeline(
                govern,
                new DataContractService(contractRepo),
                new RowLevelSecurityService(rlsRepo, userAttrs),
                new ColumnMaskingService(colRepo, userAttrs),
                userAttrs);
        auditService = new GovernanceAuditService(auditRepo, null, null);

        // Default policy set: one RLS policy (all roles) + one masking policy.
        rlsRepo.policies.add(new RlsPolicy("rls-1", "tenant-isolation", "obj-orders",
                "tenant_id = {user.email}", new String[0], true, "admin", Instant.now(), Instant.now()));
        colRepo.policies.add(new ColumnPolicy("col-1", "obj-orders", "email", "HASH",
                null, 3, new String[0], "admin", Instant.now(), Instant.now()));
    }

    private GovernanceOutcome asAgent(String sql) {
        return pipeline.governSql("run-1", 1, CONN, "", sql, USER, false);
    }

    private GovernanceOutcome asConversational(String sql) {
        return pipeline.governSql("run-1", 1, CONN, "obj-orders", sql, USER, false);
    }

    // ── parity: EXECUTE with RLS + masking + classification/route/rowLimit ──────

    @Test
    void agentAndConversationalProduceEquivalentGovernedOutcomes() {
        String sql = "SELECT id, email FROM users";

        GovernanceOutcome agent = asAgent(sql);
        GovernanceOutcome conv  = asConversational(sql);

        // The real chain actually fired (not a passthrough)
        assertEquals(GovernanceOutcome.Verdict.EXECUTE, agent.verdict());
        assertTrue(agent.governedSql().contains("tenant_id = 'u@x.com'"), "RLS injected: " + agent.governedSql());
        assertTrue(agent.governedSql().contains("MD5(CAST(email AS TEXT))"), "masking applied: " + agent.governedSql());
        assertTrue(agent.governedSql().contains("LIMIT 100"), "row limit applied: " + agent.governedSql());
        assertEquals("BOUNDED_LIST", agent.classification());
        assertEquals("EXECUTE_SYNC", agent.route());
        assertEquals(100, agent.rowLimit());
        assertEquals(List.of("tenant-isolation"), agent.rlsOutcome().policiesApplied());
        assertEquals(List.of("email"), agent.maskingOutcome().maskedColumns());

        // Full-field parity between the two paths
        assertEquals(agent.verdict(),        conv.verdict());
        assertEquals(agent.classification(), conv.classification());
        assertEquals(agent.route(),          conv.route());
        assertEquals(agent.rowLimit(),       conv.rowLimit());
        assertEquals(agent.governedSql(),    conv.governedSql());
        assertEquals(agent.approvedSql(),    conv.approvedSql());
        assertEquals(agent.resolvedRole(),   conv.resolvedRole());
        assertEquals(agent.rlsOutcome().policiesApplied(),      conv.rlsOutcome().policiesApplied());
        assertEquals(agent.maskingOutcome().maskedColumns(),    conv.maskingOutcome().maskedColumns());
        assertEquals(agent.contractOutcome().contractsChecked(),conv.contractOutcome().contractsChecked());
    }

    // ── parity: contract BLOCK identical on both paths ──────────────────────────

    @Test
    void contractBlockIsIdenticalOnBothPaths() {
        contractRepo.contracts.add(new DataContract("c-1", "no-full-scan", "obj-orders",
                "BLOCK_FULL_SCAN", new ObjectMapper().createObjectNode(), "BLOCK",
                true, "admin", Instant.now(), Instant.now()));
        String sql = "SELECT id FROM users";   // no WHERE → full scan → blocked

        GovernanceOutcome agent = asAgent(sql);
        GovernanceOutcome conv  = asConversational(sql);

        assertEquals(GovernanceOutcome.Verdict.BLOCKED, agent.verdict());
        assertEquals(GovernanceOutcome.Verdict.BLOCKED, conv.verdict());
        assertEquals(agent.reason(), conv.reason());
        assertTrue(agent.reason().contains("Full table scan blocked"), agent.reason());
        assertNotNull(agent.contractOutcome(), "contract outcome carried for the audit event");
        assertNull(agent.governedSql(), "blocked query is not executed");
    }

    // ── parity: audit attribution identical on both paths ───────────────────────

    @Test
    void auditAttributionIsIdenticalOnBothPaths() {
        String sql = "SELECT id, email FROM users";
        GovernanceOutcome agent = asAgent(sql);
        GovernanceOutcome conv  = asConversational(sql);

        auditService.recordOutcome(agent, USER, "run-1", CONN, List.of(), 5, 12, false);
        AuditEvent agentEvent = auditRepo.saved;
        auditService.recordOutcome(conv, USER, "run-1", CONN, List.of(), 5, 12, false);
        AuditEvent convEvent = auditRepo.saved;

        // Attribution (who/where/what) is identical
        assertEquals("run-1", agentEvent.runKey());
        assertEquals(agentEvent.runKey(),     convEvent.runKey());
        assertEquals(USER,    agentEvent.userEmail());
        assertEquals("analyst", agentEvent.userRole());
        assertEquals(agentEvent.userRole(),   convEvent.userRole());
        assertArrayEquals(new String[]{"email"}, agentEvent.columnsMasked());
        assertArrayEquals(new String[]{"tenant-isolation"}, agentEvent.rlsPoliciesApplied());
        assertArrayEquals(agentEvent.columnsMasked(),       convEvent.columnsMasked());
        assertArrayEquals(agentEvent.rlsPoliciesApplied(),  convEvent.rlsPoliciesApplied());
        assertEquals(agentEvent.originalSql(), convEvent.originalSql());
        assertEquals(agentEvent.executedSql(), convEvent.executedSql());
        assertEquals("COLUMN_MASKED", agentEvent.eventType());
    }

    // ── reflection helpers for @Value fields ────────────────────────────────────

    private static void setLong(Object target, String name, long value) throws Exception {
        Field f = QueryGovernanceService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setLong(target, value);
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        Field f = QueryGovernanceService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(target, value);
    }
}
