package com.sei.nexus.knowledge;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * "AI Knowledge" / Vector Store synchronization surface — tenant-ADMIN endpoints (own tenant
 * only, resolved strictly from {@link TenantContext}) plus a platform-admin cross-tenant
 * extension of the same feature (never a parallel synchronization subsystem).
 *
 * <p>Tenant-admin endpoints: the tenant is always the one the authenticated caller's own {@link
 * TenantContext} already resolves to (established upstream by the existing auth filter chain,
 * exactly like every other tenant-scoped controller in this codebase) — there is no request
 * parameter for tenant id or Vector Store id anywhere in them, so cross-tenant synchronization is
 * not expressible through those two endpoints regardless of what a caller sends.
 *
 * <p>Platform-admin endpoints: guarded by {@link #requirePlatformAdmin()} (role {@code ADMIN} AND
 * the caller's own {@code TenantContext} is {@code public} — the exact same disambiguation {@code
 * TenantController#requireAdmin()}/{@code UsageController#requirePlatformAdmin()} already use, no
 * new authorization mechanism). The target tenant is resolved from its {@code slug} via {@link
 * TenantRepository} — never a Vector Store id supplied by the client — and the tenant-scoped work
 * itself runs under {@code TenantContext.set(target.schemaName())} in a {@code try/finally}, the
 * exact same pattern {@link ConceptKnowledgeSynchronizationService#reconcileAllTenants()} already
 * uses to act "as" a specific tenant from the scheduler's own thread.
 *
 * <p>Manual "Sync Now" (either tenant-admin or platform-admin) and every automatic trigger (Pack
 * apply/remove, nightly reconciliation) all call the exact same {@link
 * ConceptKnowledgeSynchronizationService#triggerAsync()}/{@code synchronize()} — there is exactly
 * one synchronization implementation.
 */
@RestController
@RequestMapping("/admin/knowledge/vector-store")
public class AiKnowledgeSyncController {

    private final ConceptKnowledgeSynchronizationService syncService;
    private final TenantRepository tenantRepository;

    public AiKnowledgeSyncController(ConceptKnowledgeSynchronizationService syncService,
                                      TenantRepository tenantRepository) {
        this.syncService = syncService;
        this.tenantRepository = tenantRepository;
    }

    // ── Tenant ADMIN — own tenant only ───────────────────────────────────────

    /** GET /admin/knowledge/vector-store/status */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        requireTenantAdmin();
        ConceptKnowledgeSynchronizationService.StatusReport report = syncService.status();
        return ResponseEntity.ok(toResponse(report));
    }

    /**
     * POST /admin/knowledge/vector-store/sync
     *
     * <p>Returns immediately with {@code SYNCING} — the actual OpenAI work happens on {@code
     * conceptSyncExecutor}, never inside this HTTP request (per this feature's own UX
     * requirement: the caller polls {@link #status()} for the eventual outcome).
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncNow() {
        requireTenantAdmin();
        syncService.triggerAsync();
        return ResponseEntity.accepted().body(Map.of("status", "SYNCING"));
    }

    // ── Platform/Zevra ADMIN — cross-tenant ──────────────────────────────────

    /** GET /admin/knowledge/vector-store/tenants — every tenant's sync health, one row each. */
    @GetMapping("/tenants")
    public ResponseEntity<List<Map<String, Object>>> allTenantsStatus() {
        requirePlatformAdmin();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Tenant tenant : tenantRepository.findAll()) {
            if (!"ACTIVE".equals(tenant.status()) || "public".equals(tenant.schemaName())) continue;
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("tenantSlug", tenant.slug());
            row.put("tenantName", tenant.name());
            row.putAll(toResponse(statusForTenant(tenant)));
            rows.add(row);
        }
        return ResponseEntity.ok(rows);
    }

    /** GET /admin/knowledge/vector-store/tenants/{tenantSlug}/pending — one tenant's pending changes, in detail. */
    @GetMapping("/tenants/{tenantSlug}/pending")
    public ResponseEntity<Map<String, Object>> pendingForTenant(@PathVariable String tenantSlug) {
        requirePlatformAdmin();
        Tenant tenant = resolveTargetTenant(tenantSlug);
        return ResponseEntity.ok(toResponse(statusForTenant(tenant)));
    }

    /**
     * POST /admin/knowledge/vector-store/tenants/{tenantSlug}/sync — force-sync one tenant,
     * selected by the platform admin (never taken from a client-supplied Vector Store id).
     */
    @PostMapping("/tenants/{tenantSlug}/sync")
    public ResponseEntity<Map<String, Object>> syncForTenant(@PathVariable String tenantSlug) {
        requirePlatformAdmin();
        Tenant tenant = resolveTargetTenant(tenantSlug);
        TenantContext.set(tenant.schemaName());
        try {
            syncService.triggerAsync();
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.accepted().body(Map.of("status", "SYNCING", "tenantSlug", tenantSlug));
    }

    private Tenant resolveTargetTenant(String tenantSlug) {
        return tenantRepository.findBySlug(tenantSlug)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantSlug));
    }

    /** Runs {@link ConceptKnowledgeSynchronizationService#status()} under the target tenant's own
     *  {@link TenantContext} — never the platform admin's — mirroring {@code reconcileAllTenants()}'s
     *  established set/try/finally-clear discipline for acting "as" a specific tenant. */
    private ConceptKnowledgeSynchronizationService.StatusReport statusForTenant(Tenant tenant) {
        TenantContext.set(tenant.schemaName());
        try {
            return syncService.status();
        } finally {
            TenantContext.clear();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toResponse(ConceptKnowledgeSynchronizationService.StatusReport r) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", r.status().name());
        body.put("lastSuccessfulSync", r.lastSuccessfulSync());
        body.put("totalConcepts", r.totalConcepts());
        body.put("pendingChangeCount", r.pendingChangeCount());
        body.put("pendingChanges", r.pendingChanges().stream()
                .map(c -> Map.of("type", c.type(), "name", c.name(),
                        "changedAt", c.changedAt() == null ? "" : c.changedAt()))
                .toList());
        body.put("vectorStoreConnected", r.vectorStoreConnected());
        body.put("syncInProgress", r.syncInProgress());
        body.put("lastSyncStartedAt", r.lastSyncStartedAt());
        body.put("lastSyncCompletedAt", r.lastSyncCompletedAt());
        body.put("lastSyncStatus", r.lastSyncStatus());
        body.put("lastSyncError", r.lastSyncError());
        body.put("unclassifiedPromotedLearnings", r.unclassifiedPromotedLearnings());
        return body;
    }

    private UserAccount currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserAccount ua)) {
            throw new NexusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return ua;
    }

    private void requireTenantAdmin() {
        UserAccount user = currentUser();
        if (!"ADMIN".equals(user.role())) {
            throw new NexusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }

    /** Same disambiguation {@code TenantController#requireAdmin()}/{@code
     *  UsageController#requirePlatformAdmin()} already use — role ADMIN AND the caller's own
     *  session resolves to the public/platform schema, not a tenant schema. */
    private void requirePlatformAdmin() {
        UserAccount user = currentUser();
        if (!"ADMIN".equals(user.role())) {
            throw new NexusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
        if (!"public".equals(TenantContext.getSchema())) {
            throw new NexusException(HttpStatus.FORBIDDEN,
                    "Cross-tenant AI Knowledge management is only available from the platform workspace");
        }
    }
}
