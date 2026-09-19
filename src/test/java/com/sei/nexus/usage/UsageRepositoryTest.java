package com.sei.nexus.usage;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cost observability (cached_tokens + call_type) — {@link UsageRepository}. Hand-rolled fake,
 * no real database: {@link JdbcTemplate#update(String, Object...)} and {@link
 * JdbcTemplate#queryForList(String, Object...)} are overridden to capture the exact SQL/args
 * issued, matching this project's no-Mockito/no-DB unit-test convention (see {@code
 * LearnedMappingRepositoryConceptKeyTest}).
 *
 * <p>What this proves: (a) {@code cached_tokens}/{@code call_type} reach the INSERT statement
 * and are persisted alongside every pre-existing column, (b) the pre-existing 8-arg {@code
 * insert} overload still works and defaults the two new columns to the documented safe values
 * (0 / SQL NULL) rather than breaking, (c) every tenant-scoped summary query still parameterizes
 * on the CALLING tenant's own schema — the new {@code call_type} breakdown query never reads or
 * mixes another tenant's rows, and (d) the platform-wide breakdown is deliberately unscoped,
 * exactly like the pre-existing {@code allTenantsSummary} it mirrors (tenant isolation for that
 * one is enforced by {@code UsageController.requirePlatformAdmin()}, not by this repository).
 */
class UsageRepositoryTest {

    static class CapturingJdbcTemplate extends JdbcTemplate {
        String lastUpdateSql;
        Object[] lastUpdateArgs;
        String lastQuerySql;
        Object[] lastQueryArgs;
        final List<Map<String, Object>> scriptedRows = List.of();

        @Override
        public int update(String sql, Object... args) {
            lastUpdateSql = sql;
            lastUpdateArgs = args;
            return 1;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            lastQuerySql = sql;
            lastQueryArgs = args;
            return scriptedRows;
        }
    }

    // ── insert: cached_tokens / call_type persistence ───────────────────────────────────────────

    @Test
    void insertPersistsCachedTokensAndCallTypeAlongsideEveryExistingColumn() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        UsageRepository repo = new UsageRepository(jdbc);

        repo.insert("tenant_try_retail", "user@example.com", "chat", "Inventory Health",
                "gpt-4o", 6072, 128, 0.0123, 4500, "PLANNER");

        assertTrue(jdbc.lastUpdateSql.contains("cached_tokens"));
        assertTrue(jdbc.lastUpdateSql.contains("call_type"));
        assertTrue(String.valueOf(jdbc.lastUpdateArgs[0]).startsWith("usg"),
                "first positional arg is the generated id");
        assertArrayEquals(new Object[] {
                "tenant_try_retail", "user@example.com", "chat", "Inventory Health",
                "gpt-4o", 6072, 128, 0.0123, 4500, "PLANNER"
        }, Arrays.copyOfRange(jdbc.lastUpdateArgs, 1, jdbc.lastUpdateArgs.length));
    }

    @Test
    void legacyEightArgInsertOverloadDefaultsToZeroCachedTokensAndNullCallType() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        UsageRepository repo = new UsageRepository(jdbc);

        repo.insert("tenant_try_retail", "user@example.com", "chat", null,
                "gpt-4o-mini", 100, 20, 0.001);

        // Last two positional args are cached_tokens then call_type.
        Object[] args = jdbc.lastUpdateArgs;
        assertEquals(0, args[args.length - 2], "no cachedTokens supplied ⇒ 0, not an inferred value");
        assertNull(args[args.length - 1], "no call type supplied ⇒ SQL NULL, never a guessed classification");
    }

    // ── tenant isolation ─────────────────────────────────────────────────────────────────────────

    @Test
    void summaryByCallTypeIsScopedToTheCallingTenantSchemaOnly() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        UsageRepository repo = new UsageRepository(jdbc);

        repo.summaryByCallType("tenant_maryland_corporations", "2026-09");

        assertTrue(jdbc.lastQuerySql.contains("WHERE tenant_schema = ?"),
                "the per-tenant call-type breakdown must filter by tenant_schema, "
                        + "never returning another tenant's rows");
        assertEquals("tenant_maryland_corporations", jdbc.lastQueryArgs[0],
                "must be parameterized with the CALLING tenant's own schema");
    }

    @Test
    void summaryByCallTypeNeverAggregatesCostFigures() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        UsageRepository repo = new UsageRepository(jdbc);

        repo.summaryByCallType("tenant_maryland_corporations", "2026-09");

        assertFalse(jdbc.lastQuerySql.toLowerCase().contains("cost_usd"),
                "tenant-facing call-type breakdown must stay volume-only, matching the "
                        + "pre-existing summaryByFeature 'no cost' convention");
    }

    @Test
    void platformByCallTypeIsDeliberatelyCrossTenantLikeAllTenantsSummary() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        UsageRepository repo = new UsageRepository(jdbc);

        repo.platformByCallType("2026-09");

        assertFalse(jdbc.lastQuerySql.contains("tenant_schema = ?"),
                "the platform-admin breakdown is intentionally cross-tenant, exactly like "
                        + "allTenantsSummary — tenant scoping for this one is the controller's job");
        assertTrue(jdbc.lastQuerySql.toLowerCase().contains("cost_usd"),
                "platform-admin view includes cost, matching allTenantsSummary's convention");
        assertArrayEquals(new Object[] {"2026-09"}, jdbc.lastQueryArgs);
    }
}
