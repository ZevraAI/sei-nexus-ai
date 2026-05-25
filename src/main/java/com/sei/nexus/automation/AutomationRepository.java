package com.sei.nexus.automation;

import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class AutomationRepository {

    private final JdbcTemplate jdbc;

    public AutomationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Workflow SQL ──────────────────────────────────────────────────────────

    private static final String INSERT_WORKFLOW = """
            INSERT INTO nexus_automation_workflow
              (id, tenant_schema, name, description, slug, status, trigger_type, graph, created_by, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?::jsonb,?,NOW(),NOW())
            """;

    private static final String UPDATE_WORKFLOW = """
            UPDATE nexus_automation_workflow
               SET name=?, description=?, slug=?, status=?, trigger_type=?, graph=?::jsonb, updated_at=NOW()
             WHERE id=? AND tenant_schema=?
            """;

    private static final String SELECT_WORKFLOWS_BY_TENANT = """
            SELECT id, tenant_schema, name, description, slug, status, trigger_type,
                   graph::text AS graph, created_by, created_at, updated_at
              FROM nexus_automation_workflow
             WHERE tenant_schema=?
             ORDER BY updated_at DESC
            """;

    private static final String SELECT_WORKFLOW_BY_ID = """
            SELECT id, tenant_schema, name, description, slug, status, trigger_type,
                   graph::text AS graph, created_by, created_at, updated_at
              FROM nexus_automation_workflow
             WHERE id=? AND tenant_schema=?
            """;

    private static final String SELECT_WORKFLOW_BY_SLUG = """
            SELECT id, tenant_schema, name, description, slug, status, trigger_type,
                   graph::text AS graph, created_by, created_at, updated_at
              FROM nexus_automation_workflow
             WHERE slug=? AND tenant_schema=? AND status='ACTIVE'
            """;

    private static final String DELETE_WORKFLOW = """
            UPDATE nexus_automation_workflow SET status='ARCHIVED', updated_at=NOW()
             WHERE id=? AND tenant_schema=?
            """;

    // ── Execution SQL ─────────────────────────────────────────────────────────

    private static final String INSERT_EXECUTION = """
            INSERT INTO nexus_automation_execution
              (id, workflow_id, tenant_schema, trigger_payload, status, step_traces, final_output, error_message, started_at)
            VALUES (?,?,?,?::jsonb,'RUNNING','[]'::jsonb,NULL,NULL,NOW())
            """;

    private static final String UPDATE_EXECUTION_COMPLETE = """
            UPDATE nexus_automation_execution
               SET status=?, step_traces=?::jsonb, final_output=?::jsonb, error_message=?, completed_at=NOW()
             WHERE id=?
            """;

    private static final String SELECT_EXECUTIONS_BY_WORKFLOW = """
            SELECT id, workflow_id, tenant_schema,
                   trigger_payload::text AS trigger_payload,
                   status,
                   step_traces::text     AS step_traces,
                   final_output::text    AS final_output,
                   error_message, started_at, completed_at
              FROM nexus_automation_execution
             WHERE workflow_id=? AND tenant_schema=?
             ORDER BY started_at DESC
             LIMIT 50
            """;

    private static final String SELECT_EXECUTION_BY_ID = """
            SELECT id, workflow_id, tenant_schema,
                   trigger_payload::text AS trigger_payload,
                   status,
                   step_traces::text     AS step_traces,
                   final_output::text    AS final_output,
                   error_message, started_at, completed_at
              FROM nexus_automation_execution
             WHERE id=? AND tenant_schema=?
            """;

    // ── RowMappers ────────────────────────────────────────────────────────────

    private static final RowMapper<AutomationWorkflow> WORKFLOW_RM = (rs, i) ->
            new AutomationWorkflow(
                    rs.getString("id"),
                    rs.getString("tenant_schema"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("slug"),
                    rs.getString("status"),
                    rs.getString("trigger_type"),
                    rs.getString("graph"),
                    rs.getString("created_by"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at"))
            );

    private static final RowMapper<AutomationExecution> EXECUTION_RM = (rs, i) ->
            new AutomationExecution(
                    rs.getString("id"),
                    rs.getString("workflow_id"),
                    rs.getString("tenant_schema"),
                    rs.getString("trigger_payload"),
                    rs.getString("status"),
                    rs.getString("step_traces"),
                    rs.getString("final_output"),
                    rs.getString("error_message"),
                    toInstant(rs.getTimestamp("started_at")),
                    toInstant(rs.getTimestamp("completed_at"))
            );

    // ── Workflow ops ──────────────────────────────────────────────────────────

    public void insertWorkflow(AutomationWorkflow w) {
        jdbc.update(INSERT_WORKFLOW,
                w.id(), w.tenantSchema(), w.name(), w.description(),
                w.slug(), w.status(), w.triggerType(), w.graphJson(), w.createdBy());
    }

    public void updateWorkflow(AutomationWorkflow w) {
        int rows = jdbc.update(UPDATE_WORKFLOW,
                w.name(), w.description(), w.slug(), w.status(), w.triggerType(),
                w.graphJson(), w.id(), w.tenantSchema());
        if (rows == 0) throw new NexusException(HttpStatus.NOT_FOUND, "Workflow not found: " + w.id());
    }

    public List<AutomationWorkflow> findByTenant(String tenantSchema) {
        return jdbc.query(SELECT_WORKFLOWS_BY_TENANT, WORKFLOW_RM, tenantSchema);
    }

    public Optional<AutomationWorkflow> findById(String id, String tenantSchema) {
        return jdbc.query(SELECT_WORKFLOW_BY_ID, WORKFLOW_RM, id, tenantSchema)
                   .stream().findFirst();
    }

    public Optional<AutomationWorkflow> findBySlug(String slug, String tenantSchema) {
        return jdbc.query(SELECT_WORKFLOW_BY_SLUG, WORKFLOW_RM, slug, tenantSchema)
                   .stream().findFirst();
    }

    public boolean slugExists(String slug, String tenantSchema) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nexus_automation_workflow WHERE tenant_schema=? AND slug=?",
                Integer.class, tenantSchema, slug);
        return count != null && count > 0;
    }

    public void archiveWorkflow(String id, String tenantSchema) {
        int rows = jdbc.update(DELETE_WORKFLOW, id, tenantSchema);
        if (rows == 0) throw new NexusException(HttpStatus.NOT_FOUND, "Workflow not found: " + id);
    }

    // ── Execution ops ─────────────────────────────────────────────────────────

    public void insertExecution(AutomationExecution e) {
        jdbc.update(INSERT_EXECUTION,
                e.id(), e.workflowId(), e.tenantSchema(), e.triggerPayloadJson());
    }

    public void completeExecution(String executionId, String status,
                                  String stepTracesJson, String finalOutputJson,
                                  String errorMessage) {
        jdbc.update(UPDATE_EXECUTION_COMPLETE,
                status, stepTracesJson, finalOutputJson, errorMessage, executionId);
    }

    public List<AutomationExecution> findExecutionsByWorkflow(String workflowId, String tenantSchema) {
        return jdbc.query(SELECT_EXECUTIONS_BY_WORKFLOW, EXECUTION_RM, workflowId, tenantSchema);
    }

    public Optional<AutomationExecution> findExecutionById(String id, String tenantSchema) {
        return jdbc.query(SELECT_EXECUTION_BY_ID, EXECUTION_RM, id, tenantSchema)
                   .stream().findFirst();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
