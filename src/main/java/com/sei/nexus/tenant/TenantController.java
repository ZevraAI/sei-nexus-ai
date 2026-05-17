package com.sei.nexus.tenant;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for tenant lifecycle management.
 * Base path: /api/v1/admin/tenants
 *
 * <p>All endpoints require the caller to be authenticated with role ADMIN.
 * This is enforced by {@link com.sei.nexus.config.SecurityConfig} at the
 * HTTP security level AND by the {@link #requireAdmin()} guard in each method.
 *
 * <p>These endpoints operate on the {@code public} schema (tenant registry),
 * not on any individual tenant schema. They are intentionally separate from
 * the per-tenant API surface.
 */
@RestController
@RequestMapping("/admin/tenants")
public class TenantController {

    private final TenantRepository          tenantRepository;
    private final TenantProvisioningService provisioningService;

    public TenantController(TenantRepository tenantRepository,
                             TenantProvisioningService provisioningService) {
        this.tenantRepository    = tenantRepository;
        this.provisioningService = provisioningService;
    }

    /**
     * GET /admin/tenants
     * Lists all tenants regardless of status.
     */
    @GetMapping
    public ResponseEntity<List<Tenant>> listTenants() {
        requireAdmin();
        return ResponseEntity.ok(tenantRepository.findAll());
    }

    /**
     * GET /admin/tenants/{slug}
     * Returns a single tenant by its URL slug.
     */
    @GetMapping("/{slug}")
    public ResponseEntity<Tenant> getTenant(@PathVariable String slug) {
        requireAdmin();
        Tenant tenant = tenantRepository.findBySlug(slug)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Tenant not found: " + slug));
        return ResponseEntity.ok(tenant);
    }

    /**
     * POST /admin/tenants
     * Provisions a new tenant end-to-end:
     * creates the registry record, the PostgreSQL schema, runs migrations,
     * and seeds the first admin user.
     *
     * <p>Request body fields:
     * <pre>
     * {
     *   "slug":           "acme-corp",           // required — URL-safe, unique
     *   "name":           "Acme Corporation",    // required
     *   "plan":           "PROFESSIONAL",        // required — TRIAL|STANDARD|PROFESSIONAL|ENTERPRISE
     *   "contactEmail":   "admin@acme.com",      // required
     *   "maxUsers":       100,                   // optional — default 50
     *   "adminEmail":     "admin@acme.com",      // required — first admin account
     *   "adminPassword":  "SecurePass1!"         // required — min 8 chars
     * }
     * </pre>
     */
    @PostMapping
    public ResponseEntity<Tenant> provisionTenant(@RequestBody Map<String, Object> body) {
        requireAdmin();

        String slug          = requireString(body, "slug");
        String name          = requireString(body, "name");
        String plan          = requireString(body, "plan");
        String contactEmail  = requireString(body, "contactEmail");
        String adminEmail    = requireString(body, "adminEmail");
        String adminPassword = requireString(body, "adminPassword");
        int    maxUsers      = body.containsKey("maxUsers")
                               ? ((Number) body.get("maxUsers")).intValue() : 50;

        Tenant tenant = provisioningService.provision(
                slug, name, plan, contactEmail, maxUsers, adminEmail, adminPassword);

        return ResponseEntity.status(HttpStatus.CREATED).body(tenant);
    }

    /**
     * PATCH /admin/tenants/{slug}
     * Updates mutable tenant fields: plan, maxUsers, contactEmail, status.
     *
     * <p>Allowed status transitions:
     * ACTIVE → SUSPENDED, SUSPENDED → ACTIVE.
     * DEPROVISIONED is terminal and cannot be set here — use DELETE.
     */
    @PatchMapping("/{slug}")
    public ResponseEntity<Tenant> updateTenant(@PathVariable String slug,
                                                @RequestBody Map<String, Object> body) {
        requireAdmin();

        Tenant existing = tenantRepository.findBySlug(slug)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Tenant not found: " + slug));

        if (body.containsKey("status")) {
            String newStatus = (String) body.get("status");
            if (!List.of("ACTIVE", "SUSPENDED").contains(newStatus)) {
                throw new NexusException(HttpStatus.BAD_REQUEST,
                        "Status must be ACTIVE or SUSPENDED. Use DELETE to deprovision.");
            }
            tenantRepository.updateStatus(slug, newStatus);
        }

        if (body.containsKey("plan") || body.containsKey("maxUsers")) {
            String plan     = body.containsKey("plan")
                              ? (String) body.get("plan") : existing.plan();
            int    maxUsers = body.containsKey("maxUsers")
                              ? ((Number) body.get("maxUsers")).intValue() : existing.maxUsers();
            tenantRepository.updatePlan(slug, plan, maxUsers);
        }

        return ResponseEntity.ok(tenantRepository.findBySlug(slug).orElse(existing));
    }

    /**
     * DELETE /admin/tenants/{slug}
     * Permanently deprovisions a tenant: drops their PostgreSQL schema
     * and marks the registry record DEPROVISIONED. Irreversible.
     */
    @DeleteMapping("/{slug}")
    public ResponseEntity<Void> deprovisionTenant(@PathVariable String slug) {
        requireAdmin();
        provisioningService.deprovision(slug);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /admin/tenants/{slug}/suspend
     * Suspends a tenant (soft — data is retained, logins are blocked).
     */
    @PostMapping("/{slug}/suspend")
    public ResponseEntity<Void> suspendTenant(@PathVariable String slug) {
        requireAdmin();
        provisioningService.suspend(slug);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void requireAdmin() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof UserAccount user)) {
            throw new NexusException(HttpStatus.FORBIDDEN, "Authentication required");
        }
        if (!"ADMIN".equals(user.role())) {
            throw new NexusException(HttpStatus.FORBIDDEN,
                    "Admin role required for tenant management");
        }
        // Tenant management is only available from the platform workspace (public schema).
        // A tenant admin (e.g. acme-corp) has ADMIN role in their own schema but must
        // not be able to create, list, or deprovision other tenants.
        String currentSchema = com.sei.nexus.tenant.TenantContext.getSchema();
        if (!"public".equals(currentSchema)) {
            throw new NexusException(HttpStatus.FORBIDDEN,
                    "Tenant management is only available from the platform workspace");
        }
    }

    private String requireString(Map<String, Object> body, String field) {
        Object val = body.get(field);
        if (val == null || val.toString().isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Field '" + field + "' is required");
        }
        return val.toString().trim();
    }
}
