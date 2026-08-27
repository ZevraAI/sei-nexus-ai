package com.sei.nexus.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DIAGNOSTIC (opt-in, real Postgres): proves the Global Pack Foundation's connection-
 * scoping invariant — "at most one ACTIVE pack per connection" — is actually enforced
 * by the database, not just assumed. A unit test on the SQL/DDL text cannot verify a
 * real partial unique index's behavior at runtime.
 *
 * <p>Runs entirely inside a dedicated, self-created scratch schema
 * ({@code zevra_pack_scratch_test}) holding a private copy of just the
 * {@code nexus_tenant_pack} shape needed to exercise the new
 * {@code connection_key} column and its partial unique index — it never reads or
 * writes any tenant schema or any real {@code nexus_tenant_pack} row. The schema is
 * dropped in {@code @AfterEach} even on failure.
 *
 * <p>Guarded by {@code -Dnexus.pack.probe=true}; skipped otherwise (no DB access
 * required for a normal {@code mvn test} run) — mirrors
 * {@code SemanticRepositoryUpsertPreservationProbe}'s convention exactly, including
 * pinning the schema via the JDBC {@code currentSchema} connection property (a
 * session-level {@code SET search_path} does not persist across
 * {@code DriverManagerDataSource}'s per-call connections — verified the hard way while
 * writing that probe).
 */
class TenantPackConnectionScopingProbe {

    private static final String SCHEMA = "zevra_pack_scratch_test";

    private JdbcTemplate jdbc;
    private IndustryPackRepository repository;

    private static Map<String, String> env() throws Exception {
        Map<String, String> e = new java.util.HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(".env.local"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                int i = line.indexOf('=');
                e.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        }
        return e;
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.pack.probe"), "diagnostic disabled");
        Map<String, String> env = env();

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("jdbc:postgresql://([^:/]+):(\\d+)/([^?]+)")
                .matcher(env.get("NEXUS_DB_URL"));
        assumeTrue(m.find());
        String url = "jdbc:postgresql://" + m.group(1) + ":" + m.group(2) + "/" + m.group(3)
                + "?sslmode=require&currentSchema=" + SCHEMA;

        DriverManagerDataSource ds = new DriverManagerDataSource(url,
                env.get("NEXUS_DB_USERNAME"), env.get("NEXUS_DB_PASSWORD"));
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        // Minimal shape: exactly the V041 evolution of nexus_tenant_pack — a throwaway
        // scratch table, not the real one, dropped at the end of every test.
        jdbc.execute("""
                CREATE TABLE %s.nexus_tenant_pack (
                    id              BIGSERIAL    PRIMARY KEY,
                    pack_key        VARCHAR(255) NOT NULL,
                    connection_key  VARCHAR(120),
                    pack_version    VARCHAR(20)  NOT NULL DEFAULT '1.0.0',
                    display_name    VARCHAR(255),
                    status          VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE'
                                    CHECK (status IN ('ACTIVE','DISABLED')),
                    mapping_json    JSONB,
                    coverage_score  FLOAT,
                    applied_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    applied_by      VARCHAR(255),
                    UNIQUE (pack_key)
                )
                """.formatted(SCHEMA));
        jdbc.execute("""
                CREATE UNIQUE INDEX uq_tenant_pack_active_connection
                    ON %s.nexus_tenant_pack (connection_key)
                    WHERE status = 'ACTIVE' AND connection_key IS NOT NULL
                """.formatted(SCHEMA));

        repository = new IndustryPackRepository(jdbc, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private TenantPack pack(String packKey, String connectionKey, String status) {
        return new TenantPack(packKey, connectionKey, "1.0.0", "Test Pack " + packKey,
                status, Map.of(), 1.0, null, "test@example.com");
    }

    /** TEST — different packs, same connection: the second ACTIVE assignment must be rejected. */
    @Test
    void secondActivePackOnTheSameConnectionIsRejected() {
        repository.saveTenantPack(pack("retail-v1", "conn-1", "ACTIVE"));

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveTenantPack(pack("logistics-v1", "conn-1", "ACTIVE")),
                "conn-1 -> retail-v1 ACTIVE must not coexist with conn-1 -> logistics-v1 ACTIVE — "
                        + "a connection may have at most one active Industry Pack");
    }

    /** TEST — reassignment: disabling the old pack must free the connection for a new one. */
    @Test
    void connectionCanBeReassignedAfterTheOldPackIsDisabled() {
        repository.saveTenantPack(pack("retail-v1", "conn-1", "ACTIVE"));
        jdbc.update("UPDATE nexus_tenant_pack SET status = 'DISABLED' WHERE pack_key = ?", "retail-v1");

        assertDoesNotThrow(() -> repository.saveTenantPack(pack("logistics-v1", "conn-1", "ACTIVE")),
                "once the old pack is DISABLED, a new pack must be assignable to the same connection");

        Long activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nexus_tenant_pack WHERE connection_key = 'conn-1' AND status = 'ACTIVE'",
                Long.class);
        assertEquals(1L, activeCount, "exactly one active assignment must remain for conn-1");
    }

