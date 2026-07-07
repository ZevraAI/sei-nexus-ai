package com.sei.nexus.tenant;

import com.sei.nexus.auth.ImpersonationFilter;
import com.sei.nexus.auth.TenantDomain;
import com.sei.nexus.auth.TenantDomainRepository;
import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.governance.AuditEvent;
import com.sei.nexus.governance.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
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

    private static final Logger log = LoggerFactory.getLogger(TenantController.class);

    private final TenantRepository          tenantRepository;
    private final TenantProvisioningService provisioningService;
    private final TenantDomainRepository    tenantDomainRepository;
    private final AuditEventRepository      auditEventRepository;
    private final SecureRandom              secureRandom = new SecureRandom();

    public TenantController(TenantRepository tenantRepository,
                             TenantProvisioningService provisioningService,
                             TenantDomainRepository tenantDomainRepository,
                             AuditEventRepository auditEventRepository) {
        this.tenantRepository       = tenantRepository;
        this.provisioningService    = provisioningService;
        this.tenantDomainRepository = tenantDomainRepository;
        this.auditEventRepository   = auditEventRepository;
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

    /**
     * POST /admin/tenants/{slug}/reinvite
     * Resends the Supabase invite email to a user in this tenant.
     * Useful when the original invite email was lost or never delivered.
     *
     * <p>Body: { "email": "admin@acme.com" }
     */
    @PostMapping("/{slug}/reinvite")
    public ResponseEntity<?> reinviteTenantUser(@PathVariable String slug,
                                                 @RequestBody Map<String, Object> body) {
        requireAdmin();
        Tenant tenant = tenantRepository.findBySlug(slug)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Tenant not found: " + slug));
        String email = requireString(body, "email");
        java.util.Optional<String> recoveryLink = provisioningService.reinvite(tenant, email);

        if (recoveryLink.isPresent()) {
            // User already existed in Supabase Auth — return a one-time recovery link
            // for the platform admin to share directly with the user.
            return ResponseEntity.ok(Map.of(
                    "mode",    "recovery_link",
                    "message", "User already registered. Share the recovery link below directly with " + email + ".",
                    "link",    recoveryLink.get()));
        }
        return ResponseEntity.ok(Map.of(
                "mode",    "invite_sent",
                "message", "Invite email sent to " + email));
    }

    // ── Domain management ─────────────────────────────────────────────────────

    /** GET /admin/tenants/{slug}/domains — list SSO domains for a tenant. */
    @GetMapping("/{slug}/domains")
    public ResponseEntity<?> listDomains(@PathVariable String slug) {
        requireAdmin();
        Tenant tenant = tenantRepository.findBySlug(slug)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Tenant not found: " + slug));
        return ResponseEntity.ok(tenantDomainRepository.findByTenantSchema(tenant.schemaName()));
    }

    /** POST /admin/tenants/{slug}/domains — register an SSO domain. Body: { "domain": "acme.com", "default_role": "ANALYST" } */
    @PostMapping("/{slug}/domains")
    public ResponseEntity<?> addDomain(@PathVariable String slug,
                                        @RequestBody Map<String, Object> body) {
        requireAdmin();
        Tenant tenant = tenantRepository.findBySlug(slug)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Tenant not found: " + slug));

        String domain      = requireString(body, "domain").toLowerCase().replaceAll("^@", "");
        String defaultRole = body.containsKey("default_role")
                ? body.get("default_role").toString() : "ANALYST";

        if (!List.of("ADMIN", "ANALYST", "DOMAIN_OWNER").contains(defaultRole)) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Invalid default_role: " + defaultRole);
        }

        String callerEmail = null;
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof UserAccount ua) callerEmail = ua.email();

        tenantDomainRepository.create(new TenantDomain(
                domain, tenant.schemaName(), defaultRole, callerEmail, null));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("domain", domain, "tenant_schema", tenant.schemaName(),
                             "default_role", defaultRole));
    }

    /** DELETE /admin/tenants/{slug}/domains/{domain} — remove an SSO domain. */
    @DeleteMapping("/{slug}/domains/{domain}")
    public ResponseEntity<Void> removeDomain(@PathVariable String slug,
                                              @PathVariable String domain) {
        requireAdmin();
        Tenant tenant = tenantRepository.findBySlug(slug)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Tenant not found: " + slug));
        tenantDomainRepository.delete(domain, tenant.schemaName());
        return ResponseEntity.noContent().build();
    }

    // ── Impersonation ─────────────────────────────────────────────────────────

    /**
     * POST /admin/tenants/{slug}/impersonate
     * Issues a 30-minute impersonation token scoped to the target tenant.
     * Platform admin only (schema=public, role=ADMIN).
     */
    @PostMapping("/{slug}/impersonate")
    public ResponseEntity<Map<String, Object>> startImpersonation(
            @PathVariable String slug) {
        requireAdmin();

        Tenant tenant = tenantRepository.findBySlug(slug)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Tenant not found: " + slug));
        if ("DEPROVISIONED".equals(tenant.status())) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Cannot impersonate a deprovisioned tenant");
        }

        UserAccount caller = (UserAccount) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        // Generate cryptographically random 32-byte token
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawToken = HexFormat.of().formatHex(bytes);

        String tokenHash;
        try {
            tokenHash = ImpersonationFilter.sha256Hex(rawToken);
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Token generation failed");
        }

        Instant expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES);
        tenantRepository.writeSessionIndex(
                tokenHash, tenant.schemaName(), caller.email(), expiresAt);

        auditEventRepository.save(new AuditEvent(
                Keys.uniqueKey("audit"), "IMPERSONATION_START",
                caller.email(), "ADMIN", null, null,
                new String[]{slug}, new String[0], new String[0],
                new String[0], new String[0], new String[0],
                null, null, null, null, null, null, Instant.now()));

        log.info("Platform admin '{}' started impersonation of tenant '{}'",
                caller.email(), slug);

        return ResponseEntity.ok(Map.of(
                "token",      rawToken,
                "tenantSlug", slug,
                "tenantName", tenant.name(),
                "expiresAt",  expiresAt.toString()));
    }

    /**
     * DELETE /admin/tenants/impersonate
     * Revokes an active impersonation session.
     * Must be called without the X-Nexus-Impersonate header (i.e., as platform admin).
     */
    @DeleteMapping("/impersonate")
    public ResponseEntity<Void> exitImpersonation(
            @RequestBody Map<String, Object> body) {
        requireAdmin();
        String rawToken = requireString(body, "token");

        String tokenHash;
        try {
            tokenHash = ImpersonationFilter.sha256Hex(rawToken);
        } catch (Exception e) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Invalid token");
        }

        tenantRepository.deleteSessionIndex(tokenHash);

        UserAccount caller = (UserAccount) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        auditEventRepository.save(new AuditEvent(
                Keys.uniqueKey("audit"), "IMPERSONATION_END",
                caller.email(), "ADMIN", null, null,
                new String[0], new String[0], new String[0],
                new String[0], new String[0], new String[0],
                null, null, null, null, null, null, Instant.now()));

        log.info("Platform admin '{}' exited impersonation", caller.email());
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
