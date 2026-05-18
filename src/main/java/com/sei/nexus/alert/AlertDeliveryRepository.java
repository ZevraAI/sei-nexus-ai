package com.sei.nexus.alert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class AlertDeliveryRepository {

    private final JdbcTemplate jdbc;

    public AlertDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(AlertDelivery d) {
        jdbc.update("""
                INSERT INTO nexus_alert_delivery
                    (delivery_key, rule_key, rule_name, anomaly_key, channel,
                     metric_name, current_value, baseline_value, deviation_pct,
                     severity, message_text, status, sent_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                d.deliveryKey(), d.ruleKey(), d.ruleName(), d.anomalyKey(),
                d.channel(), d.metricName(),
                d.currentValue(), d.baselineValue(), d.deviationPct(),
                d.severity(), d.messageText(), d.status(),
                Timestamp.from(d.sentAt() != null ? d.sentAt() : Instant.now()));
    }

    public void markRead(String deliveryKey) {
        jdbc.update("""
                UPDATE nexus_alert_delivery
                   SET status = 'READ', read_at = NOW()
                 WHERE delivery_key = ?
                """, deliveryKey);
    }

    public void markAllRead() {
        jdbc.update("""
                UPDATE nexus_alert_delivery
                   SET status = 'READ', read_at = NOW()
                 WHERE status = 'UNREAD'
                """);
    }

    public int countUnread() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nexus_alert_delivery WHERE status = 'UNREAD'",
                Integer.class);
        return count != null ? count : 0;
    }

    public List<AlertDelivery> findRecent(int limit) {
        return jdbc.query(
                "SELECT * FROM nexus_alert_delivery ORDER BY sent_at DESC LIMIT ?",
                mapper(), limit);
    }

    private RowMapper<AlertDelivery> mapper() {
        return (rs, i) -> new AlertDelivery(
                rs.getString("delivery_key"),
                rs.getString("rule_key"),
                rs.getString("rule_name"),
                rs.getString("anomaly_key"),
                rs.getString("channel"),
                rs.getString("metric_name"),
                toBigDecimal(rs, "current_value"),
                toBigDecimal(rs, "baseline_value"),
                toBigDecimal(rs, "deviation_pct"),
                rs.getString("severity"),
                rs.getString("message_text"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("sent_at")),
                toInstant(rs.getTimestamp("read_at")));
    }

    private BigDecimal toBigDecimal(java.sql.ResultSet rs, String col) {
        try {
            java.math.BigDecimal v = rs.getBigDecimal(col);
            return v;
        } catch (Exception e) { return null; }
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
