package com.sei.nexus.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AutomationService {

    private final AutomationRepository repo;
    private final WorkflowExecutionEngine engine;
    private final ObjectMapper mapper;

    public AutomationService(AutomationRepository repo,
                             WorkflowExecutionEngine engine,
                             ObjectMapper mapper) {
        this.repo   = repo;
        this.engine = engine;
        this.mapper = mapper;
    }

    // ── Workflow CRUD ─────────────────────────────────────────────────────────

    public List<AutomationWorkflow> listWorkflows(String tenantSchema) {
        return repo.findByTenant(tenantSchema);
    }

    public AutomationWorkflow getWorkflow(String id, String tenantSchema) {
        return repo.findById(id, tenantSchema)
                   .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND, "Workflow not found: " + id));
    }

    public AutomationWorkflow createWorkflow(String tenantSchema, String name,
                                             String description, String slug,
                                             String triggerType, String graphJson,
                                             String createdBy) {
        validateSlug(slug);
        String graphToStore = (graphJson == null || graphJson.isBlank())
                ? "{\"nodes\":[],\"edges\":[]}" : graphJson;

        AutomationWorkflow w = new AutomationWorkflow(
                Keys.uniqueKey("wf"), tenantSchema, name, description, slug,
                "DRAFT", triggerType != null ? triggerType : "WEBHOOK",
                graphToStore, createdBy, null, null
        );
        repo.insertWorkflow(w);
        return repo.findById(w.id(), tenantSchema).orElseThrow();
    }

    public AutomationWorkflow updateWorkflow(String id, String tenantSchema,
                                             String name, String description,
                                             String slug, String status,
                                             String triggerType, String graphJson) {
        AutomationWorkflow existing = getWorkflow(id, tenantSchema);
        String effectiveSlug = slug != null ? slug : existing.slug();
        validateSlug(effectiveSlug);

        AutomationWorkflow updated = new AutomationWorkflow(
                id, tenantSchema,
                name        != null ? name        : existing.name(),
                description != null ? description : existing.description(),
                effectiveSlug,
                status      != null ? status      : existing.status(),
                triggerType != null ? triggerType : existing.triggerType(),
                graphJson   != null ? graphJson   : existing.graphJson(),
                existing.createdBy(), existing.createdAt(), existing.updatedAt()
        );
        repo.updateWorkflow(updated);
        return repo.findById(id, tenantSchema).orElseThrow();
    }

    public void archiveWorkflow(String id, String tenantSchema) {
        repo.archiveWorkflow(id, tenantSchema);
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    /** Trigger a workflow by slug (used by webhook endpoint). */
    public AutomationExecution triggerBySlug(String slug, String tenantSchema,
                                             Map<String, Object> payload) {
        AutomationWorkflow workflow = repo.findBySlug(slug, tenantSchema)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "No active workflow with slug: " + slug));
        return runWorkflow(workflow, payload);
    }

    /** Manually trigger a workflow by ID (used by UI Test Panel). */
    public AutomationExecution triggerById(String workflowId, String tenantSchema,
                                           Map<String, Object> payload) {
        AutomationWorkflow workflow = getWorkflow(workflowId, tenantSchema);
        return runWorkflow(workflow, payload);
    }

    public List<AutomationExecution> listExecutions(String workflowId, String tenantSchema) {
        getWorkflow(workflowId, tenantSchema); // existence check
        return repo.findExecutionsByWorkflow(workflowId, tenantSchema);
    }

    public AutomationExecution getExecution(String executionId, String tenantSchema) {
        return repo.findExecutionById(executionId, tenantSchema)
                   .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                           "Execution not found: " + executionId));
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private AutomationExecution runWorkflow(AutomationWorkflow workflow,
                                            Map<String, Object> payload) {
        String execId = Keys.uniqueKey("exec");
        String payloadJson = toJson(payload != null ? payload : Map.of());

        AutomationExecution pending = new AutomationExecution(
                execId, workflow.id(), workflow.tenantSchema(),
                payloadJson, "RUNNING", "[]", null, null, null, null);
        repo.insertExecution(pending);

        try {
            WorkflowExecutionEngine.ExecutionResult result =
                    engine.execute(workflow.graphJson(), payload != null ? payload : Map.of());

            repo.completeExecution(
                    execId,
                    result.status(),
                    toJson(result.stepTraces()),
                    toJson(result.finalOutput()),
                    result.errorMessage()
            );
        } catch (Exception e) {
            repo.completeExecution(execId, "FAILED", "[]", null, e.getMessage());
        }

        return repo.findExecutionById(execId, workflow.tenantSchema()).orElseThrow();
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }

    private void validateSlug(String slug) {
        if (slug == null || !slug.matches("[a-z0-9][a-z0-9\\-]*"))
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Slug must be lowercase letters, digits, and hyphens (e.g. order-fulfillment)");
    }
}
