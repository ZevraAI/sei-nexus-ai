package com.sei.nexus.agentrunner;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/zevra-agents")
public class ZevraAgentController {

    private final ZevraAgentService service;

    public ZevraAgentController(ZevraAgentService service) {
        this.service = service;
    }

    // ── Agent CRUD ────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<ZevraAgent>> list() {
        return ResponseEntity.ok(service.listAgents(tenantSchema()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ZevraAgent> get(@PathVariable String id) {
        return ResponseEntity.ok(service.getAgent(id, tenantSchema()));
    }

    @PostMapping
    public ResponseEntity<ZevraAgent> create(@RequestBody Map<String, Object> body) {
        ZevraAgent agent = service.createAgent(
                tenantSchema(),
                str(body, "name"),
                str(body, "description"),
                str(body, "persona"),
                str(body, "goal"),
                strList(body, "connectionKeys"),
                intVal(body, "maxIterations", 10),
                currentUser().email()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(agent);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ZevraAgent> update(@PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        ZevraAgent agent = service.updateAgent(
                id, tenantSchema(),
                str(body, "name"),
                str(body, "description"),
                str(body, "persona"),
                str(body, "goal"),
                strList(body, "connectionKeys"),
                intVal(body, "maxIterations", 0),
                str(body, "status")
        );
        return ResponseEntity.ok(agent);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable String id) {
        service.archiveAgent(id, tenantSchema());
        return ResponseEntity.noContent().build();
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/chat")
    public ResponseEntity<ZevraSession> chat(@PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        String message = str(body, "message");
        if (message == null || message.isBlank())
            return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(service.chat(id, tenantSchema(), message));
    }

    @GetMapping("/{id}/sessions")
    public ResponseEntity<List<ZevraSession>> sessions(@PathVariable String id) {
        return ResponseEntity.ok(service.listSessions(id, tenantSchema()));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<ZevraSession> session(@PathVariable String sessionId) {
        return ResponseEntity.ok(service.getSession(sessionId, tenantSchema()));
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

    @SuppressWarnings("unchecked")
    private List<String> strList(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof List<?> l ? (List<String>) l : null;
    }

    private int intVal(Map<String, Object> body, String key, int fallback) {
        Object v = body.get(key);
        if (v instanceof Number n) return n.intValue();
        return fallback;
    }
}
