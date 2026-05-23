package com.sei.nexus.governance;

import com.sei.nexus.common.Keys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class AuditEventRepository {

    private final JdbcTemplate jdbc;

    public AuditEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(AuditEvent e) {
        String key = e.eventKey() != null ? e.eventKey() : Keys.uniqueKey("audit");
        // All TEXT[] columns use the ?::text[] cast with a PostgreSQL array literal string.
        // This avoids calling DataSource.getConnection() directly, which leaks connections
        // because the raw Connection is never returned to the HikariCP pool.
        jdbc.update("""
                INSERT INTO nexus_audit_event (
                    event_key, event_type, user_email, user_role, run_key,
                    connection_key, object_keys, columns_accessed, columns_masked,
                    rls_policies_applied, contracts_checked, contracts_violated,
                    original_sql, executed_sql, row_count_returned,
                    rows_filtered_by_rls, execution_ms, ip_address, created_at
                ) VALUES (?,?,?,?,?,?,?::text[],?::text[],?::text[],?::text[],?::text[],?::text[],?,?,?,?,?,?,NOW())
                """,
                key, e.eventType(), e.userEmail(), e.userRole(), e.runKey(),
                e.connectionKey(),
                toArrayLiteral(e.objectKeys()),
                toArrayLiteral(e.columnsAccessed()),
                toArrayLiteral(e.columnsMasked()),
                toArrayLiteral(e.rlsPoliciesApplied()),
                toArrayLiteral(e.contractsChecked()),
                toArrayLiteral(e.contractsViolated()),
                e.originalSql(), e.executedSql(),
                e.rowCountReturned(), e.rowsFilteredByRls(),
                e.executionMs(), e.ipAddress());
    }

    public List<AuditEvent> findRecent(int page, int size) {
        int offset = page * size;
        return jdbc.query(
                "SELECT * FROM nexus_audit_event ORDER BY created_at DESC LIMIT ? OFFSET ?",
                mapper(), size, offset);
    }

    public List<AuditEvent> findFiltered(String userEmail, String eventType,
                                         String connectionKey, Instant from, Instant to,
                                         int page, int size) {
        List<Object> params = new java.util.ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM nexus_audit_event WHERE 1=1");
        if (userEmail != null && !userEmail.isBlank()) {
            sql.append(" AND user_email = ?");    params.add(userEmail);
        }
        if (eventType != null && !eventType.isBlank()) {
            sql.append(" AND event_type = ?");    params.add(eventType);
        }
        if (connectionKey != null && !connectionKey.isBlank()) {
            sql.append(" AND connection_key = ?"); params.add(connectionKey);
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");   params.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");   params.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);
        return jdbc.query(sql.toString(), mapper(), params.toArray());
    }

    public long countFiltered(String userEmail, String eventType,
                               String connectionKey, Instant from, Instant to) {
        List<Object> params = new java.util.ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM nexus_audit_event WHERE 1=1");
        if (userEmail != null && !userEmail.isBlank()) {
            sql.append(" AND user_email = ?");    params.add(userEmail);
        }
        if (eventType != null && !eventType.isBlank()) {
            sql.append(" AND event_type = ?");    params.add(eventType);
        }
        if (connectionKey != null && !connectionKey.isBlank()) {
            sql.append(" AND connection_key = ?"); params.add(connectionKey);
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");   params.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");   params.add(Timestamp.from(to));
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return count != null ? count : 0L;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RowMapper<AuditEvent> mapper() {
        return (rs, i) -> new AuditEvent(
                rs.getString("event_key"),
                rs.getString("event_type"),
                rs.getString("user_email"),
                rs.getString("user_role"),
                rs.getString("run_key"),
                rs.getString("connection_key"),
                toStringArray(rs.getArray("object_keys")),
                toStringArray(rs.getArray("columns_accessed")),
                toStringArray(rs.getArray("columns_masked")),
                toStringArray(rs.getArray("rls_policies_applied")),
                toStringArray(rs.getArray("contracts_checked")),
                toStringArray(rs.getArray("contracts_violated")),
                rs.getString("original_sql"),
                rs.getString("executed_sql"),
                rs.getObject("row_count_returned")  != null ? rs.getInt("row_count_returned")  : null,
                rs.getObject("rows_filtered_by_rls") != null ? rs.getInt("rows_filtered_by_rls") : null,
                rs.getObject("execution_ms")         != null ? rs.getInt("execution_ms")         : null,
                rs.getString("ip_address"),
                toInstant(rs.getTimestamp("created_at")));
    }

    /**
     * Converts a Java String array to a PostgreSQL text[] literal, e.g. {"a","b"}.
     * Used with ?::text[] cast in SQL so no raw Connection is ever borrowed from
     * the pool — eliminates the connection leak that caused pool exhaustion.
     */
    private String toArrayLiteral(String[] values) {
        if (values == null || values.length == 0) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values[i].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    private String[] toStringArray(Array arr) {
        try {
            return arr != null ? (String[]) arr.getArray() : new String[0];
        } catch (Exception e) {
            return new String[0];
        }
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.now();
    }
}
