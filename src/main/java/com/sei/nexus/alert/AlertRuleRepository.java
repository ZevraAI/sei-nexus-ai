package com.sei.nexus.alert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class AlertRuleRepository {

    private final JdbcTemplate jdbc;

    public AlertRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(AlertRule r) {
        jdbc.update("""
                INSERT INTO nexus_alert_rule
                    (rule_key, rule_name, baseline_key, agent_key, kpi_key, metric_name,
                     condition, severity_threshold, channel, slack_webhook, email_to,
                     cooldown_minutes, enabled, created_by, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (rule_key) DO UPDATE SET
                    rule_name          = EXCLUDED.rule_name,
                    baseline_key       = EXCLUDED.baseline_key,
                    agent_key          = EXCLUDED.agent_key,
                    kpi_key            = EXCLUDED.kpi_key,
                    metric_name        = EXCLUDED.metric_name,
                    condition          = EXCLUDED.condition,
                    severity_threshold = EXCLUDED.severity_threshold,
                    channel            = EXCLUDED.channel,
                    slack_webhook      = EXCLUDED.slack_webhook,
                    email_to           = EXCLUDED.email_to,
                    cooldown_minutes   = EXCLUDED.cooldown_minutes,
                    enabled            = EXCLUDED.enabled,
                    updated_at         = NOW()
                """,
                r.ruleKey(), r.ruleName(), r.baselineKey(), r.agentKey(), r.kpiKey(),
                r.metricName(), r.condition(), r.severityThreshold(), r.channel(),
                r.slackWebhook(), r.emailTo(), r.cooldownMinutes(), r.enabled(),
                r.createdBy(),
                Timestamp.from(r.createdAt() != null ? r.createdAt() : Instant.now()),
                Timestamp.from(r.updatedAt() != null ? r.updatedAt() : Instant.now()));
    }

    public void delete(String ruleKey) {
        jdbc.update("DELETE FROM nexus_alert_rule WHERE rule_key = ?", ruleKey);
    }

    public Optional<AlertRule> findByKey(String ruleKey) {
        List<AlertRule> rows = jdbc.query(
                "SELECT * FROM nexus_alert_rule WHERE rule_key = ?", mapper(), ruleKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<AlertRule> findAll() {
        return jdbc.query("SELECT * FROM nexus_alert_rule ORDER BY created_at DESC", mapper());
    }

    public List<AlertRule> findEnabledByBaselineKey(String baselineKey) {
        return jdbc.query(
                "SELECT * FROM nexus_alert_rule WHERE baseline_key = ? AND enabled = TRUE",
                mapper(), baselineKey);
    }

    /** Find the most recent delivery for a rule to enforce cooldown. */
    public Optional<Instant> lastDeliveryTime(String ruleKey) {
        List<Timestamp> rows = jdbc.query(
                "SELECT sent_at FROM nexus_alert_delivery WHERE rule_key = ? ORDER BY sent_at DESC LIMIT 1",
                (rs, i) -> rs.getTimestamp("sent_at"), ruleKey);
        if (rows.isEmpty() || rows.get(0) == null) return Optional.empty();
        return Optional.of(rows.get(0).toInstant());
    }

    private RowMapper<AlertRule> mapper() {
        return (rs, i) -> new AlertRule(
                rs.getString("rule_key"),
                rs.getString("rule_name"),
                rs.getString("baseline_key"),
                rs.getString("agent_key"),
                rs.getString("kpi_key"),
                rs.getString("metric_name"),
                rs.getString("condition"),
                rs.getString("severity_threshold"),
                rs.getString("channel"),
                rs.getString("slack_webhook"),
                rs.getString("email_to"),
                rs.getInt("cooldown_minutes"),
                rs.getBoolean("enabled"),
                rs.getString("created_by"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.now();
    }
}
