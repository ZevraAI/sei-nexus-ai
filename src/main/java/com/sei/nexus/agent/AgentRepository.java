package com.sei.nexus.agent;

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
public class AgentRepository {

    private static final String FIND_ALL = """
            SELECT agent_key, name, purpose, domain_keys, connection_keys, rest_api_enabled,
                   action_scope, version_no, status, created_by, created_at, updated_at
              FROM nexus_agent
             ORDER BY name
            """;

    private static final String FIND_BY_KEY = """
            SELECT agent_key, name, purpose, domain_keys, connection_keys, rest_api_enabled,
                   action_scope, version_no, status, created_by, created_at, updated_at
              FROM nexus_agent
             WHERE agent_key = ?
            """;

    private static final String FIND_ACTIVE = """
            SELECT agent_key, name, purpose, domain_keys, connection_keys, rest_api_enabled,
                   action_scope, version_no, status, created_by, created_at, updated_at
              FROM nexus_agent
             WHERE status = 'ACTIVE'
             ORDER BY name
            """;

    private static final String UPSERT_AGENT = """
            INSERT INTO nexus_agent
                (agent_key, name, purpose, domain_keys, connection_keys, rest_api_enabled,
                 action_scope, version_no, status, created_by, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (agent_key) DO UPDATE SET
                name             = EXCLUDED.name,
                purpose          = EXCLUDED.purpose,
                domain_keys      = EXCLUDED.domain_keys,
                connection_keys  = EXCLUDED.connection_keys,
                rest_api_enabled = EXCLUDED.rest_api_enabled,
                action_scope     = EXCLUDED.action_scope,
                version_no       = EXCLUDED.version_no,
                status           = EXCLUDED.status,
                updated_at       = NOW()
            """;

    private static final String ARCHIVE_AGENT = """
            UPDATE nexus_agent SET status = 'ARCHIVED', updated_at = NOW()
             WHERE agent_key = ?
            """;

    private static final String INSERT_VERSION = """
            INSERT INTO nexus_agent_version
                (version_key, agent_key, version_no, snapshot, created_at)
            VALUES (?,?,?,?,?)
            ON CONFLICT (version_key) DO NOTHING
            """;

    private static final String FIND_VERSIONS_BY_AGENT = """
            SELECT version_key, agent_key, version_no, snapshot, created_at
              FROM nexus_agent_version
             WHERE agent_key = ?
             ORDER BY version_no DESC
            """;

    private static final String FIND_VERSION_BY_NO = """
            SELECT version_key, agent_key, version_no, snapshot, created_at
              FROM nexus_agent_version
             WHERE agent_key = ? AND version_no = ?
            """;

    private static final String UPSERT_PLAYBOOK = """
            INSERT INTO nexus_agent_playbook
                (playbook_key, agent_key, name, trigger_conditions, investigation_steps,
                 escalation_rules, confidence_threshold, preferred_evidence_order,
                 max_investigation_steps, status, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (playbook_key) DO UPDATE SET
                agent_key                = EXCLUDED.agent_key,
                name                     = EXCLUDED.name,
                trigger_conditions       = EXCLUDED.trigger_conditions,
                investigation_steps      = EXCLUDED.investigation_steps,
                escalation_rules         = EXCLUDED.escalation_rules,
                confidence_threshold     = EXCLUDED.confidence_threshold,
                preferred_evidence_order = EXCLUDED.preferred_evidence_order,
                max_investigation_steps  = EXCLUDED.max_investigation_steps,
                status                   = EXCLUDED.status,
                updated_at               = NOW()
            """;

    private static final String FIND_PLAYBOOKS_BY_AGENT = """
            SELECT playbook_key, agent_key, name, trigger_conditions, investigation_steps,
                   escalation_rules, confidence_threshold, preferred_evidence_order,
                   max_investigation_steps, status, created_at, updated_at
              FROM nexus_agent_playbook
             WHERE agent_key = ? AND status = 'ACTIVE'
             ORDER BY name
            """;

