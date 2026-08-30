package com.sei.nexus.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Persistent Tenant Knowledge, Phase 1 — {@link TenantProvisioningService#provisionAiKnowledgeStore}.
 *
 * <p>Exercises only the provisioning orchestration logic (idempotency, failure handling,
 * per-tenant isolation) — never touches a real database or a real OpenAI endpoint. Same
 * hand-rolled-fakes convention as {@code AzureOpenAiClientThrottleTest}/{@code
 * AzureOpenAiClientMetricsTest}: {@link TenantRepository} and {@link AzureOpenAiClient} are
 * subclassed with their I/O-touching methods overridden, mirroring the existing {@code
 * sendHttp}-override seam. {@code rawDataSource} is passed {@code null} because
 * provisionAiKnowledgeStore never uses it — schema/Flyway/Supabase logic lives in a separate
 * code path ({@link TenantProvisioningService#provision}) not exercised here.
 */
class TenantProvisioningServiceAiKnowledgeTest {

    /** In-memory stand-in for public.nexus_tenant — no JDBC. */
    static class FakeTenantRepository extends TenantRepository {
        final Map<String, Tenant> bySlug = new LinkedHashMap<>();

        FakeTenantRepository() { super(null); }

        void seed(Tenant tenant) { bySlug.put(tenant.slug(), tenant); }

        @Override
        public Optional<Tenant> findBySlug(String slug) {
            return Optional.ofNullable(bySlug.get(slug));
        }

        @Override
        public void updateAiKnowledgeReady(String slug, String vectorStoreId) {
            Tenant t = bySlug.get(slug);
            bySlug.put(slug, new Tenant(t.tenantId(), t.slug(), t.name(), t.schemaName(), t.plan(),
                    t.status(), t.contactEmail(), t.maxUsers(), t.createdAt(), Instant.now(),
                    vectorStoreId, "READY", null, Instant.now()));
        }

        @Override
        public void updateAiKnowledgeFailed(String slug, String errorMessage) {
            Tenant t = bySlug.get(slug);
            bySlug.put(slug, new Tenant(t.tenantId(), t.slug(), t.name(), t.schemaName(), t.plan(),
                    t.status(), t.contactEmail(), t.maxUsers(), t.createdAt(), Instant.now(),
                    t.aiKnowledgeVectorStoreId(), "FAILED", errorMessage, t.aiKnowledgeProvisionedAt()));
        }
    }

    /** Scripted OpenAI client — never sends real HTTP. */
    static class FakeAiClient extends AzureOpenAiClient {
        final AtomicInteger createCalls = new AtomicInteger(0);
        String returnedId;
        RuntimeException toThrow;
        String lastRequestedName;

        FakeAiClient() { super(new ObjectMapper(), null); }

        @Override
        public String createVectorStore(String name) {
            createCalls.incrementAndGet();
            lastRequestedName = name;
            if (toThrow != null) throw toThrow;
            return returnedId;
        }
    }

    private Tenant newTenant(String slug) {
        return new Tenant(UUID.randomUUID(), slug, slug + " Inc", "tenant_" + slug.replace('-', '_'),
                "STANDARD", "ACTIVE", "admin@" + slug + ".example", 50,
                Instant.now(), Instant.now(), null, null, null, null);
    }

    private TenantProvisioningService serviceFor(FakeTenantRepository repo, FakeAiClient aiClient) {
        return new TenantProvisioningService(repo, null, new ObjectMapper(), aiClient);
    }

    // ── new tenant creates exactly one store / success persists ID ──────────────────────────

    @Test
    void newTenantProvisioningCreatesExactlyOneStoreAndPersistsItsId() {
        FakeTenantRepository repo = new FakeTenantRepository();
        repo.seed(newTenant("acme"));
        FakeAiClient aiClient = new FakeAiClient();
        aiClient.returnedId = "vs_acme_001";

        serviceFor(repo, aiClient).provisionAiKnowledgeStore("acme");

        assertEquals(1, aiClient.createCalls.get());
        Tenant updated = repo.findBySlug("acme").orElseThrow();
        assertEquals("vs_acme_001", updated.aiKnowledgeVectorStoreId());
        assertEquals("READY", updated.aiKnowledgeStatus());
        assertNull(updated.aiKnowledgeError());
        assertNotNull(updated.aiKnowledgeProvisionedAt());
    }

    @Test
    void storeNameIsDeterministicAndDerivedFromSchemaNotFromNameOrEmail() {
        FakeTenantRepository repo = new FakeTenantRepository();
        repo.seed(newTenant("acme"));
        FakeAiClient aiClient = new FakeAiClient();
        aiClient.returnedId = "vs_acme_001";

        serviceFor(repo, aiClient).provisionAiKnowledgeStore("acme");

        assertEquals("zevra-tenant-tenant_acme", aiClient.lastRequestedName);
    }

    // ── retry with existing ID is a no-op ────────────────────────────────────────────────────

    @Test
    void retryWhenAlreadyProvisionedIsANoOp() {
        FakeTenantRepository repo = new FakeTenantRepository();
        Tenant already = newTenant("acme");
        repo.seed(new Tenant(already.tenantId(), already.slug(), already.name(), already.schemaName(),
                already.plan(), already.status(), already.contactEmail(), already.maxUsers(),
                already.createdAt(), already.updatedAt(),
                "vs_existing", "READY", null, Instant.now()));
        FakeAiClient aiClient = new FakeAiClient();
        aiClient.returnedId = "vs_should_never_be_used";

        serviceFor(repo, aiClient).provisionAiKnowledgeStore("acme");

        assertEquals(0, aiClient.createCalls.get(), "already-provisioned tenant must never call OpenAI again");
        assertEquals("vs_existing", repo.findBySlug("acme").orElseThrow().aiKnowledgeVectorStoreId(),
                "the original id must be untouched");
    }

    // ── OpenAI failure yields an observable failure state ────────────────────────────────────

    @Test
    void openAiFailureIsRecordedAsAnObservableFailureRatherThanThrowingOrSilentlyDoingNothing() {
        FakeTenantRepository repo = new FakeTenantRepository();
        repo.seed(newTenant("acme"));
        FakeAiClient aiClient = new FakeAiClient();
        aiClient.toThrow = new RuntimeException("OpenAI unreachable");

        assertDoesNotThrow(() -> serviceFor(repo, aiClient).provisionAiKnowledgeStore("acme"),
                "provisioning failure must not fail tenant creation/onboarding — it's a non-fatal step");

        Tenant updated = repo.findBySlug("acme").orElseThrow();
        assertNull(updated.aiKnowledgeVectorStoreId());
        assertEquals("FAILED", updated.aiKnowledgeStatus());
        assertEquals("OpenAI unreachable", updated.aiKnowledgeError());
    }

    // ── retry-after-failure can recover ──────────────────────────────────────────────────────

    @Test
    void retryAfterFailureCanRecover() {
        FakeTenantRepository repo = new FakeTenantRepository();
        repo.seed(newTenant("acme"));
        FakeAiClient failingClient = new FakeAiClient();
        failingClient.toThrow = new RuntimeException("transient outage");
        TenantProvisioningService service1 = serviceFor(repo, failingClient);
        service1.provisionAiKnowledgeStore("acme");
        assertEquals("FAILED", repo.findBySlug("acme").orElseThrow().aiKnowledgeStatus());

        FakeAiClient recoveredClient = new FakeAiClient();
        recoveredClient.returnedId = "vs_recovered";
        TenantProvisioningService service2 = serviceFor(repo, recoveredClient);
        service2.provisionAiKnowledgeStore("acme");

        Tenant recovered = repo.findBySlug("acme").orElseThrow();
        assertEquals("READY", recovered.aiKnowledgeStatus());
        assertEquals("vs_recovered", recovered.aiKnowledgeVectorStoreId());
        assertNull(recovered.aiKnowledgeError(), "a successful retry must clear the prior failure reason");
    }

    // ── existing tenant with null ID stays compatible ────────────────────────────────────────

    @Test
    void existingTenantWithNullVectorStoreIdIsTreatedAsNotYetProvisionedNotAsAnError() {
        FakeTenantRepository repo = new FakeTenantRepository();
        // A pre-Phase-1 tenant row: all ai_knowledge_* columns are null (Phase 1 never backfills).
        repo.seed(newTenant("legacy-tenant"));
        Tenant beforeProvisioning = repo.findBySlug("legacy-tenant").orElseThrow();

        assertNull(beforeProvisioning.aiKnowledgeVectorStoreId());
        assertNull(beforeProvisioning.aiKnowledgeStatus());
        assertDoesNotThrow(() -> beforeProvisioning.aiKnowledgeError());
        assertDoesNotThrow(() -> beforeProvisioning.aiKnowledgeProvisionedAt());

        // Provisioning still works normally for it — null is "not yet provisioned", not corrupt data.
        FakeAiClient aiClient = new FakeAiClient();
        aiClient.returnedId = "vs_legacy";
        serviceFor(repo, aiClient).provisionAiKnowledgeStore("legacy-tenant");
        assertEquals("vs_legacy", repo.findBySlug("legacy-tenant").orElseThrow().aiKnowledgeVectorStoreId());
    }

    // ── Tenant A cannot use Tenant B's store association ─────────────────────────────────────

    @Test
    void provisioningOneTenantNeverAffectsAnotherTenantsRow() {
        FakeTenantRepository repo = new FakeTenantRepository();
        repo.seed(newTenant("tenant-a"));
        repo.seed(newTenant("tenant-b"));
        FakeAiClient aiClient = new FakeAiClient();
        aiClient.returnedId = "vs_tenant_a_only";

        serviceFor(repo, aiClient).provisionAiKnowledgeStore("tenant-a");

        Tenant a = repo.findBySlug("tenant-a").orElseThrow();
        Tenant b = repo.findBySlug("tenant-b").orElseThrow();
        assertEquals("vs_tenant_a_only", a.aiKnowledgeVectorStoreId());
        assertNull(b.aiKnowledgeVectorStoreId(), "tenant B must never see tenant A's vector store id");
        assertNull(b.aiKnowledgeStatus());
    }
}
