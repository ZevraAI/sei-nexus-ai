package com.sei.nexus.agentrunner;

import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class ZevraAgentRepository {

    private final JdbcTemplate jdbc;

    public ZevraAgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Agent SQL ─────────────────────────────────────────────────────────────

    private static final String INSERT_AGENT = """
            INSERT INTO nexus_zevra_agent
              (id, tenant_schema, name, slug, description, persona, goal,
               connection_keys, max_iterations, status, created_by, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW())
            """;

    private static final String UPDATE_AGENT = """
            UPDATE nexus_zevra_agent
               SET name=?, slug=?, description=?, persona=?, goal=?,
                   connection_keys=?, max_iterations=?, status=?, updated_at=NOW()
             WHERE id=? AND tenant_schema=?
            """;

    private static final String SELECT_AGENTS_BY_TENANT = """
            SELECT id, tenant_schema, name, slug, description, persona, goal,
                   connection_keys, max_iterations, status, created_by, created_at, updated_at
              FROM nexus_zevra_agent
             WHERE tenant_schema=? AND status != 'ARCHIVED'
             ORDER BY updated_at DESC
            """;

    private static final String SELECT_AGENT_BY_ID = """
            SELECT id, tenant_schema, name, slug, description, persona, goal,
                   connection_keys, max_iterations, status, created_by, created_at, updated_at
              FROM nexus_zevra_agent
             WHERE id=? AND tenant_schema=?
            """;

    private static final String ARCHIVE_AGENT = """
            UPDATE nexus_zevra_agent SET status='ARCHIVED', updated_at=NOW()
             WHERE id=? AND tenant_schema=?
            """;

    private static final String SLUG_EXISTS = """
            SELECT COUNT(*) FROM nexus_zevra_agent WHERE tenant_schema=? AND slug=?
            """;

    // ── Session SQL ───────────────────────────────────────────────────────────

    private static final String INSERT_SESSION = """
            INSERT INTO nexus_zevra_session
              (id, agent_id, tenant_schema, input_message, status, steps,
               final_output, error_message, iterations_used, started_at)
            VALUES (?,?,?,?,'RUNNING','[]'::jsonb,NULL,NULL,0,NOW())
            """;

    private static final String COMPLETE_SESSION = """
            UPDATE nexus_zevra_session
               SET status=?, steps=?::jsonb, final_output=?, error_message=?,
                   iterations_used=?, completed_at=NOW()
             WHERE id=?
            """;

    private static final String SELECT_SESSIONS_BY_AGENT = """
            SELECT id, agent_id, tenant_schema, input_message, status,
                   steps::text AS steps, final_output, error_message,
                   iterations_used, started_at, completed_at
              FROM nexus_zevra_session
             WHERE agent_id=? AND tenant_schema=?
             ORDER BY started_at DESC
             LIMIT 50
            """;

    private static final String SELECT_SESSION_BY_ID = """
            SELECT id, agent_id, tenant_schema, input_message, status,
                   steps::text AS steps, final_output, error_message,
                   iterations_used, started_at, completed_at
              FROM nexus_zevra_session
             WHERE id=? AND tenant_schema=?
            """;

    // ── RowMappers ────────────────────────────────────────────────────────────

    private static final RowMapper<ZevraAgent> AGENT_RM = (rs, i) -> {
        Array arr = rs.getArray("connection_keys");
        List<String> keys = arr != null
                ? Arrays.asList((String[]) arr.getArray())
                : Collections.emptyList();
        return new ZevraAgent(
                rs.getString("id"),
                rs.getString("tenant_schema"),
                rs.getString("name"),
                rs.getString("slug"),
                rs.getString("description"),
                rs.getString("persona"),
                rs.getString("goal"),
                keys,
                rs.getInt("max_iterations"),
                rs.getString("status"),
                rs.getString("created_by"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    };

    private static final RowMapper<ZevraSession> SESSION_RM = (rs, i) ->
            new ZevraSession(
                    rs.getString("id"),
                    rs.getString("agent_id"),
                    rs.getString("tenant_schema"),
                    rs.getString("input_message"),
                    rs.getString("status"),
                    rs.getString("steps"),
                    rs.getString("final_output"),
                    rs.getString("error_message"),
                    rs.getInt("iterations_used"),
                    toInstant(rs.getTimestamp("started_at")),
                    toInstant(rs.getTimestamp("completed_at"))
            );

    // ── Agent ops ─────────────────────────────────────────────────────────────

    public void insertAgent(ZevraAgent a) {
        jdbc.update(INSERT_AGENT,
                a.id(), a.tenantSchema(), a.name(), a.slug(), a.description(),
                a.persona(), a.goal(),
                a.connectionKeys().toArray(String[]::new),
                a.maxIterations(), a.status(), a.createdBy());
    }

    public void updateAgent(ZevraAgent a) {
        int rows = jdbc.update(UPDATE_AGENT,
                a.name(), a.slug(), a.description(), a.persona(), a.goal(),
                a.connectionKeys().toArray(String[]::new),
                a.maxIterations(), a.status(),
                a.id(), a.tenantSchema());
        if (rows == 0)
            throw new NexusException(HttpStatus.NOT_FOUND, "Agent not found: " + a.id());
    }

    public List<ZevraAgent> findByTenant(String tenantSchema) {
        return jdbc.query(SELECT_AGENTS_BY_TENANT, AGENT_RM, tenantSchema);
    }

    public Optional<ZevraAgent> findById(String id, String tenantSchema) {
        return jdbc.query(SELECT_AGENT_BY_ID, AGENT_RM, id, tenantSchema).stream().findFirst();
    }

    public void archiveAgent(String id, String tenantSchema) {
        int rows = jdbc.update(ARCHIVE_AGENT, id, tenantSchema);
        if (rows == 0)
            throw new NexusException(HttpStatus.NOT_FOUND, "Agent not found: " + id);
    }

    public boolean slugExists(String slug, String tenantSchema) {
        Integer count = jdbc.queryForObject(SLUG_EXISTS, Integer.class, tenantSchema, slug);
        return count != null && count > 0;
    }

    // ── Session ops ───────────────────────────────────────────────────────────

    public void insertSession(ZevraSession s) {
        jdbc.update(INSERT_SESSION, s.id(), s.agentId(), s.tenantSchema(), s.inputMessage());
    }

    public void completeSession(String sessionId, String status, String stepsJson,
                                String finalOutput, String errorMessage, int iterations) {
        jdbc.update(COMPLETE_SESSION, status, stepsJson, finalOutput, errorMessage,
                iterations, sessionId);
    }

    public List<ZevraSession> findSessionsByAgent(String agentId, String tenantSchema) {
        return jdbc.query(SELECT_SESSIONS_BY_AGENT, SESSION_RM, agentId, tenantSchema);
    }

    public Optional<ZevraSession> findSessionById(String id, String tenantSchema) {
        return jdbc.query(SELECT_SESSION_BY_ID, SESSION_RM, id, tenantSchema).stream().findFirst();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
