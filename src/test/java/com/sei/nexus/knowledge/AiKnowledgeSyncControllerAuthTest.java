package com.sei.nexus.knowledge;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADMIN-only authorization for the AI Knowledge / Vector Store sync surface. Hand-rolled fakes —
 * no Mockito, no Spring context, no DB, no OpenAI. The {@link ConceptKnowledgeSynchronizationService}
 * dependency is never actually invoked in the "must be rejected" cases, so a fully-null instance
 * is sufficient; the "must succeed" case uses a scripted subclass instead of the real service.
 */
class AiKnowledgeSyncControllerAuthTest {

    @AfterEach
    void clearSecurityContextAndTenantContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void authenticateAs(String email, String role) {
        UserAccount user = new UserAccount(email, email, "hash", role, "ACTIVE", null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
    }

    static class ScriptedSyncService extends ConceptKnowledgeSynchronizationService {
        boolean triggerAsyncCalled = false;
        List<String> triggeredForSchemas = new java.util.ArrayList<>();
        ScriptedSyncService() { super(null, null, null, null, null); }
        @Override public void triggerAsync() {
            triggerAsyncCalled = true;
            triggeredForSchemas.add(TenantContext.getSchema());
        }
        @Override public StatusReport status() {
            return new StatusReport(Status.IN_SYNC, 3, 0, List.of(), true, false,
                    null, null, null, "SUCCESS", null, 0);
        }
    }

    static class FakeTenantRepository extends TenantRepository {
        Map<String, Tenant> bySlug = new LinkedHashMap<>();
        FakeTenantRepository() { super(null); }
        void seed(Tenant tenant) { bySlug.put(tenant.slug(), tenant); }
        @Override public Optional<Tenant> findBySlug(String slug) { return Optional.ofNullable(bySlug.get(slug)); }
        @Override public List<Tenant> findAll() { return List.copyOf(bySlug.values()); }
    }

    private static Tenant tenant(String slug, String schema) {
        return new Tenant(UUID.randomUUID(), slug, slug + " Inc", schema, "STANDARD", "ACTIVE",
                "admin@" + slug + ".example", 50, Instant.now(), Instant.now(),
                "vs_" + slug, "READY", null, Instant.now());
    }

    // ── Platform/Zevra admin — cross-tenant endpoints ────────────────────────────────────────

    @Test
    void tenantAdminInOwnSchemaCannotAccessPlatformWideCrossTenantEndpoints() {
        authenticateAs("admin@acme.com", "ADMIN");
        TenantContext.set("tenant_acme"); // tenant admin's own schema, NOT public
        FakeTenantRepository tenants = new FakeTenantRepository();
        tenants.seed(tenant("acme", "tenant_acme"));
        AiKnowledgeSyncController controller = new AiKnowledgeSyncController(new ScriptedSyncService(), tenants);

        assertThrows(NexusException.class, controller::allTenantsStatus,
                "a tenant admin (even with role ADMIN) must not reach the cross-tenant view from their own schema");
        assertThrows(NexusException.class, () -> controller.syncForTenant("acme"),
                "a tenant admin must not be able to force-sync ANY tenant, including their own, via the platform API");
    }

    @Test
    void nonAdminCannotAccessPlatformWideCrossTenantEndpointsEvenFromPublicSchema() {
        authenticateAs("analyst@example.com", "ANALYST");
        TenantContext.set("public");
        FakeTenantRepository tenants = new FakeTenantRepository();
        AiKnowledgeSyncController controller = new AiKnowledgeSyncController(new ScriptedSyncService(), tenants);

        assertThrows(NexusException.class, controller::allTenantsStatus);
    }

    @Test
    void platformAdminCanViewMultipleTenantsStatus() {
        authenticateAs("platform@zevra.example", "ADMIN");
        TenantContext.set("public");
        FakeTenantRepository tenants = new FakeTenantRepository();
        tenants.seed(tenant("acme", "tenant_acme"));
        tenants.seed(tenant("contoso", "tenant_contoso"));
        AiKnowledgeSyncController controller = new AiKnowledgeSyncController(new ScriptedSyncService(), tenants);

        ResponseEntity<List<Map<String, Object>>> response = controller.allTenantsStatus();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size(), "both seeded tenants must appear");
        assertEquals("public", TenantContext.getSchema(),
                "the platform admin's own TenantContext must be restored after querying each tenant");
    }

    @Test
    void platformAdminForceSyncRunsUnderTheTargetTenantsOwnContextNotThePlatformAdmins() {
        authenticateAs("platform@zevra.example", "ADMIN");
        TenantContext.set("public");
        FakeTenantRepository tenants = new FakeTenantRepository();
        tenants.seed(tenant("contoso", "tenant_contoso"));
        ScriptedSyncService service = new ScriptedSyncService();
        AiKnowledgeSyncController controller = new AiKnowledgeSyncController(service, tenants);

        ResponseEntity<Map<String, Object>> response = controller.syncForTenant("contoso");

        assertEquals(202, response.getStatusCode().value());
        assertEquals(List.of("tenant_contoso"), service.triggeredForSchemas,
                "the sync must run under the TARGET tenant's schema, never the calling platform admin's own");
        assertEquals("public", TenantContext.getSchema(),
                "the platform admin's own TenantContext must be restored after triggering sync for the target tenant");
    }

    @Test
    void platformAdminForceSyncRejectsAnUnknownTenantSlug() {
        authenticateAs("platform@zevra.example", "ADMIN");
        TenantContext.set("public");
        AiKnowledgeSyncController controller = new AiKnowledgeSyncController(new ScriptedSyncService(), new FakeTenantRepository());

        assertThrows(NexusException.class, () -> controller.syncForTenant("does-not-exist"));
    }

    @Test
    void nonAdminCannotViewStatus() {
        authenticateAs("analyst@acme.com", "ANALYST");
        AiKnowledgeSyncController controller = new AiKnowledgeSyncController(new ScriptedSyncService(), null);

        NexusException ex = assertThrows(NexusException.class, controller::status);
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    void nonAdminCannotTriggerSyncNow() {
        authenticateAs("analyst@acme.com", "ANALYST");
        ScriptedSyncService service = new ScriptedSyncService();
        AiKnowledgeSyncController controller = new AiKnowledgeSyncController(service, null);

        assertThrows(NexusException.class, controller::syncNow);
        assertFalse(service.triggerAsyncCalled, "a rejected request must never reach the sync service");
    }

    @Test
    void unauthenticatedCallerCannotViewStatusOrSync() {
        SecurityContextHolder.clearContext();
        AiKnowledgeSyncController controller = new AiKnowledgeSyncController(new ScriptedSyncService(), null);

        assertThrows(NexusException.class, controller::status);
        assertThrows(NexusException.class, controller::syncNow);
    }

    @Test
    void tenantAdminCanViewStatus() {
        authenticateAs("admin@acme.com", "ADMIN");
        AiKnowledgeSyncController controller = new AiKnowledgeSyncController(new ScriptedSyncService(), null);

        ResponseEntity<?> response = controller.status();
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void tenantAdminCanTriggerSyncNowAndItReturnsImmediatelyWithSyncingStatus() {
        authenticateAs("admin@acme.com", "ADMIN");
        ScriptedSyncService service = new ScriptedSyncService();
        AiKnowledgeSyncController controller = new AiKnowledgeSyncController(service, null);

        ResponseEntity<?> response = controller.syncNow();
        assertEquals(202, response.getStatusCode().value(), "the endpoint returns immediately, not after the sync completes");
        assertTrue(service.triggerAsyncCalled);
    }
}
