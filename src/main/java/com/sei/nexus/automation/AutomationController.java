package com.sei.nexus.automation;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/automations")
public class AutomationController {

    private final AutomationService service;
    private final AutomationGeneratorService generatorService;

    public AutomationController(AutomationService service,
                                AutomationGeneratorService generatorService) {
        this.service          = service;
        this.generatorService = generatorService;
    }

    // ── Workflow CRUD ─────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<AutomationWorkflow>> list() {
        return ResponseEntity.ok(service.listWorkflows(tenantSchema()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AutomationWorkflow> get(@PathVariable String id) {
        return ResponseEntity.ok(service.getWorkflow(id, tenantSchema()));
    }

    @PostMapping
    public ResponseEntity<AutomationWorkflow> create(@RequestBody Map<String, Object> body) {
        AutomationWorkflow w = service.createWorkflow(
                tenantSchema(),
                str(body, "name"),
                str(body, "description"),
                str(body, "slug"),
                str(body, "triggerType"),
                str(body, "graph"),
                currentUser().email()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(w);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AutomationWorkflow> update(@PathVariable String id,
                                                     @RequestBody Map<String, Object> body) {
        AutomationWorkflow w = service.updateWorkflow(
                id, tenantSchema(),
                str(body, "name"),
                str(body, "description"),
                str(body, "slug"),
                str(body, "status"),
                str(body, "triggerType"),
                str(body, "graph")
        );
        return ResponseEntity.ok(w);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable String id) {
        service.archiveWorkflow(id, tenantSchema());
        return ResponseEntity.noContent().build();
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    /** Manual trigger from the UI Test Panel — requires auth. */
    @PostMapping("/{id}/run")
    public ResponseEntity<AutomationExecution> manualRun(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> payload) {
        AutomationExecution exec = service.triggerById(id, tenantSchema(),
                payload != null ? payload : Map.of());
        return ResponseEntity.ok(exec);
    }

    @GetMapping("/{id}/executions")
    public ResponseEntity<List<AutomationExecution>> executions(@PathVariable String id) {
        return ResponseEntity.ok(service.listExecutions(id, tenantSchema()));
    }

    @GetMapping("/executions/{execId}")
    public ResponseEntity<AutomationExecution> execution(@PathVariable String execId) {
        return ResponseEntity.ok(service.getExecution(execId, tenantSchema()));
    }

    // ── Public webhook endpoint (no auth required — protected by slug secrecy) ─

    /**
     * POST /api/automations/run/{slug}
     * Called by external systems to trigger an active workflow.
     * Uses the tenant header to resolve the workspace; auth filter allows public access.
     */
    @PostMapping("/run/{slug}")
    public ResponseEntity<Map<String, Object>> webhookRun(
            @PathVariable String slug,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Nexus-Tenant", required = false) String tenantHeader) {

        String schema = tenantHeader != null && !tenantHeader.isBlank()
                ? tenantHeader : tenantSchema();
        AutomationExecution exec = service.triggerBySlug(slug, schema,
                payload != null ? payload : Map.of());

        Map<String, Object> response = Map.of(
                "executionId", exec.id(),
                "status",      exec.status(),
                "workflowId",  exec.workflowId()
        );
        return ResponseEntity.ok(response);
    }

    // ── AI Workflow Generation ────────────────────────────────────────────────

    @PostMapping("/generate/analyze")
    public ResponseEntity<Map<String, Object>> analyzeRequirement(
            @RequestBody Map<String, Object> body) {
        String requirement = str(body, "requirement");
        if (requirement == null || requirement.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "requirement is required"));
        return ResponseEntity.ok(generatorService.analyze(requirement));
    }

    @PostMapping("/generate")
    public ResponseEntity<AutomationWorkflow> generateWorkflow(
            @RequestBody Map<String, Object> body) {
        String requirement = str(body, "requirement");
        String summary     = str(body, "summary");
        String workflowId  = str(body, "workflowId"); // null → create, non-null → update graph

        @SuppressWarnings("unchecked")
        List<String> connections = body.get("selectedConnections") instanceof List<?> l
                ? (List<String>) l : List.of();
        @SuppressWarnings("unchecked")
        List<String> tables = body.get("selectedTables") instanceof List<?> l
                ? (List<String>) l : List.of();

        AutomationWorkflow w = generatorService.generate(
                requirement, summary, connections, tables,
                workflowId, tenantSchema(), currentUser().email());
        return ResponseEntity.status(workflowId != null ? HttpStatus.OK : HttpStatus.CREATED).body(w);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String tenantSchema() {
        return TenantContext.getSchema();
    }

    private UserAccount currentUser() {
        return (UserAccount) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }
}