    private static final String UPSERT_KPI = """
            INSERT INTO nexus_agent_kpi
                (kpi_key, agent_key, domain_key, kpi_name, kpi_description,
                 measurement_object_key, measurement_sql, threshold_warning, threshold_critical,
                 higher_is_better, refresh_interval_hrs, status, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (kpi_key) DO UPDATE SET
                agent_key              = EXCLUDED.agent_key,
                domain_key             = EXCLUDED.domain_key,
                kpi_name               = EXCLUDED.kpi_name,
                kpi_description        = EXCLUDED.kpi_description,
                measurement_object_key = EXCLUDED.measurement_object_key,
                measurement_sql        = EXCLUDED.measurement_sql,
                threshold_warning      = EXCLUDED.threshold_warning,
                threshold_critical     = EXCLUDED.threshold_critical,
                higher_is_better       = EXCLUDED.higher_is_better,
                refresh_interval_hrs   = EXCLUDED.refresh_interval_hrs,
                status                 = EXCLUDED.status
            """;

    private static final String FIND_KPIS_BY_AGENT = """
            SELECT kpi_key, agent_key, domain_key, kpi_name, kpi_description,
                   measurement_object_key, measurement_sql, threshold_warning, threshold_critical,
                   higher_is_better, refresh_interval_hrs, status, created_at
              FROM nexus_agent_kpi
             WHERE agent_key = ?
             ORDER BY kpi_name
            """;

    private final JdbcTemplate jdbc;

    public AgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // NexusAgent
    // -------------------------------------------------------------------------

    public List<NexusAgent> findAll() {
        return jdbc.query(FIND_ALL, agentMapper());
    }

