package com.sei.nexus.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for {@link OnboardingAnalysisJob} (V040). Mirrors
 * {@code MorningBriefRepository}'s style: stub-insert, incremental update,
 * terminal mark-complete/mark-failed, tenant-scoped reads.
 *
 * <p>Table is shared/public-schema-resident (like {@code nexus_morning_brief}),
 * scoped by the {@code tenant_schema} column rather than physical schema
 * isolation — every query below filters on it explicitly.
 */
@Repository
public class OnboardingAnalysisJobRepository {

    private final JdbcTemplate  jdbc;
    private final ObjectMapper  objectMapper;

    public OnboardingAnalysisJobRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc         = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insertJob(OnboardingAnalysisJob job) {
        jdbc.update("""
                INSERT INTO nexus_onboarding_analysis_job
                    (id, tenant_schema, connection_key, schema_name, domain_key, table_names,
                     status, results_json, tables_done, tables_total, request_hash,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?::jsonb, ?, ?, ?, NOW(), NOW())
                """,
                job.id(), job.tenantSchema(), job.connectionKey(), job.schemaName(), job.domainKey(),
                toArrayLiteral(job.tableNames()), job.status(), job.resultsJson(),
                job.tablesDone(), job.tablesTotal(), job.requestHash());
    }

    /**
     * Writes one table's result into the job's accumulating {@code results_json}
     * and bumps the {@code tables_done} counter — safe to call concurrently from
     * multiple tables of the same job, since each table writes a distinct JSON key
     * and Postgres serializes the row-level {@code UPDATE}.
     */
    public void updateTableResult(String jobId, String tableName, Map<String, Object> tableResult) {
        try {
            String resultJson = objectMapper.writeValueAsString(tableResult);
            jdbc.update("""
                    UPDATE nexus_onboarding_analysis_job
                       SET results_json = jsonb_set(results_json, ARRAY[?], ?::jsonb, true),
                           tables_done  = tables_done + 1,
                           updated_at   = NOW()
                     WHERE id = ?
                    """, tableName, resultJson, jobId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize table result for " + tableName, e);
        }
    }

    public void markComplete(String jobId) {
        jdbc.update("""
                UPDATE nexus_onboarding_analysis_job
                   SET status = 'COMPLETE', completed_at = NOW(), updated_at = NOW()
                 WHERE id = ?
                """, jobId);
    }

    public void markFailed(String jobId) {
        jdbc.update("""
                UPDATE nexus_onboarding_analysis_job
                   SET status = 'FAILED', completed_at = NOW(), updated_at = NOW()
                 WHERE id = ?
                """, jobId);
    }

    public Optional<OnboardingAnalysisJob> findById(String jobId) {
        List<OnboardingAnalysisJob> rows = jdbc.query(
                "SELECT * FROM nexus_onboarding_analysis_job WHERE id = ?", jobMapper(), jobId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * A non-FAILED job for this exact request, created within {@code window} —
     * the double-submit guard. A FAILED job is deliberately excluded so a retry
     * always gets a fresh job rather than reattaching to a dead one.
     */
    public Optional<OnboardingAnalysisJob> findRecentByHash(String tenantSchema, String requestHash,
                                                              Duration window) {
        List<OnboardingAnalysisJob> rows = jdbc.query("""
                SELECT * FROM nexus_onboarding_analysis_job
                 WHERE tenant_schema = ? AND request_hash = ? AND status != 'FAILED'
                   AND created_at >= ?
                 ORDER BY created_at DESC LIMIT 1
                """, jobMapper(), tenantSchema, requestHash,
                Timestamp.from(Instant.now().minus(window)));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<OnboardingAnalysisJob> findLatest(String tenantSchema) {
        List<OnboardingAnalysisJob> rows = jdbc.query("""
                SELECT * FROM nexus_onboarding_analysis_job
                 WHERE tenant_schema = ? ORDER BY created_at DESC LIMIT 1
                """, jobMapper(), tenantSchema);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ── Mapper ────────────────────────────────────────────────────────────────

    private RowMapper<OnboardingAnalysisJob> jobMapper() {
        return (rs, i) -> new OnboardingAnalysisJob(
                rs.getString("id"),
                rs.getString("tenant_schema"),
                rs.getString("connection_key"),
                rs.getString("schema_name"),
                rs.getString("domain_key"),
                toStringList(rs.getArray("table_names")),
                rs.getString("status"),
                rs.getString("results_json"),
                rs.getInt("tables_done"),
                rs.getInt("tables_total"),
                rs.getString("request_hash"),
                rs.getTimestamp("created_at")   != null ? rs.getTimestamp("created_at").toInstant()   : null,
                rs.getTimestamp("updated_at")   != null ? rs.getTimestamp("updated_at").toInstant()   : null,
                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null);
    }

    private List<String> toStringList(Array arr) {
        try {
            if (arr == null) return Collections.emptyList();
            return Arrays.asList((String[]) arr.getArray());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String toArrayLiteral(List<String> values) {
        if (values == null || values.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
