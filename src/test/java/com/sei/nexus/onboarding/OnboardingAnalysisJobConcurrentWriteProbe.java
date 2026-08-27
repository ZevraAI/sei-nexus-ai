package com.sei.nexus.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DIAGNOSTIC (opt-in, real Postgres): proves {@link OnboardingAnalysisJobRepository
 * #updateTableResult} has no lost-update race when multiple tables of the same job
 * complete concurrently and write into the shared {@code results_json} column at
 * once — a hand-rolled fake / in-memory test (see {@code OnboardingServiceAnalysisJobTest})
 * cannot prove this; it needs Postgres's actual row-level locking under the
 * {@code jsonb_set(...) WHERE id = ?} statement.
 *
 * <p>Runs entirely inside a dedicated, self-created scratch schema
 * ({@code zevra_onbjob_scratch_test}) holding a private copy of just the
 * {@code nexus_onboarding_analysis_job} shape — never touches any tenant schema
 * or any real job row. Dropped in {@code @AfterEach} even on failure.
 *
 * <p>Guarded by {@code -Dnexus.onboarding.probe=true}; skipped otherwise (no DB
 * access required for a normal {@code mvn test} run).
 */
class OnboardingAnalysisJobConcurrentWriteProbe {

    private static final String SCHEMA = "zevra_onbjob_scratch_test";

    private JdbcTemplate jdbc;
    private JdbcTemplate ddlJdbc;
    private OnboardingAnalysisJobRepository repository;

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
        assumeTrue(Boolean.getBoolean("nexus.onboarding.probe"), "diagnostic disabled");
        Map<String, String> env = env();

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("jdbc:postgresql://([^:/]+):(\\d+)/([^?]+)")
                .matcher(env.get("NEXUS_DB_URL"));
        assumeTrue(m.find());
        // DriverManagerDataSource opens a fresh connection per call (no pooling) — a
        // one-off `SET search_path` from setUp() would not reliably survive to later
        // calls. `currentSchema` as a JDBC URL param is instead applied by the pgjdbc
        // driver on every new connection, deterministically (see ChatGroundingReproProbe
        // for the same pattern against a real tenant schema).
        String url = "jdbc:postgresql://" + m.group(1) + ":" + m.group(2) + "/" + m.group(3)
                + "?sslmode=require&currentSchema=" + SCHEMA;

        DriverManagerDataSource ds = new DriverManagerDataSource(url,
                env.get("NEXUS_DB_USERNAME"), env.get("NEXUS_DB_PASSWORD"));
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);

        // Schema/table creation must run with the default search_path (the target
        // schema doesn't exist yet) — use a separate, unscoped connection for DDL only.
        DriverManagerDataSource ddlDs = new DriverManagerDataSource(
                "jdbc:postgresql://" + m.group(1) + ":" + m.group(2) + "/" + m.group(3) + "?sslmode=require",
                env.get("NEXUS_DB_USERNAME"), env.get("NEXUS_DB_PASSWORD"));
        ddlDs.setDriverClassName("org.postgresql.Driver");
        JdbcTemplate ddlJdbc = new JdbcTemplate(ddlDs);

        ddlJdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        ddlJdbc.execute("CREATE SCHEMA " + SCHEMA);
        ddlJdbc.execute("""
                CREATE TABLE %s.nexus_onboarding_analysis_job (
                    id              VARCHAR(120) PRIMARY KEY,
                    tenant_schema   TEXT NOT NULL,
                    connection_key  TEXT NOT NULL,
                    schema_name     TEXT NOT NULL,
                    domain_key      TEXT NOT NULL,
                    table_names     TEXT[] NOT NULL,
                    status          TEXT NOT NULL DEFAULT 'RUNNING',
                    results_json    JSONB NOT NULL DEFAULT '{}',
                    tables_done     INT NOT NULL DEFAULT 0,
                    tables_total    INT NOT NULL,
                    request_hash    TEXT NOT NULL,
                    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    completed_at    TIMESTAMPTZ
                )
                """.formatted(SCHEMA));

        this.ddlJdbc = ddlJdbc;
        repository = new OnboardingAnalysisJobRepository(jdbc, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (ddlJdbc != null) {
            ddlJdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void concurrentTableResultWritesLoseNoUpdates() throws Exception {
        int tableCount = 20;
        List<String> tableNames = java.util.stream.IntStream.range(0, tableCount)
                .mapToObj(i -> "table_" + i).collect(java.util.stream.Collectors.toList());

        repository.insertJob(new OnboardingAnalysisJob(
                "onbjob-probe-1", "tenant_probe", "conn-1", "public", "PLATFORM",
                tableNames, "RUNNING", "{}", 0, tableCount, "hash-1",
                null, null, null));

        ExecutorService pool = Executors.newFixedThreadPool(tableCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(tableCount);

        for (String tableName : tableNames) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    repository.updateTableResult("onbjob-probe-1", tableName,
                            Map.of("table_name", tableName, "entityName", "Thing"));
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(doneLatch.await(20, TimeUnit.SECONDS), "all concurrent writes should finish");
        pool.shutdown();

        OnboardingAnalysisJob result = repository.findById("onbjob-probe-1").orElseThrow();
        assertEquals(tableCount, result.tablesDone(),
                "tables_done must reflect every concurrent write — a lost update would undercount this");

        @SuppressWarnings("unchecked")
        Map<String, Object> results = new ObjectMapper().readValue(result.resultsJson(), Map.class);
        for (String tableName : tableNames) {
            assertTrue(results.containsKey(tableName),
                    "table '" + tableName + "' must be present in results_json — a lost update would drop it");
        }
        assertEquals(tableCount, results.size(), "no extra or missing keys");
    }
}