    public Optional<NexusAgent> findByKey(String key) {
        List<NexusAgent> rows = jdbc.query(FIND_BY_KEY, agentMapper(), key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<NexusAgent> findActive() {
        return jdbc.query(FIND_ACTIVE, agentMapper());
    }

    public void save(NexusAgent agent) {
        jdbc.update(UPSERT_AGENT,
            agent.agentKey(), agent.name(), agent.purpose(),
            agent.domainKeys(), agent.connectionKeys(),
            agent.restApiEnabled(), agent.actionScope(),
            agent.versionNo(), agent.status(), agent.createdBy(),
            toTimestamp(agent.createdAt() != null ? agent.createdAt() : Instant.now()),
            toTimestamp(agent.updatedAt() != null ? agent.updatedAt() : Instant.now()));
    }

    public void archive(String agentKey) {
        jdbc.update(ARCHIVE_AGENT, agentKey);
    }

    // -------------------------------------------------------------------------
    // AgentVersion
    // -------------------------------------------------------------------------

    public void saveVersion(AgentVersion v) {
        jdbc.update(INSERT_VERSION,
            v.versionKey(), v.agentKey(), v.versionNo(), v.snapshot(),
            toTimestamp(v.createdAt() != null ? v.createdAt() : Instant.now()));
    }

    public List<AgentVersion> findVersionsByAgent(String agentKey) {
        return jdbc.query(FIND_VERSIONS_BY_AGENT, versionMapper(), agentKey);
    }

    public Optional<AgentVersion> findVersionByNo(String agentKey, int versionNo) {
        List<AgentVersion> rows = jdbc.query(FIND_VERSION_BY_NO, versionMapper(), agentKey, versionNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // -------------------------------------------------------------------------
    // AgentPlaybook
    // -------------------------------------------------------------------------

    public void savePlaybook(AgentPlaybook p) {
        jdbc.update(UPSERT_PLAYBOOK,
            p.playbookKey(), p.agentKey(), p.name(), p.triggerConditions(),
            p.investigationSteps(), p.escalationRules(), p.confidenceThreshold(),
            p.preferredEvidenceOrder(), p.maxInvestigationSteps(), p.status(),
            toTimestamp(p.createdAt() != null ? p.createdAt() : Instant.now()),
            toTimestamp(p.updatedAt() != null ? p.updatedAt() : Instant.now()));
    }

    public List<AgentPlaybook> findPlaybooksByAgent(String agentKey) {
        return jdbc.query(FIND_PLAYBOOKS_BY_AGENT, playbookMapper(), agentKey);
    }

    // -------------------------------------------------------------------------
    // AgentKpi
    // -------------------------------------------------------------------------

    public void saveKpi(AgentKpi k) {
        jdbc.update(UPSERT_KPI,
            k.kpiKey(), k.agentKey(), k.domainKey(), k.kpiName(), k.kpiDescription(),
            k.measurementObjectKey(), k.measurementSql(), k.thresholdWarning(), k.thresholdCritical(),
            k.higherIsBetter(), k.refreshIntervalHrs(), k.status(),
            toTimestamp(k.createdAt() != null ? k.createdAt() : Instant.now()));
    }

    public List<AgentKpi> findKpisByAgent(String agentKey) {
        return jdbc.query(FIND_KPIS_BY_AGENT, kpiMapper(), agentKey);
    }

    // -------------------------------------------------------------------------
    // Row mappers
    // -------------------------------------------------------------------------

    private RowMapper<NexusAgent> agentMapper() {
        return (rs, rowNum) -> new NexusAgent(
            rs.getString("agent_key"),
            rs.getString("name"),
            rs.getString("purpose"),
            rs.getString("domain_keys"),
            rs.getString("connection_keys"),
            rs.getBoolean("rest_api_enabled"),
            rs.getString("action_scope"),
            rs.getInt("version_no"),
            rs.getString("status"),
            rs.getString("created_by"),
            toInstant(rs, "created_at"),
            toInstant(rs, "updated_at"));
    }

    private RowMapper<AgentVersion> versionMapper() {
        return (rs, rowNum) -> new AgentVersion(
            rs.getString("version_key"),
            rs.getString("agent_key"),
            rs.getInt("version_no"),
            rs.getString("snapshot"),
            toInstant(rs, "created_at"));
    }

    private RowMapper<AgentPlaybook> playbookMapper() {
        return (rs, rowNum) -> {
            Double confidenceThreshold = rs.getDouble("confidence_threshold");
            if (rs.wasNull()) confidenceThreshold = null;
            Integer maxSteps = rs.getInt("max_investigation_steps");
            if (rs.wasNull()) maxSteps = null;
            return new AgentPlaybook(
                rs.getString("playbook_key"),
                rs.getString("agent_key"),
                rs.getString("name"),
                rs.getString("trigger_conditions"),
                rs.getString("investigation_steps"),
                rs.getString("escalation_rules"),
                confidenceThreshold,
                rs.getString("preferred_evidence_order"),
                maxSteps,
                rs.getString("status"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
        };
    }

    private RowMapper<AgentKpi> kpiMapper() {
        return (rs, rowNum) -> {
            Double thresholdWarning = rs.getDouble("threshold_warning");
            if (rs.wasNull()) thresholdWarning = null;
            Double thresholdCritical = rs.getDouble("threshold_critical");
            if (rs.wasNull()) thresholdCritical = null;
            Integer refreshInterval = rs.getInt("refresh_interval_hrs");
            if (rs.wasNull()) refreshInterval = null;
            return new AgentKpi(
                rs.getString("kpi_key"),
                rs.getString("agent_key"),
                rs.getString("domain_key"),
                rs.getString("kpi_name"),
                rs.getString("kpi_description"),
                rs.getString("measurement_object_key"),
                rs.getString("measurement_sql"),
                thresholdWarning,
                thresholdCritical,
                rs.getBoolean("higher_is_better"),
                refreshInterval,
                rs.getString("status"),
                toInstant(rs, "created_at"));
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
