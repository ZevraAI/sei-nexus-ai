package com.sei.nexus.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ScheduledReportRepository {

    private final JdbcTemplate jdbc;

    public ScheduledReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(ScheduledReport r) {
        jdbc.update("""
                INSERT INTO nexus_scheduled_report
                    (report_key, name, description, questions_json, agent_key,
                     schedule_type, schedule_time, schedule_day_of_week, schedule_day_of_month,
                     timezone, channel, slack_webhook, email_to, status,
                     last_run_at, next_run_at, last_run_status, last_run_message,
                     created_by, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (report_key) DO UPDATE SET
                    name                  = EXCLUDED.name,
                    description           = EXCLUDED.description,
                    questions_json        = EXCLUDED.questions_json,
                    agent_key             = EXCLUDED.agent_key,
                    schedule_type         = EXCLUDED.schedule_type,
                    schedule_time         = EXCLUDED.schedule_time,
                    schedule_day_of_week  = EXCLUDED.schedule_day_of_week,
                    schedule_day_of_month = EXCLUDED.schedule_day_of_month,
                    timezone              = EXCLUDED.timezone,
                    channel               = EXCLUDED.channel,
                    slack_webhook         = EXCLUDED.slack_webhook,
                    email_to              = EXCLUDED.email_to,
                    status                = EXCLUDED.status,
                    next_run_at           = EXCLUDED.next_run_at,
                    updated_at            = NOW()
                """,
                r.reportKey(), r.name(), r.description(), r.questionsJson(), r.agentKey(),
                r.scheduleType(), r.scheduleTime(), r.scheduleDayOfWeek(), r.scheduleDayOfMonth(),
                r.timezone(), r.channel(), r.slackWebhook(), r.emailTo(), r.status(),
                ts(r.lastRunAt()), ts(r.nextRunAt()), r.lastRunStatus(), r.lastRunMessage(),
                r.createdBy(), ts(r.createdAt()), ts(r.updatedAt()));
    }

    public void updateRunResult(String reportKey, String status, String message,
                                 Instant lastRunAt, Instant nextRunAt) {
        jdbc.update("""
                UPDATE nexus_scheduled_report
                   SET last_run_at      = ?,
                       last_run_status  = ?,
                       last_run_message = ?,
                       next_run_at      = ?,
                       updated_at       = NOW()
                 WHERE report_key = ?
                """, ts(lastRunAt), status, message, ts(nextRunAt), reportKey);
    }

    public void delete(String reportKey) {
        jdbc.update("UPDATE nexus_scheduled_report SET status='ARCHIVED', updated_at=NOW() WHERE report_key=?",
                reportKey);
    }

    public Optional<ScheduledReport> findByKey(String reportKey) {
        List<ScheduledReport> rows = jdbc.query(
                "SELECT * FROM nexus_scheduled_report WHERE report_key = ?", mapper(), reportKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ScheduledReport> findAll() {
        return jdbc.query(
                "SELECT * FROM nexus_scheduled_report WHERE status != 'ARCHIVED' ORDER BY created_at DESC",
                mapper());
    }

    /** Returns reports that are due to run — status ACTIVE and next_run_at <= now. */
    public List<ScheduledReport> findDue(Instant now) {
        return jdbc.query("""
                SELECT * FROM nexus_scheduled_report
                 WHERE status = 'ACTIVE'
                   AND next_run_at <= ?
                """, mapper(), ts(now));
    }

    // ── Mapper ────────────────────────────────────────────────────────────────

    private RowMapper<ScheduledReport> mapper() {
        return (rs, i) -> new ScheduledReport(
                rs.getString("report_key"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("questions_json"),
                rs.getString("agent_key"),
                rs.getString("schedule_type"),
                rs.getString("schedule_time"),
                rs.getString("schedule_day_of_week"),
                rs.getObject("schedule_day_of_month") != null ? rs.getInt("schedule_day_of_month") : null,
                rs.getString("timezone"),
                rs.getString("channel"),
                rs.getString("slack_webhook"),
                rs.getString("email_to"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("last_run_at")),
                toInstant(rs.getTimestamp("next_run_at")),
                rs.getString("last_run_status"),
                rs.getString("last_run_message"),
                rs.getString("created_by"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private Timestamp ts(Instant i)  { return i != null ? Timestamp.from(i) : null; }
    private Instant toInstant(Timestamp ts) { return ts != null ? ts.toInstant() : null; }
}
