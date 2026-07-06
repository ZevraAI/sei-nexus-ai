package com.sei.nexus.integration;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/templates")
public class IntegrationTemplateController {

    private final IntegrationTemplateService templateService;

    public IntegrationTemplateController(IntegrationTemplateService templateService) {
        this.templateService = templateService;
    }

    /** GET /templates — list all available integration templates. */
    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(templateService.listTemplates());
    }

    /** GET /templates/{id} — full template detail including suggested questions and agent definition. */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return ResponseEntity.ok(templateService.getTemplate(id));
    }

    /**
     * POST /templates/{id}/validate
     * Body: { "connection_key": "nexus-local" }
     * Returns which entities' tables were found in the connection.
     */
    @PostMapping("/{id}/validate")
    public ResponseEntity<?> validate(@PathVariable String id,
                                       @RequestBody Map<String, String> body) {
        String connKey = body.get("connection_key");
        if (connKey == null || connKey.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "connection_key is required");
        }
        return ResponseEntity.ok(templateService.validateConnection(id, connKey));
    }

    /**
     * POST /templates/{id}/apply
     * Body: { "domain_key": "servicenow-itsm", "connection_key": "nexus-local" }
     * Creates domain, entities, vocabulary, and pre-built agent.
     */
    @PostMapping("/{id}/apply")
    public ResponseEntity<?> apply(@PathVariable String id,
                                    @RequestBody Map<String, String> body) {
        String domainKey  = body.getOrDefault("domain_key", id.replace("-v1", "").replace("-", "_"));
        String connKey    = body.get("connection_key");
        String appliedBy  = currentUserEmail();

        var result = templateService.applyTemplate(id, domainKey, connKey, appliedBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /** GET /templates/applied — list templates already applied to this tenant. */
    @GetMapping("/applied")
    public ResponseEntity<?> applied() {
        return ResponseEntity.ok(templateService.listApplied());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String currentUserEmail() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserAccount ua) return ua.email();
        } catch (Exception ignored) {}
        return "system";
    }
}
