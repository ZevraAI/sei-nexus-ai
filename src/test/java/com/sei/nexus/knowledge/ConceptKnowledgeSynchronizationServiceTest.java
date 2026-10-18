package com.sei.nexus.knowledge;

import com.sei.nexus.knowledge.ConceptKnowledgeMaterializationServiceTest.FakeEmbeddingClient;
import com.sei.nexus.knowledge.ConceptKnowledgeMaterializationServiceTest.FakeEmbeddingRepository;
import com.sei.nexus.knowledge.ConceptKnowledgeMaterializationServiceTest.FakeLearnedMappingRepository;
import com.sei.nexus.knowledge.ConceptKnowledgeMaterializationServiceTest.FakePackRepository;
import com.sei.nexus.knowledge.ConceptKnowledgeMaterializationServiceTest.FakeSemanticService;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.sei.nexus.knowledge.ConceptKnowledgeMaterializationServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConceptKnowledgeSynchronizationService}: the one convergence of the concept index to what
 * Postgres says it should be — create / update / delete / unchanged, the status report, and the
 * all-tenants reconciliation. Hand-rolled fakes; no Mockito, no DB, no live OpenAI.
 */
class ConceptKnowledgeSynchronizationServiceTest {

    static class FakeTenantRepository extends TenantRepository {
        final List<Tenant> tenants = new ArrayList<>();
        FakeTenantRepository() { super(null); }
        @Override public List<Tenant> findAll() { return tenants; }
    }

    private FakePackRepository packRepo;
    private FakeSemanticService semantic;
    private FakeEmbeddingClient ai;
    private FakeLearnedMappingRepository learned;
    private FakeEmbeddingRepository repo;
    private FakeTenantRepository tenants;
    private ConceptKnowledgeSynchronizationService sync;

    @BeforeEach
    void setUp() {
        packRepo = packRepoWithTwoConcepts();
        semantic = semanticServiceUsing("conn-1", "purchase-order", "sales-transaction");
        ai = new FakeEmbeddingClient();
        learned = new FakeLearnedMappingRepository();
        repo = new FakeEmbeddingRepository();
        tenants = new FakeTenantRepository();
        var materialization = new ConceptKnowledgeMaterializationService(packRepo, semantic, ai, learned, repo);
        sync = new ConceptKnowledgeSynchronizationService(tenants, materialization, repo);
        TenantContext.set("tenant_acme");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private static Tenant tenant(String slug, String schema, String status) {
        return new Tenant(UUID.randomUUID(), slug, slug + " Inc", schema, "STANDARD", status,
                "admin@" + slug + ".example", 50, Instant.now(), Instant.now());
    }

    // ── synchronize ───────────────────────────────────────────────────────────────────────────

    @Test
    void theFirstSyncEmbedsAndStoresEveryConcept() {
        var result = sync.synchronize();

        assertEquals(ConceptKnowledgeSynchronizationService.Status.IN_SYNC, result.status());
        assertEquals(2, result.createdCount());
        assertEquals(2, repo.rows.size());
        assertEquals(2, ai.embeddedTexts.size());
    }

    @Test
    void aSecondSyncOfUnchangedConceptsCostsNoEmbeddingCall() {
        sync.synchronize();
        ai.embeddedTexts.clear();

        var result = sync.synchronize();

        assertEquals(2, result.unchangedCount());
        assertEquals(0, result.createdCount() + result.updatedCount() + result.deletedCount());
        assertTrue(ai.embeddedTexts.isEmpty(), "unchanged concepts are not re-embedded");
    }

    @Test
    void aPromotedLearningReEmbedsOnlyTheConceptItBelongsTo() {
        sync.synchronize();
        ai.embeddedTexts.clear();
        learned.seed("purchase-order", learnedMapping("k", "late po", "expected < now()", 0.9));

        var result = sync.synchronize();

        assertEquals(1, result.updatedCount());
        assertEquals(1, result.unchangedCount());
        assertEquals(1, ai.embeddedTexts.size());
        assertTrue(ai.embeddedTexts.get(0).contains("late po"));
        assertTrue(repo.rows.get("conn-1::retail-v1::purchase-order").docText().contains("late po"));
    }

    @Test
    void aStaleProjectionVersionIsTreatedAsChanged() {
        sync.synchronize();
        var row = repo.rows.get("conn-1::retail-v1::purchase-order");
        repo.rows.put(row.conceptUid(), new FakeEmbeddingRepository.Row(row.conceptUid(), row.connectionKey(),
                row.packKey(), row.conceptKey(), row.contentHash(), "1", row.docText(), row.embedding()));
        ai.embeddedTexts.clear();

        var result = sync.synchronize();

        assertEquals(1, result.updatedCount());
        assertEquals(1, ai.embeddedTexts.size());
    }

    @Test
    void aConceptNoLongerInThePostgresCatalogIsDeletedFromTheIndex() {
        sync.synchronize();
        semantic.usedConceptKeysByConnection.put("conn-1", List.of("purchase-order"));

        var result = sync.synchronize();

        assertEquals(1, result.deletedCount());
        assertEquals(List.of("conn-1::retail-v1::purchase-order"), List.copyOf(repo.rows.keySet()));
    }

    @Test
    void aFailedEmbeddingIsReportedPartialWritesNothingForThatConceptAndDoesNotAdvanceTheWatermark() {
        ai.failure = new IllegalStateException("embedding service down");

        var result = sync.synchronize();

        assertEquals(ConceptKnowledgeSynchronizationService.Status.FAILED, result.status());
        assertEquals(2, result.failedCount());
        assertTrue(repo.rows.isEmpty());
    }

    @Test
    void withoutATenantContextNothingRuns() {
        TenantContext.clear();

        var result = sync.synchronize();

        assertEquals(ConceptKnowledgeSynchronizationService.Status.FAILED, result.status());
        assertTrue(ai.embeddedTexts.isEmpty());
    }

    // ── reconciliation ────────────────────────────────────────────────────────────────────────

    @Test
    void reconciliationSyncsEveryActiveTenantAndSkipsInactiveAndTheSharedPublicSchema() {
        tenants.tenants.add(tenant("acme", "tenant_acme", "ACTIVE"));
        tenants.tenants.add(tenant("gone", "tenant_gone", "SUSPENDED"));
        tenants.tenants.add(tenant("default", "public", "ACTIVE"));
        TenantContext.clear();

        sync.reconcileAllTenants();

        assertEquals(2, repo.rows.size(), "only the one ACTIVE, non-public tenant was synchronized");
        assertFalse(TenantContext.isSet(), "the context is cleared after each tenant");
    }

    @Test
    void theStartupReconciliationRunsWithNoTenantContextOnTheCallingThread() throws Exception {
        // Regression: it was once @Async on the tenant-propagating executor, which refuses a task
        // submitted with no tenant context — so on a real startup it was silently dropped.
        tenants.tenants.add(tenant("acme", "tenant_acme", "ACTIVE"));
        TenantContext.clear();

        sync.reconcileOnStartup();

        long deadline = System.currentTimeMillis() + 5000;
        while (repo.rows.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertEquals(2, repo.rows.size());
    }}
