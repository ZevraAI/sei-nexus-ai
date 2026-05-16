package com.sei.nexus.agent;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/agents")
public class AgentController {

    private final AgentService agentService;
    private final AgentRepository agentRepository;

    public AgentController(AgentService agentService, AgentRepository agentRepository) {
        this.agentService = agentService;
        this.agentRepository = agentRepository;
    }

    // -------------------------------------------------------------------------
    // Agents
    // -------------------------------------------------------------------------

    /**
     * GET /agents
     * Lists all agents.
     */
    @GetMapping
    public ResponseEntity<List<NexusAgent>> listAgents() {
        return ResponseEntity.ok(agentRepository.findAll());
    }

    /**
     * POST /agents
     * Creates or updates an agent.
     */
    @PostMapping
    public ResponseEntity<NexusAgent> createOrUpdateAgent(@RequestBody Map<String, Object> body) {
        UserAccount user = currentUser();
        NexusAgent agent = agentService.createOrUpdate(body, user.email());
        return ResponseEntity.status(HttpStatus.OK).body(agent);
    }

    /**
     * DELETE /agents/{agentKey}
     * Archives the agent (soft delete).
     */
    @DeleteMapping("/{agentKey}")
    public ResponseEntity<Void> archiveAgent(@PathVariable String agentKey) {
        agentRepository.findByKey(agentKey)
            .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                "Agent not found: " + agentKey));
        agentRepository.archive(agentKey);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Versions
    // -------------------------------------------------------------------------

    /**
     * GET /agents/{agentKey}/versions
     */
    @GetMapping("/{agentKey}/versions")
    public ResponseEntity<List<AgentVersion>> listVersions(@PathVariable String agentKey) {
        return ResponseEntity.ok(agentRepository.findVersionsByAgent(agentKey));
    }

    /**
     * POST /agents/{agentKey}/versions/{versionNo}/rollback
     */
    @PostMapping("/{agentKey}/versions/{versionNo}/rollback")
    public ResponseEntity<NexusAgent> rollback(@PathVariable String agentKey,
                                                @PathVariable int versionNo) {
        NexusAgent rolled = agentService.rollback(agentKey, versionNo);
        return ResponseEntity.ok(rolled);
    }

    // -------------------------------------------------------------------------
    // Playbooks
    // -------------------------------------------------------------------------

    /**
     * GET /agents/{agentKey}/playbooks
     */
    @GetMapping("/{agentKey}/playbooks")
    public ResponseEntity<List<AgentPlaybook>> listPlaybooks(@PathVariable String agentKey) {
        return ResponseEntity.ok(agentRepository.findPlaybooksByAgent(agentKey));
    }

    /**
     * POST /agents/{agentKey}/playbooks
     */
    @PostMapping("/{agentKey}/playbooks")
    public ResponseEntity<AgentPlaybook> addPlaybook(@PathVariable String agentKey,
                                                      @RequestBody Map<String, Object> body) {
        AgentPlaybook playbook = agentService.addPlaybook(agentKey, body);
        return ResponseEntity.status(HttpStatus.OK).body(playbook);
    }

    /**
     * DELETE /agents/{agentKey}/playbooks/{playbookKey}
     * Archives a playbook by setting its status to ARCHIVED.
     */
    @DeleteMapping("/{agentKey}/playbooks/{playbookKey}")
    public ResponseEntity<Void> archivePlaybook(@PathVariable String agentKey,
                                                 @PathVariable String playbookKey) {
        // Soft-delete by saving with ARCHIVED status — reuse upsert with changed status
        agentRepository.findPlaybooksByAgent(agentKey).stream()
            .filter(p -> p.playbookKey().equals(playbookKey))
            .findFirst()
            .ifPresent(p -> {
                AgentPlaybook archived = new AgentPlaybook(
                    p.playbookKey(), p.agentKey(), p.name(), p.triggerConditions(),
                    p.investigationSteps(), p.escalationRules(), p.confidenceThreshold(),
                    p.preferredEvidenceOrder(), p.maxInvestigationSteps(),
                    "ARCHIVED", p.createdAt(), java.time.Instant.now());
                agentRepository.savePlaybook(archived);
            });
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // KPIs
    // -------------------------------------------------------------------------

    /**
     * GET /agents/{agentKey}/kpis
     */
    @GetMapping("/{agentKey}/kpis")
    public ResponseEntity<List<AgentKpi>> listKpis(@PathVariable String agentKey) {
        return ResponseEntity.ok(agentRepository.findKpisByAgent(agentKey));
    }

    /**
     * POST /agents/{agentKey}/kpis
     */
    @PostMapping("/{agentKey}/kpis")
    public ResponseEntity<AgentKpi> addKpi(@PathVariable String agentKey,
                                            @RequestBody Map<String, Object> body) {
        AgentKpi kpi = agentService.addKpi(agentKey, body);
        return ResponseEntity.status(HttpStatus.OK).body(kpi);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UserAccount currentUser() {
        return (UserAccount) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
