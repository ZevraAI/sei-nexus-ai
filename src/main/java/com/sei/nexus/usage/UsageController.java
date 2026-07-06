package com.sei.nexus.usage;

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
@RequestMapping("/usage")
public class UsageController {

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    /**
     * GET /usage/summary?period=2026-05
     * Tenant admin view — token counts only, no cost figures.
     */
    @GetMapping("/summary")
    public ResponseEntity<?> tenantSummary(
            @RequestParam(required = false) String period) {
        requireAuthenticated();
        String schema = TenantContext.getSchema();
        Map<String, Object> summary = usageService.tenantSummary(schema, period);
        // Strip cost from totals — tenant admins see volume, not price
        stripCost(summary);
        return ResponseEntity.ok(summary);
    }

    /**
     * GET /usage/admin?period=2026-05
     * Platform admin only — cross-tenant view with cost figures.
     */
    @GetMapping("/admin")
    public ResponseEntity<?> platformSummary(
            @RequestParam(required = false) String period) {
        requirePlatformAdmin();
        return ResponseEntity.ok(usageService.platformSummary(period));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UserAccount currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserAccount ua)) {
            throw new NexusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return ua;
    }

    private void requireAuthenticated() {
        currentUser(); // throws if not auth'd
    }

    private void requirePlatformAdmin() {
        UserAccount user = currentUser();
        if (!"ADMIN".equals(user.role())) {
            throw new NexusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
        String schema = TenantContext.getSchema();
        if (!"public".equals(schema)) {
            throw new NexusException(HttpStatus.FORBIDDEN,
                    "Platform-wide usage is only available from the platform workspace");
        }
    }

    @SuppressWarnings("unchecked")
    private void stripCost(Map<String, Object> summary) {
        Object totals = summary.get("totals");
        if (totals instanceof Map<?,?> m) ((Map<String, Object>) m).remove("cost_usd");
    }
}