    /** TEST — one tenant, many connections, many different packs: all must coexist. */
    @Test
    void oneTenantCanHaveManyDifferentActivePacksOnDifferentConnections() {
        repository.saveTenantPack(pack("retail-v1",    "conn-1", "ACTIVE"));
        repository.saveTenantPack(pack("logistics-v1", "conn-2", "ACTIVE"));
        repository.saveTenantPack(pack("finance-v1",   "conn-3", "ACTIVE"));

        Long activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nexus_tenant_pack WHERE status = 'ACTIVE'", Long.class);
        assertEquals(3L, activeCount, "three distinct connections, three distinct active packs — all must coexist");
    }

    /** Sanity check: legacy rows with no connection_key (pre-existing behavior) are unaffected. */
    @Test
    void legacyRowsWithNoConnectionKeyDoNotConflictWithEachOther() {
        // Mirrors real, live-observed data: tenant_maryland_corporations has two
        // historical DISABLED nexus_tenant_pack rows, neither with a connection_key.
        assertDoesNotThrow(() -> {
            repository.saveTenantPack(pack("healthcare-v1", null, "DISABLED"));
            repository.saveTenantPack(pack("logistics-v1",  null, "DISABLED"));
        }, "legacy/unscoped rows (connection_key = NULL) must not conflict with each other, "
                + "matching real historical data observed in tenant_maryland_corporations");
    }

    /**
     * Connection-Scoped Industry Pack Assignment: proves the new connection-scoped write path
     * never touches, backfills, or is blocked by a pre-existing legacy connection_key = NULL
     * row — the task explicitly forbids guessing which connection a legacy row belongs to.
     */
    @Test
    void applyingAPackToARealConnectionDoesNotTouchAPreExistingLegacyNullConnectionRow() {
        repository.saveTenantPack(pack("healthcare-v1", null, "DISABLED"));

        assertDoesNotThrow(() -> repository.saveTenantPack(pack("retail-v1", "conn-1", "ACTIVE")),
                "a real connection-scoped assignment must not be blocked by an unrelated legacy NULL row");

        Map<String, Object> legacyRow = jdbc.queryForMap(
                "SELECT connection_key, status FROM nexus_tenant_pack WHERE pack_key = 'healthcare-v1'");
        assertNull(legacyRow.get("connection_key"), "legacy row's connection_key must remain NULL — never backfilled");
        assertEquals("DISABLED", legacyRow.get("status"), "legacy row's status must be untouched");

        Long totalRows = jdbc.queryForObject("SELECT COUNT(*) FROM nexus_tenant_pack", Long.class);
        assertEquals(2L, totalRows, "the new connection-scoped row must be an independent insert, "
                + "not a mutation of the legacy row");
    }
}
