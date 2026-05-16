package com.sei.nexus.temporal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class TemporalRepository {

    // ── Baseline SQL ─────────────────────────────────────────────────────────
    private static final String UPSERT_BASELINE =
            "INSERT INTO nexus_operational_baseline " +
            "(baseline_key, domain_key, agent_key, kpi_key, metric_name, measurement_sql, " +
            " connection_key, current_value, baseline_avg, baseline_stddev, measurement_window, " +
            " trend_data, last_computed_at, next_due_at, status, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (baseline_key) DO UPDATE SET " +
            " domain_key = EXCLUDED.domain_key, agent_key = EXCLUDED.agent_key, " +
            " kpi_key = EXCLUDED.kpi_key, metric_name = EXCLUDED.metric_name, " +
            " measurement_sql = EXCLUDED.measurement_sql, connection_key = EXCLUDED.connection_key, " +
            " current_value = EXCLUDED.current_value, baseline_avg = EXCLUDED.baseline_avg, " +
            " baseline_stddev = EXCLUDED.baseline_stddev, measurement_window = EXCLUDED.measurement_window, " +
            " trend_data = EXCLUDED.trend_data, last_computed_at = EXCLUDED.last_computed_at, " +
            " next_due_at = EXCLUDED.next_due_at, status = EXCLUDED.status";

    private static final String UPDATE_BASELINE_VALUES =
            "UPDATE nexus_operational_baseline SET current_value=?, baseline_avg=?, baseline_stddev=?, " +
            "trend_data=?, last_computed_at=?, next_due_at=? WHERE baseline_key=?";

    private static final String FIND_BASELINES_BY_DOMAIN =
            "SELECT baseline_key, domain_key, agent_key, kpi_key, metric_name, measurement_sql, " +
            "connection_key, current_value, baseline_avg, baseline_stddev, measurement_window, " +
            "trend_data, last_computed_at, next_due_at, status, created_at " +
            "FROM nexus_operational_baseline WHERE domain_key = ? ORDER BY metric_name ASC";

    private static final String FIND_DUE_BASELINES =
            "SELECT baseline_key, domain_key, agent_key, kpi_key, metric_name, measurement_sql, " +
            "connection_key, current_value, baseline_avg, baseline_stddev, measurement_window, " +
            "trend_data, last_computed_at, next_due_at, status, created_at " +
            "FROM nexus_operational_baseline WHERE next_due_at <= ? AND status = 'ACTIVE'";

    private static final String FIND_BASELINE_BY_KEY =
            "SELECT baseline_key, domain_key, agent_key, kpi_key, metric_name, measurement_sql, " +
            "connection_key, current_value, baseline_avg, baseline_stddev, measurement_window, " +
            "trend_data, last_computed_at, next_due_at, status, created_at " +
            "FROM nexus_operational_baseline WHERE baseline_key = ?";

    // ── Anomaly SQL ───────────────────────────────────────────────────────────
    private static final String INSERT_ANOMALY =
            "INSERT INTO nexus_anomaly_event " +
            "(anomaly_key, baseline_key, domain_key, entity_key, detected_at, metric_name, " +
            " baseline_value, observed_value, deviation_pct, deviation_stddev, " +
            " severity, status, finding_key) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_ANOMALY_STATUS =
            "UPDATE nexus_anomaly_event SET status=?, finding_key=? WHERE anomaly_key=?";

    private static final String FIND_ANOMALIES_BY_DOMAIN =
            "SELECT anomaly_key, baseline_key, domain_key, entity_key, detected_at, metric_name, " +
            "baseline_value, observed_value, deviation_pct, deviation_stddev, " +
            "severity, status, finding_key " +
            "FROM nexus_anomaly_event WHERE domain_key = ? AND status = ? " +
            "ORDER BY detected_at DESC";

    private static final String FIND_ANOMALIES_BY_DOMAIN_ALL =
            "SELECT anomaly_key, baseline_key, domain_key, entity_key, detected_at, metric_name, " +
            "baseline_value, observed_value, deviation_pct, deviation_stddev, " +
            "severity, status, finding_key " +
            "FROM nexus_anomaly_event WHERE domain_key = ? ORDER BY detected_at DESC";

    private static final String FIND_RECENT_ANOMALIES =
            "SELECT anomaly_key, baseline_key, domain_key, entity_key, detected_at, metric_name, " +
            "baseline_value, observed_value, deviation_pct, deviation_stddev, " +
            "severity, status, finding_key " +
            "FROM nexus_anomaly_event WHERE domain_key = ANY(?::text[]) " +
            "ORDER BY detected_at DESC LIMIT ?";

    private final JdbcTemplate jdbc;

    public TemporalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Baseline ──────────────────────────────────────────────────────────────

    public void saveBaseline(OperationalBaseline b) {
        jdbc.update(UPSERT_BASELINE,
                b.baselineKey(), b.domainKey(), b.agentKey(), b.kpiKey(), b.metricName(),
                b.measurementSql(), b.connectionKey(), b.currentValue(), b.baselineAvg(),
                b.baselineStddev(), b.measurementWindow(), b.trendData(),
                toTimestamp(b.lastComputedAt()), toTimestamp(b.nextDueAt()),
                b.status(), toTimestamp(b.createdAt()));
    }

    public void updateBaselineValues(String baselineKey, Double currentValue, Double avg,
                                     Double stddev, String trendData,
                                     Instant lastComputedAt, Instant nextDueAt) {
        jdbc.update(UPDATE_BASELINE_VALUES,
                currentValue, avg, stddev, trendData,
                toTimestamp(lastComputedAt), toTimestamp(nextDueAt), baselineKey);
    }

    public List<OperationalBaseline> findBaselinesByDomain(String domainKey) {
        return jdbc.query(FIND_BASELINES_BY_DOMAIN, baselineMapper(), domainKey);
    }

    public List<OperationalBaseline> findDueBaselines(Instant now) {
        return jdbc.query(FIND_DUE_BASELINES, baselineMapper(), toTimestamp(now));
    }

    public java.util.Optional<OperationalBaseline> findBaselineByKey(String baselineKey) {
        List<OperationalBaseline> rows = jdbc.query(FIND_BASELINE_BY_KEY, baselineMapper(), baselineKey);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    // ── Anomaly ───────────────────────────────────────────────────────────────

    public void saveAnomaly(AnomalyEvent a) {
        jdbc.update(INSERT_ANOMALY,
                a.anomalyKey(), a.baselineKey(), a.domainKey(), a.entityKey(),
                toTimestamp(a.detectedAt()), a.metricName(),
                a.baselineValue(), a.observedValue(), a.deviationPct(), a.deviationStddev(),
                a.severity(), a.status(), a.findingKey());
    }

    public void updateAnomalyStatus(String anomalyKey, String status, String findingKey) {
        jdbc.update(UPDATE_ANOMALY_STATUS, status, findingKey, anomalyKey);
    }

    public List<AnomalyEvent> findAnomaliesByDomain(String domainKey, String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return jdbc.query(FIND_ANOMALIES_BY_DOMAIN_ALL, anomalyMapper(), domainKey);
        }
        return jdbc.query(FIND_ANOMALIES_BY_DOMAIN, anomalyMapper(), domainKey, status);
    }

    public List<AnomalyEvent> findRecentAnomalies(List<String> domainKeys, int limit) {
        if (domainKeys == null || domainKeys.isEmpty()) return List.of();
        return jdbc.query(FIND_RECENT_ANOMALIES, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("text", domainKeys.toArray()));
            ps.setInt(2, limit);
        }, anomalyMapper());
    }

    // ── Row Mappers ───────────────────────────────────────────────────────────

    private RowMapper<OperationalBaseline> baselineMapper() {
        return (rs, rowNum) -> {
            Double cv = getNullableDouble(rs, "current_value");
            Double avg = getNullableDouble(rs, "baseline_avg");
            Double std = getNullableDouble(rs, "baseline_stddev");
            return new OperationalBaseline(
                    rs.getString("baseline_key"),
                    rs.getString("domain_key"),
                    rs.getString("agent_key"),
                    rs.getString("kpi_key"),
                    rs.getString("metric_name"),
                    rs.getString("measurement_sql"),
                    rs.getString("connection_key"),
                    cv, avg, std,
                    rs.getString("measurement_window"),
                    rs.getString("trend_data"),
                    toInstant(rs, "last_computed_at"),
                    toInstant(rs, "next_due_at"),
                    rs.getString("status"),
                    toInstant(rs, "created_at"));
        };
    }

    private RowMapper<AnomalyEvent> anomalyMapper() {
        return (rs, rowNum) -> new AnomalyEvent(
                rs.getString("anomaly_key"),
                rs.getString("baseline_key"),
                rs.getString("domain_key"),
                rs.getString("entity_key"),
                toInstant(rs, "detected_at"),
                rs.getString("metric_name"),
                getNullableDouble(rs, "baseline_value"),
                getNullableDouble(rs, "observed_value"),
                getNullableDouble(rs, "deviation_pct"),
                getNullableDouble(rs, "deviation_stddev"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getString("finding_key"));
    }

    private Double getNullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private Instant toInstant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }
}
