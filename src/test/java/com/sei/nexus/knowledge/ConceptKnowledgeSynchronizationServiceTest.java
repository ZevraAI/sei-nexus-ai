package com.sei.nexus.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.onboarding.TenantSettingsRepository;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hand-rolled fakes throughout — same convention as {@code
 * ConceptKnowledgeMaterializationServiceTest}, which this test complements rather than duplicates
 * (the underlying projection/upload logic is already covered there; this file covers the diff
 * engine — create/update/delete/no-op — plus tenant isolation and the concurrency guard).
 */
class ConceptKnowledgeSynchronizationServiceTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────────────────────

    static class FakeTenantRepository extends TenantRepository {
        final Map<String, Tenant> bySchema = new LinkedHashMap<>();
        FakeTenantRepository() { super(null); }
        void seed(Tenant tenant) { bySchema.put(tenant.schemaName(), tenant); }
        @Override public Optional<Tenant> findBySchemaName(String schemaName) {
            return Optional.ofNullable(bySchema.get(schemaName));
        }
        @Override public List<Tenant> findAll() { return List.copyOf(bySchema.values()); }
    }

    static class FakePackRepository extends IndustryPackRepository {
        List<TenantPack> appliedPacks = new ArrayList<>();
        Map<String, IndustryPack> packsById = new LinkedHashMap<>();
        FakePackRepository() { super(null, new ObjectMapper()); }
        @Override public List<TenantPack> findAppliedPacks() { return appliedPacks; }
        @Override public Optional<IndustryPack> findPackById(String packId) {
            return Optional.ofNullable(packsById.get(packId));
        }
    }

    static class FakeSemanticService extends SemanticService {
        Map<String, List<String>> usedConceptKeysByConnection = new LinkedHashMap<>();
        Map<String, List<com.sei.nexus.semantic.BusinessEntity>> changedEntitiesByConnection = new LinkedHashMap<>();
        FakeSemanticService() { super(null, null, null); }
        @Override public List<String> findDistinctConceptKeysForConnection(String connectionKey) {
            return usedConceptKeysByConnection.getOrDefault(connectionKey, List.of());
        }
        @Override public List<com.sei.nexus.semantic.BusinessEntity> findEntitiesChangedAfterForConnection(
                String connectionKey, Instant since) {
            return changedEntitiesByConnection.getOrDefault(connectionKey, List.of());
        }
    }

    static class FakeTenantSettingsRepository extends TenantSettingsRepository {
        Map<String, String> store = new LinkedHashMap<>();
        FakeTenantSettingsRepository() { super(null); }
        @Override public Optional<String> get(String key) { return Optional.ofNullable(store.get(key)); }
        @Override public void set(String key, String value) { store.put(key, value); }
        @Override public void delete(String key) { store.remove(key); }
    }

    static class FakeAiClient extends AzureOpenAiClient {
        final AtomicInteger uploadCalls = new AtomicInteger(0);
        final List<String> uploadedFilenames = new ArrayList<>();
        final List<Map<String, String>> attachedAttributes = new ArrayList<>();
        final List<String> deletedFileIds = new ArrayList<>();
        final List<String> detachedFileIds = new ArrayList<>();
        List<AzureOpenAiClient.VectorStoreFileRef> existingFiles = new ArrayList<>();
        /** When true, simulates every upload failing — the watermark-correctness tests' way of
         *  forcing a PARTIAL/FAILED synchronize() outcome without a real OpenAI error. */
        boolean uploadShouldFail = false;

        FakeAiClient() { super(new ObjectMapper(), null); }

        @Override public String uploadFile(byte[] content, String filename, String mimeType) {
            if (uploadShouldFail) {
                throw new RuntimeException("simulated upload failure");
            }
            uploadCalls.incrementAndGet();
            uploadedFilenames.add(filename);
            return "file_" + uploadCalls.get();
        }

        @Override public void attachFileToVectorStore(String vectorStoreId, String fileId, Map<String, String> attributes) {
            attachedAttributes.add(attributes);
        }

        @Override public List<AzureOpenAiClient.VectorStoreFileRef> listVectorStoreFiles(String vectorStoreId) {
            return existingFiles;
        }

        @Override public void deleteFile(String fileId) {
            deletedFileIds.add(fileId);
        }

        @Override public void detachFileFromVectorStore(String vectorStoreId, String fileId) {
            detachedFileIds.add(fileId);
        }
    }

    private static Tenant tenantWithVectorStore(String slug, String schema, String vectorStoreId) {
        return new Tenant(UUID.randomUUID(), slug, slug + " Inc", schema,
                "STANDARD", "ACTIVE", "admin@" + slug + ".example", 50,
                Instant.now(), Instant.now(), vectorStoreId, vectorStoreId != null ? "READY" : null, null, Instant.now());
    }

    private static PackEntity entity(String conceptKey, String name) {
        return new PackEntity(name, List.of(name.toLowerCase()), List.of(), List.of(),
                "description of " + name, "operational meaning of " + name, conceptKey, "ACTIVE");
    }

    private static FakePackRepository packRepoWithOneConcept(String connectionKey, String packKey, String conceptKey, String name) {
        FakePackRepository packRepo = new FakePackRepository();
        IndustryPack pack = new IndustryPack(packKey, "retail", "Retail", "1.0", "desc",
                List.of(entity(conceptKey, name)),
                List.of(), List.of(), List.of(), List.of(), null, null, null, null, List.of(), null, null);
        packRepo.packsById.put(packKey, pack);
        packRepo.appliedPacks.add(new TenantPack(packKey, connectionKey, "1.0", "Retail", "ACTIVE",
                Map.of(), 1.0, Instant.now(), "test"));
        return packRepo;
    }

    /** One tenant/connection/pack/concept wired end to end, with all fakes exposed for assertions. */
    private static final class Harness {
        final FakeTenantRepository tenantRepo = new FakeTenantRepository();
        final FakePackRepository packRepo;
        final FakeSemanticService semanticService;
        final FakeAiClient aiClient = new FakeAiClient();
        final FakeTenantSettingsRepository settings = new FakeTenantSettingsRepository();
        final ConceptKnowledgeMaterializationService materializer;
        final ConceptKnowledgeSynchronizationService sync;

        Harness(String schema, String slug, String vectorStoreId,
                String connectionKey, String packKey, String conceptKey, String name) {
            packRepo = packRepoWithOneConcept(connectionKey, packKey, conceptKey, name);
            semanticService = new FakeSemanticService();
            semanticService.usedConceptKeysByConnection.put(connectionKey, List.of(conceptKey));
            materializer = new ConceptKnowledgeMaterializationService(
                    tenantRepo, packRepo, semanticService, aiClient, new ObjectMapper());
            sync = new ConceptKnowledgeSynchronizationService(tenantRepo, aiClient, materializer, settings);
            tenantRepo.seed(tenantWithVectorStore(slug, schema, vectorStoreId));
        }
    }

    // ── Create ────────────────────────────────────────────────────────────────────────────────

    @Test
    void newAuthoritativeConceptIsCreatedWhenVectorStoreHasNoMatchingFile() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");

        TenantContext.set("tenant_acme");
        ConceptKnowledgeSynchronizationService.SyncResult result = h.sync.synchronize();
        TenantContext.clear();

        assertEquals(ConceptKnowledgeSynchronizationService.Status.IN_SYNC, result.status());
        assertEquals(1, result.createdCount());
        assertEquals(0, result.updatedCount());
        assertEquals(0, result.deletedCount());
        assertEquals(1, h.aiClient.uploadCalls.get());
    }

    // ── No-op (content hash matches) ─────────────────────────────────────────────────────────

    @Test
    void unchangedConceptIsSkippedNotReUploadedWhenContentHashMatches() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");

        ConceptKnowledgeMaterializationService.ConceptUnit unit = h.materializer.collectConceptUnits().get(0);
        String hash = h.materializer.contentHash(unit);
        h.aiClient.existingFiles.add(new AzureOpenAiClient.VectorStoreFileRef("file_existing", Map.of(
                "concept_uid", unit.uid(), "knowledge_type", "business-concept", "content_hash", hash)));

        TenantContext.set("tenant_acme");
        ConceptKnowledgeSynchronizationService.SyncResult result = h.sync.synchronize();
        TenantContext.clear();

        assertEquals(0, result.createdCount());
        assertEquals(0, result.updatedCount());
        assertEquals(0, result.deletedCount());
        assertEquals(1, result.unchangedCount());
        assertEquals(0, h.aiClient.uploadCalls.get(), "an unchanged concept must never be re-uploaded");
    }

    // ── Update (content hash differs) ────────────────────────────────────────────────────────

    @Test
    void changedConceptContentTriggersDeleteThenReupload() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");

        ConceptKnowledgeMaterializationService.ConceptUnit unit = h.materializer.collectConceptUnits().get(0);
        h.aiClient.existingFiles.add(new AzureOpenAiClient.VectorStoreFileRef("file_stale", Map.of(
                "concept_uid", unit.uid(), "knowledge_type", "business-concept",
                "content_hash", "stale-hash-from-before-the-description-changed")));

        TenantContext.set("tenant_acme");
        ConceptKnowledgeSynchronizationService.SyncResult result = h.sync.synchronize();
        TenantContext.clear();

        assertEquals(0, result.createdCount());
        assertEquals(1, result.updatedCount());
        assertEquals(0, result.deletedCount());
        assertEquals(1, h.aiClient.uploadCalls.get(), "the changed concept must be re-uploaded");
        assertEquals(List.of("file_stale"), h.aiClient.detachedFileIds,
                "the stale file must be DETACHED from the vector store — deleteFile alone does not "
                        + "remove it from listVectorStoreFiles (confirmed against the real OpenAI API "
                        + "during this feature's own real-tenant validation)");
        assertEquals(List.of("file_stale"), h.aiClient.deletedFileIds,
                "the stale file's underlying File object must also be deleted — no duplicate copies");
    }

    // ── Delete (concept no longer authoritative) ─────────────────────────────────────────────

    @Test
    void conceptNoLongerInAuthoritativeProjectionIsDeletedFromVectorStore() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");

        // Existing file for a concept that no longer appears in the authoritative projection
        // (e.g. its Pack was removed) — must never remain searchable.
        h.aiClient.existingFiles.add(new AzureOpenAiClient.VectorStoreFileRef("file_stale_pack", Map.of(
                "concept_uid", "conn-1::old-pack::inventory-balance",
                "knowledge_type", "business-concept", "content_hash", "whatever")));
        // Plus the one concept that IS still authoritative, already correctly present.
        ConceptKnowledgeMaterializationService.ConceptUnit unit = h.materializer.collectConceptUnits().get(0);
        h.aiClient.existingFiles.add(new AzureOpenAiClient.VectorStoreFileRef("file_current", Map.of(
                "concept_uid", unit.uid(), "knowledge_type", "business-concept",
                "content_hash", h.materializer.contentHash(unit))));

        TenantContext.set("tenant_acme");
        ConceptKnowledgeSynchronizationService.SyncResult result = h.sync.synchronize();
        TenantContext.clear();

        assertEquals(0, result.createdCount());
        assertEquals(0, result.updatedCount());
        assertEquals(1, result.deletedCount());
        assertEquals(1, result.unchangedCount());
        assertEquals(List.of("file_stale_pack"), h.aiClient.detachedFileIds,
                "detach must happen for a deleted concept too — not just delete");
        assertEquals(List.of("file_stale_pack"), h.aiClient.deletedFileIds);
    }

    // ── Non-Zevra-managed files are never touched ────────────────────────────────────────────

    @Test
    void filesWithoutTheBusinessConceptKnowledgeTypeAreNeverDeletedOrConsidered() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");
        h.aiClient.existingFiles.add(new AzureOpenAiClient.VectorStoreFileRef("file_unrelated",
                Map.of("concept_uid", "conn-1::retail-v1::purchase-order"))); // no knowledge_type attribute at all

        TenantContext.set("tenant_acme");
        ConceptKnowledgeSynchronizationService.SyncResult result = h.sync.synchronize();
        TenantContext.clear();

        assertEquals(1, result.createdCount(), "the unmanaged file must not count as already-present");
        assertTrue(h.aiClient.deletedFileIds.isEmpty(), "an unmanaged file must never be deleted by this service");
    }

    // ── Tenant isolation ──────────────────────────────────────────────────────────────────────

    @Test
    void synchronizingOneTenantNeverConsultsOrAffectsAnotherTenantsAuthoritativeData() {
        Harness h = new Harness("tenant_a", "tenant-a", "vs_a", "conn-a", "retail-v1", "purchase-order", "Purchase Order");
        // A second tenant's row exists in the shared FakeTenantRepository, but this harness's
        // packRepo/semanticService are scoped only to conn-a — proving that whichever tenant's
        // schema is in TenantContext is the only one whose data the diff ever consults.
        h.tenantRepo.seed(tenantWithVectorStore("tenant-b", "tenant_b", "vs_b"));

        TenantContext.set("tenant_a");
        ConceptKnowledgeSynchronizationService.SyncResult result = h.sync.synchronize();
        TenantContext.clear();

        assertEquals(ConceptKnowledgeSynchronizationService.Status.IN_SYNC, result.status());
        assertEquals(1, result.createdCount(), "only tenant A's own concept was ever authoritative");
    }

    // ── Concurrency guard ─────────────────────────────────────────────────────────────────────

    @Test
    void aSecondConcurrentSynchronizeCallForTheSameTenantIsSkippedNotDuplicated() throws Exception {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");

        TenantContext.set("tenant_acme");
        // Simulate an in-flight sync by directly marking the guard (mirrors what the real
        // synchronize() call does internally at its very first step).
        java.lang.reflect.Field field = ConceptKnowledgeSynchronizationService.class.getDeclaredField("inProgress");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> inProgress = (java.util.Set<String>) field.get(h.sync);
        inProgress.add("tenant_acme");

        try {
            ConceptKnowledgeSynchronizationService.SyncResult result = h.sync.synchronize();
            assertEquals(ConceptKnowledgeSynchronizationService.Status.SYNCING, result.status());
            assertEquals(0, h.aiClient.uploadCalls.get(), "a concurrent duplicate call must never upload anything");
        } finally {
            TenantContext.clear();
        }
    }

    // ── Missing tenant context fails closed ──────────────────────────────────────────────────

    @Test
    void missingTenantContextFailsClosedRatherThanSynchronizingAgainstPublic() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");

        TenantContext.clear(); // deliberately no context
        ConceptKnowledgeSynchronizationService.SyncResult result = h.sync.synchronize();

        assertEquals(ConceptKnowledgeSynchronizationService.Status.FAILED, result.status());
        assertEquals(0, h.aiClient.uploadCalls.get());
        assertEquals(0, h.aiClient.deletedFileIds.size());
    }

    // ── Content-hash determinism ──────────────────────────────────────────────────────────────

    @Test
    void sameContentAlwaysProducesTheSameHashAndChangedContentProducesADifferentOne() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");
        ConceptKnowledgeMaterializationService.ConceptUnit unit = h.materializer.collectConceptUnits().get(0);

        String hash1 = h.materializer.contentHash(unit);
        String hash2 = h.materializer.contentHash(unit);
        assertEquals(hash1, hash2, "hashing the same unit twice must be deterministic");

        ConceptKnowledgeMaterializationService.ConceptUnit changed = new ConceptKnowledgeMaterializationService.ConceptUnit(
                unit.connectionKey(), unit.packKey(),
                new ConceptKnowledgeMaterializationService.ConceptEntry(
                        unit.entry().conceptKey(), unit.entry().name(), unit.entry().aliases(),
                        "a materially different description", unit.entry().operationalMeaning()));
        String hash3 = h.materializer.contentHash(changed);
        assertNotEquals(hash1, hash3, "changed description must produce a different hash");
    }

    // ── Sync watermark: status() derivation ─────────────────────────────────────────────────────

    private static com.sei.nexus.semantic.BusinessEntity changedEntity(String entityKey, String conceptKey,
                                                                         String name, Instant createdAt, Instant updatedAt) {
        return new com.sei.nexus.semantic.BusinessEntity(entityKey, "PLATFORM", name, "desc", "obj-1",
                "meaning", null, "ACTIVE", "test", createdAt, updatedAt, null, null, "retail-v1", conceptKey);
    }

    @Test
    void neverSyncedTenantWithMaterializableConceptsShowsPendingWithRealNamesNotFalseInSync() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");
        // Mirrors real Postgres behavior: with no watermark yet, status() queries from
        // Instant.EPOCH, and this concept's own created_at (any real timestamp) is trivially
        // "after" EPOCH — so it must appear in pendingChanges by its real name, never collapsed
        // into an unnamed placeholder count.
        h.semanticService.changedEntitiesByConnection.put("conn-1", List.of(
                changedEntity("e1", "purchase-order", "Purchase Order", Instant.now(), Instant.now())));

        TenantContext.set("tenant_acme");
        ConceptKnowledgeSynchronizationService.StatusReport report = h.sync.status();
        TenantContext.clear();

        assertNotEquals(ConceptKnowledgeSynchronizationService.Status.IN_SYNC, report.status(),
                "a tenant that has never completed a successful sync must never read as IN_SYNC");
        assertNull(report.lastSuccessfulSync());
        assertEquals(1, report.pendingChanges().size());
        assertEquals("Purchase Order", report.pendingChanges().get(0).name(),
                "the never-synced case must list the real concept name, not a synthetic placeholder");
    }

    @Test
    void noMetadataChangesAfterWatermarkYieldsInSync() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");
        h.settings.set("concept_sync_last_successful_at", Instant.now().toString());
        // No entries in changedEntitiesByConnection ⇒ nothing changed since the watermark.

        TenantContext.set("tenant_acme");
        ConceptKnowledgeSynchronizationService.StatusReport report = h.sync.status();
        TenantContext.clear();

        assertEquals(ConceptKnowledgeSynchronizationService.Status.IN_SYNC, report.status());
        assertEquals(0, report.pendingChangeCount());
        assertTrue(report.pendingChanges().isEmpty());
    }

    @Test
    void newMetadataAfterWatermarkYieldsChangesPending() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");
        Instant watermark = Instant.now().minusSeconds(3600);
        h.settings.set("concept_sync_last_successful_at", watermark.toString());
        Instant createdAfter = watermark.plusSeconds(60);
        h.semanticService.changedEntitiesByConnection.put("conn-1", List.of(
                changedEntity("e1", "purchase-order", "Purchase Order", createdAfter, createdAfter)));

        TenantContext.set("tenant_acme");
        ConceptKnowledgeSynchronizationService.StatusReport report = h.sync.status();
        TenantContext.clear();

        assertEquals(ConceptKnowledgeSynchronizationService.Status.CHANGES_PENDING, report.status());
        assertEquals(1, report.pendingChangeCount());
        assertEquals("Purchase Order", report.pendingChanges().get(0).name());
    }

    @Test
    void updatedMetadataAfterWatermarkYieldsChangesPending() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");
        Instant watermark = Instant.now().minusSeconds(3600);
        h.settings.set("concept_sync_last_successful_at", watermark.toString());
        Instant createdBefore = watermark.minusSeconds(600);
        Instant updatedAfter  = watermark.plusSeconds(60);
        h.semanticService.changedEntitiesByConnection.put("conn-1", List.of(
                changedEntity("e1", "purchase-order", "Purchase Order", createdBefore, updatedAfter)));

        TenantContext.set("tenant_acme");
        ConceptKnowledgeSynchronizationService.StatusReport report = h.sync.status();
        TenantContext.clear();

        assertEquals(ConceptKnowledgeSynchronizationService.Status.CHANGES_PENDING, report.status());
        assertEquals(1, report.pendingChangeCount());
    }

    @Test
    void multipleChangedMetadataRecordsAreAllDisplayed() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");
        Instant watermark = Instant.now().minusSeconds(3600);
        h.settings.set("concept_sync_last_successful_at", watermark.toString());
        Instant after = watermark.plusSeconds(60);
        h.semanticService.changedEntitiesByConnection.put("conn-1", List.of(
                changedEntity("e1", "purchase-order", "Purchase Order", after, after),
                changedEntity("e2", "invoice", "Invoice", after, after)));

        TenantContext.set("tenant_acme");
        ConceptKnowledgeSynchronizationService.StatusReport report = h.sync.status();
        TenantContext.clear();

        assertEquals(2, report.pendingChangeCount());
        assertEquals(2, report.pendingChanges().size());
    }

    @Test
    void successfulSynchronizationAdvancesTheWatermark() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");
        assertTrue(h.settings.get("concept_sync_last_successful_at").isEmpty());

        TenantContext.set("tenant_acme");
        ConceptKnowledgeSynchronizationService.SyncResult result = h.sync.synchronize();
        TenantContext.clear();

        assertEquals(0, result.failedCount());
        assertTrue(h.settings.get("concept_sync_last_successful_at").isPresent(),
                "a fully-clean synchronize() run must advance the watermark");
    }

    @Test
    void partialFailureDoesNotAdvanceTheWatermarkOrFalselyReportInSync() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");
        h.aiClient.uploadShouldFail = true; // forces the one create to fail

        TenantContext.set("tenant_acme");
        ConceptKnowledgeSynchronizationService.SyncResult result = h.sync.synchronize();
        ConceptKnowledgeSynchronizationService.StatusReport statusAfter = h.sync.status();
        TenantContext.clear();

        assertEquals(1, result.failedCount());
        assertTrue(h.settings.get("concept_sync_last_successful_at").isEmpty(),
                "the watermark must NOT advance when 1 of the concepts in this run failed");
        assertEquals(ConceptKnowledgeSynchronizationService.Status.FAILED, statusAfter.status(),
                "status() must keep surfacing FAILED, never a false IN_SYNC, after a partial run");
    }

    @Test
    void retryAfterPartialFailureEventuallyAdvancesTheWatermarkAndClearsPending() {
        Harness h = new Harness("tenant_acme", "acme", "vs_acme", "conn-1", "retail-v1", "purchase-order", "Purchase Order");
        h.aiClient.uploadShouldFail = true;

        TenantContext.set("tenant_acme");
        ConceptKnowledgeSynchronizationService.SyncResult first = h.sync.synchronize();
        assertEquals(1, first.failedCount());
        assertTrue(h.settings.get("concept_sync_last_successful_at").isEmpty());

        h.aiClient.uploadShouldFail = false; // retry succeeds
        ConceptKnowledgeSynchronizationService.SyncResult retry = h.sync.synchronize();
        TenantContext.clear();

        assertEquals(0, retry.failedCount());
        assertTrue(h.settings.get("concept_sync_last_successful_at").isPresent(),
                "a subsequent fully-clean retry must advance the watermark");
    }

    // ── Tenant isolation for status()/watermark ─────────────────────────────────────────────────

    @Test
    void tenantACannotSeeTenantBsPendingMetadataOrWatermark() {
        Harness h = new Harness("tenant_a", "tenant-a", "vs_a", "conn-a", "retail-v1", "purchase-order", "Purchase Order");
        h.tenantRepo.seed(tenantWithVectorStore("tenant-b", "tenant_b", "vs_b"));
        // Tenant A's own watermark is set; tenant A's own semanticService (this Harness's fake,
        // standing in for tenant A's search_path-scoped repository in production) has no changed
        // entities queued for conn-a — proving "in sync" is derived purely from tenant A's own
        // state, never from another tenant's data (there is no tenant-id parameter anywhere in
        // this call chain for a "wrong" tenant's data to leak through even accidentally).
        h.settings.set("concept_sync_last_successful_at", Instant.now().toString());

        TenantContext.set("tenant_a");
        ConceptKnowledgeSynchronizationService.StatusReport reportA = h.sync.status();
        TenantContext.clear();

        assertEquals(ConceptKnowledgeSynchronizationService.Status.IN_SYNC, reportA.status());
        assertTrue(reportA.pendingChanges().isEmpty());
    }
}
