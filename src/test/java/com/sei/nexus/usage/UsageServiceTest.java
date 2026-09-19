package com.sei.nexus.usage;

import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cost observability (cached_tokens + call_type) — {@link UsageService#record}. Hand-rolled
 * fake {@link UsageRepository} (constructed with a {@code null} JdbcTemplate, {@link #insert}
 * overridden to capture args instead of touching a real database), same no-Mockito/no-DB
 * convention as the rest of this codebase.
 *
 * <p>Proves: (a) {@code call_type} reaches {@link UsageRepository#insert} unchanged from
 * whatever the caller passed (never inferred), (b) the pre-existing cached-token cost discount
 * math is unchanged by adding call_type, (c) every record persists under the CALLING thread's
 * own {@link TenantContext} schema — never a different tenant's — so cost/usage rows can never
 * cross tenant boundaries, and (d) legacy overloads that don't know about call types still work
 * and persist {@code null} rather than guessing one.
 */
class UsageServiceTest {

    static class CapturingRepository extends UsageRepository {
        record Row(String tenantSchema, String userEmail, String feature, String agentName,
                   String model, int promptTokens, int completionTokens, double costUsd,
                   int cachedTokens, String callType) {}
        final List<Row> rows = new ArrayList<>();

        CapturingRepository() { super(null); }

        @Override
        public void insert(String tenantSchema, String userEmail, String feature,
                            String agentName, String model,
                            int promptTokens, int completionTokens, double costUsd,
                            int cachedTokens, String callType) {
            rows.add(new Row(tenantSchema, userEmail, feature, agentName, model,
                    promptTokens, completionTokens, costUsd, cachedTokens, callType));
        }
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        UsageContext.clear();
    }

    @Test
    void callTypePassesThroughUnchangedToTheRepository() {
        CapturingRepository repo = new CapturingRepository();
        UsageService service = new UsageService(repo);
        TenantContext.set("tenant_try_retail");

        service.record("gpt-4o", 6072, 128, 4500, "PLANNER");

        assertEquals(1, repo.rows.size());
        assertEquals("PLANNER", repo.rows.get(0).callType());
        assertEquals(4500, repo.rows.get(0).cachedTokens());
    }

    @Test
    void blankCallTypeIsPersistedAsNullNotAsTheLiteralBlankString() {
        CapturingRepository repo = new CapturingRepository();
        UsageService service = new UsageService(repo);
        TenantContext.set("tenant_try_retail");

        service.record("gpt-4o", 100, 10, 0, "   ");

        assertNull(repo.rows.get(0).callType());
    }

    @Test
    void legacyFourArgOverloadStillPersistsNullCallTypeRatherThanGuessingOne() {
        CapturingRepository repo = new CapturingRepository();
        UsageService service = new UsageService(repo);
        TenantContext.set("tenant_try_retail");

        service.record("gpt-4o", 100, 10, 30);

        assertNull(repo.rows.get(0).callType());
        assertEquals(30, repo.rows.get(0).cachedTokens());
    }

    @Test
    void costCalculationWithCachedTokensIsUnchangedByAddingCallType() {
        CapturingRepository repo = new CapturingRepository();
        UsageService service = new UsageService(repo);
        TenantContext.set("tenant_try_retail");

        // gpt-4o: $2.50/M input, $10.00/M output, 50% cached-input discount.
        // 1000 prompt tokens, 400 cached -> 600 uncached * 2.50e-6 + 400 * 2.50e-6 * 0.5
        //                                   + 50 completion * 10.00e-6
        service.record("gpt-4o", 1000, 50, 400, "EVALUATOR");

        double expected = 600 * (2.50 / 1_000_000.0)
                + 400 * (2.50 / 1_000_000.0) * 0.5
                + 50 * (10.00 / 1_000_000.0);
        assertEquals(expected, repo.rows.get(0).costUsd(), 1e-12);
    }

    // ── tenant isolation ─────────────────────────────────────────────────────────────────────────

    @Test
    void recordAlwaysPersistsUnderTheCallingThreadsOwnTenantSchema() {
        CapturingRepository repo = new CapturingRepository();
        UsageService service = new UsageService(repo);

        TenantContext.set("tenant_try_retail");
        service.record("gpt-4o", 10, 1, 0, "PLANNER");

        TenantContext.clear();
        TenantContext.set("tenant_maryland_corporations");
        service.record("gpt-4o", 20, 2, 0, "EVALUATOR");

        assertEquals("tenant_try_retail", repo.rows.get(0).tenantSchema());
        assertEquals("tenant_maryland_corporations", repo.rows.get(1).tenantSchema());
        assertNotEquals(repo.rows.get(0).tenantSchema(), repo.rows.get(1).tenantSchema(),
                "one tenant's usage call must never be attributed to another tenant's schema");
    }

    @Test
    void noTenantContextFallsBackToThePublicSchemaNeverAGuessedTenant() {
        CapturingRepository repo = new CapturingRepository();
        UsageService service = new UsageService(repo);
        // Deliberately no TenantContext.set(...) — simulates an unauthenticated/registry-level call.

        service.record("gpt-4o", 10, 1, 0, "PLANNER");

        assertEquals("public", repo.rows.get(0).tenantSchema());
    }
}
