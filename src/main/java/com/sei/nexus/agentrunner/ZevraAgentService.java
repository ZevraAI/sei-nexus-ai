package com.sei.nexus.agentrunner;

import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ZevraAgentService {

    private final ZevraAgentRepository repository;
    private final AgentRunner           runner;

    public ZevraAgentService(ZevraAgentRepository repository, AgentRunner runner) {
        this.repository = repository;
        this.runner     = runner;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public List<ZevraAgent> listAgents(String tenantSchema) {
        return repository.findByTenant(tenantSchema);
    }

    public ZevraAgent getAgent(String id, String tenantSchema) {
        return repository.findById(id, tenantSchema)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Agent not found: " + id));
    }

    public ZevraAgent createAgent(String tenantSchema, String name, String description,
                                   String persona, String goal,
                                   List<String> connectionKeys, int maxIterations,
                                   String createdBy) {
        String slug = uniqueSlug(slugify(name), tenantSchema);
        String id   = Keys.uniqueKey("ag");
        ZevraAgent agent = new ZevraAgent(id, tenantSchema, name, slug, description,
                persona, goal, connectionKeys, maxIterations, "DRAFT",
                createdBy, null, null);
        repository.insertAgent(agent);
        return getAgent(id, tenantSchema);
    }

    public ZevraAgent updateAgent(String id, String tenantSchema, String name,
                                   String description, String persona, String goal,
                                   List<String> connectionKeys, int maxIterations,
                                   String status) {
        ZevraAgent existing = getAgent(id, tenantSchema);
        ZevraAgent updated  = new ZevraAgent(
                id, tenantSchema,
                name        != null ? name        : existing.name(),
                existing.slug(),
                description != null ? description : existing.description(),
                persona     != null ? persona     : existing.persona(),
                goal        != null ? goal        : existing.goal(),
                connectionKeys != null ? connectionKeys : existing.connectionKeys(),
                maxIterations > 0 ? maxIterations : existing.maxIterations(),
                status      != null ? status      : existing.status(),
                existing.createdBy(), existing.createdAt(), existing.updatedAt()
        );
        repository.updateAgent(updated);
        return getAgent(id, tenantSchema);
    }

    public void archiveAgent(String id, String tenantSchema) {
        repository.archiveAgent(id, tenantSchema);
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    public ZevraSession chat(String agentId, String tenantSchema, String message, String userEmail) {
        ZevraAgent agent = getAgent(agentId, tenantSchema);
        if (agent.connectionKeys().isEmpty())
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Agent has no connections configured — add at least one connection before chatting.");
        // Direct agent chat is autonomous execution — no conversational run exists, so the
        // runtime creates its own governance run (existingRunKey = null).
        return runner.run(agent, message, userEmail, null);
    }

    public List<ZevraSession> listSessions(String agentId, String tenantSchema) {
        return repository.findSessionsByAgent(agentId, tenantSchema);
    }

    public ZevraSession getSession(String sessionId, String tenantSchema) {
        return repository.findSessionById(sessionId, tenantSchema)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Session not found: " + sessionId));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String slugify(String input) {
        return input.toLowerCase()
                .replaceAll("[^a-z0-9 -]", "")
                .trim()
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-");
    }

    private String uniqueSlug(String base, String tenantSchema) {
        if (!repository.slugExists(base, tenantSchema)) return base;
        int n = 2;
        while (true) {
            String candidate = base + "-" + n++;
            if (!repository.slugExists(candidate, tenantSchema)) return candidate;
        }
    }
}
