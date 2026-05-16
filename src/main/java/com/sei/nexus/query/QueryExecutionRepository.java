package com.sei.nexus.query;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class QueryExecutionRepository {

    private static final String INSERT_EXECUTION = """
            INSERT INTO nexus_query_execution
                (execution_key, run_key, step_no, connection_key, object_keys,
                 classification, route, risk_level, status, estimated_rows, estimated_cost,
                 timeout_seconds, row_limit, original_sql, approved_sql, decision_reason,
                 error_message, result_json, created_at, started_at, completed_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (execution_key) DO NOTHING
            """;

    private static final String UPDATE_STATUS = """
            UPDATE nexus_query_execution
               SET status       = ?,
                   started_at   = COALESCE(?, started_at),
                   completed_at = COALESCE(?, completed_at),
                   error_message = COALESCE(?, error_message)
             WHERE execution_key = ?
            """;

    private static final String UPDATE_RESULT = """
            UPDATE nexus_query_execution
               SET result_json  = ?,
                   status       = ?,
                   completed_at = ?
             WHERE execution_key = ?
            """;

    private static final String FIND_BY_KEY = """
            SELECT execution_key, run_key, step_no, connection_key, object_keys,
                   classification, route, risk_level, status, estimated_rows, estimated_cost,
                   timeout_seconds, row_limit, original_sql, approved_sql, decision_reason,
                   error_message, result_json, created_at, started_at, completed_at
              FROM nexus_query_execution
             WHERE execution_key = ?
            """;

    private static final String FIND_BY_RUN_KEY = """
            SELECT execution_key, run_key, step_no, connection_key, object_keys,
                   classification, route, risk_level, status, estimated_rows, estimated_cost,
                   timeout_seconds, row_limit, original_sql, approved_sql, decision_reason,
                   error_message, result_json, created_at, started_at, completed_at
              FROM nexus_query_execution
             WHERE run_key = ?
             ORDER BY step_no ASC
            """;

    private static final String FIND_QUEUED = """
            SELECT execution_key, run_key, step_no, connection_key, object_keys,
                   classification, route, risk_level, status, estimated_rows, estimated_cost,
                   timeout_seconds, row_limit, original_sql, approved_sql, decision_reason,
                   error_message, result_json, created_at, started_at, completed_at
              FROM nexus_query_execution
             WHERE status = 'QUEUED'
             ORDER BY created_at ASC
             LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public QueryExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(QueryExecution qe) {
        jdbc.update(INSERT_EXECUTION,
                qe.executionKey(), qe.runKey(), qe.stepNo(), qe.connectionKey(), qe.objectKeys(),
                qe.classification(), qe.route(), qe.riskLevel(), qe.status(),
                qe.estimatedRows(), qe.estimatedCost(),
                qe.timeoutSeconds(), qe.rowLimit(),
                qe.originalSql(), qe.approvedSql(), qe.decisionReason(),
                qe.errorMessage(), qe.resultJson(),
                toTimestamp(qe.createdAt()), toTimestamp(qe.startedAt()), toTimestamp(qe.completedAt()));
    }

    public void updateStatus(String executionKey, String status, Instant startedAt,
                              Instant completedAt, String errorMessage) {
        jdbc.update(UPDATE_STATUS,
                status,
                toTimestamp(startedAt),
                toTimestamp(completedAt),
                errorMessage,
                executionKey);
    }

    public void updateResult(String executionKey, String resultJson, String status, Instant completedAt) {
        jdbc.update(UPDATE_RESULT, resultJson, status, toTimestamp(completedAt), executionKey);
    }

    public Optional<QueryExecution> findByKey(String executionKey) {
        List<QueryExecution> rows = jdbc.query(FIND_BY_KEY, rowMapper(), executionKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<QueryExecution> findByRunKey(String runKey) {
        return jdbc.query(FIND_BY_RUN_KEY, rowMapper(), runKey);
    }

    public List<QueryExecution> findQueued(int limit) {
        return jdbc.query(FIND_QUEUED, rowMapper(), limit);
    }

    // ---------------------------------------------------------------------------
    // Row mapper
    // ---------------------------------------------------------------------------

    private RowMapper<QueryExecution> rowMapper() {
        return (rs, rowNum) -> {
            Long estimatedRows = rs.getLong("estimated_rows");
            if (rs.wasNull()) estimatedRows = null;
            Long estimatedCost = rs.getLong("estimated_cost");
            if (rs.wasNull()) estimatedCost = null;
            Integer timeoutSeconds = rs.getInt("timeout_seconds");
            if (rs.wasNull()) timeoutSeconds = null;
            Integer rowLimit = rs.getInt("row_limit");
            if (rs.wasNull()) rowLimit = null;

            return new QueryExecution(
                    rs.getString("execution_key"),
                    rs.getString("run_key"),
                    rs.getInt("step_no"),
                    rs.getString("connection_key"),
                    rs.getString("object_keys"),
                    rs.getString("classification"),
                    rs.getString("route"),
                    rs.getString("risk_level"),
                    rs.getString("status"),
                    estimatedRows,
                    estimatedCost,
                    timeoutSeconds,
                    rowLimit,
                    rs.getString("original_sql"),
                    rs.getString("approved_sql"),
                    rs.getString("decision_reason"),
                    rs.getString("error_message"),
                    rs.getString("result_json"),
                    toInstant(rs, "created_at"),
                    toInstant(rs, "started_at"),
                    toInstant(rs, "completed_at"));
        };
    }

    private Instant toInstant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }
}
