package com.sei.nexus.brief;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class MorningBriefRepository {

    private final JdbcTemplate jdbc;

    public MorningBriefRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Brief CRUD ────────────────────────────────────────────────────────────

    public void insertBrief(MorningBrief brief) {
        jdbc.update("""
                INSERT INTO nexus_morning_brief
                    (id, tenant_schema, brief_date, status, headline, sections_json,
                     agents_used, generated_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::text[], ?, NOW())
                ON CONFLICT (tenant_schema, brief_date) DO NOTHING
                """,
                brief.id(), brief.tenantSchema(), brief.briefDate(),
                brief.status(), brief.headline(), brief.sectionsJson(),
                toArrayLiteral(brief.agentsUsed()),
                brief.generatedAt() != null ? java.sql.Timestamp.from(brief.generatedAt()) : null);
    }

    public void updateBriefReady(String id, String headline, String sectionsJson,
                                  List<String> agentsUsed) {
        jdbc.update("""
                UPDATE nexus_morning_brief
                   SET status = 'READY', headline = ?, sections_json = ?::jsonb,
                       agents_used = ?::text[], generated_at = NOW()
                 WHERE id = ?
                """, headline, sectionsJson, toArrayLiteral(agentsUsed), id);
    }

    public void updateBriefFailed(String id) {
        jdbc.update("UPDATE nexus_morning_brief SET status = 'FAILED' WHERE id = ?", id);
    }

    public void deleteBrief(String tenantSchema, LocalDate date) {
        jdbc.update("DELETE FROM nexus_morning_brief WHERE tenant_schema = ? AND brief_date = ?",
                tenantSchema, date);
    }

    public Optional<MorningBrief> findByDate(String tenantSchema, LocalDate date) {
        List<MorningBrief> rows = jdbc.query(
                "SELECT * FROM nexus_morning_brief WHERE tenant_schema = ? AND brief_date = ?",
                briefMapper(), tenantSchema, date);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<MorningBrief> findRecent(String tenantSchema, int limit) {
        return jdbc.query(
                "SELECT * FROM nexus_morning_brief WHERE tenant_schema = ? " +
                "ORDER BY brief_date DESC LIMIT ?",
                briefMapper(), tenantSchema, limit);
    }

    // ── Config CRUD ───────────────────────────────────────────────────────────

    public void upsertConfig(MorningBriefConfig config) {
        jdbc.update("""
                INSERT INTO nexus_morning_brief_config
                    (tenant_schema, schedule_time, timezone, enabled, email_to,
                     brief_agent_ids, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::text[], NOW())
                ON CONFLICT (tenant_schema) DO UPDATE SET
                    schedule_time    = EXCLUDED.schedule_time,
                    timezone         = EXCLUDED.timezone,
                    enabled          = EXCLUDED.enabled,
                    email_to         = EXCLUDED.email_to,
                    brief_agent_ids  = EXCLUDED.brief_agent_ids,
                    updated_at       = NOW()
                """,
                config.tenantSchema(), config.scheduleTime(), config.timezone(),
                config.enabled(), config.emailTo(),
                toArrayLiteral(config.briefAgentIds()));
    }

    public Optional<MorningBriefConfig> findConfig(String tenantSchema) {
        List<MorningBriefConfig> rows = jdbc.query(
                "SELECT * FROM nexus_morning_brief_config WHERE tenant_schema = ?",
                configMapper(), tenantSchema);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<MorningBriefConfig> findAllEnabledConfigs() {
        return jdbc.query(
                "SELECT * FROM nexus_morning_brief_config WHERE enabled = true",
                configMapper());
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private RowMapper<MorningBrief> briefMapper() {
        return (rs, i) -> new MorningBrief(
                rs.getString("id"),
                rs.getString("tenant_schema"),
                rs.getDate("brief_date").toLocalDate(),
                rs.getString("status"),
                rs.getString("headline"),
                rs.getString("sections_json"),
                toStringList(rs.getArray("agents_used")),
                rs.getTimestamp("generated_at") != null
                        ? rs.getTimestamp("generated_at").toInstant() : null,
                rs.getTimestamp("created_at").toInstant());
    }

    private RowMapper<MorningBriefConfig> configMapper() {
        return (rs, i) -> new MorningBriefConfig(
                rs.getString("tenant_schema"),
                rs.getString("schedule_time"),
                rs.getString("timezone"),
                rs.getBoolean("enabled"),
                rs.getString("email_to"),
                toStringList(rs.getArray("brief_agent_ids")),
                rs.getTimestamp("updated_at").toInstant());
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
