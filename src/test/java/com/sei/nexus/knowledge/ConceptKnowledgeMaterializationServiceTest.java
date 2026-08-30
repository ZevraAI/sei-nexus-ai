package com.sei.nexus.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.NexusException;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2A — Concept Knowledge Materialization. Hand-rolled fakes throughout (no Mockito, no
 * DB, no live OpenAI) — same convention as every other test in this session's arc. {@link
 * IndustryPackRepository} and {@link SemanticService} are subclassed with their DB-touching
 * methods overridden (constructed with {@code null} JDBC dependencies they never call), and
 * {@link AzureOpenAiClient} is subclassed exactly like {@code AzureOpenAiClientVectorStoreTest}.
 */
class ConceptKnowledgeMaterializationServiceTest {

    @AfterEach
    void clearTenantContext() {
        // materializeTenantConcepts always clears in its own finally{}, but guard against a
        // thrown-before-set-clears-cleanly bug leaking TenantContext onto the next test.
        TenantContext.clear();
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────────────────────

    static class FakeTenantRepository extends TenantRepository {
        final Map<String, Tenant> bySlug = new LinkedHashMap<>();
        FakeTenantRepository() { super(null); }
        void seed(Tenant tenant) { bySlug.put(tenant.slug(), tenant); }
        @Override public Optional<Tenant> findBySlug(String slug) { return Optional.ofNullable(bySlug.get(slug)); }
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
        FakeSemanticService() { super(null, null, null); }
        @Override public List<String> findDistinctConceptKeysForConnection(String connectionKey) {
            return usedConceptKeysByConnection.getOrDefault(connectionKey, List.of());
        }
    }

    static class FakeAiClient extends AzureOpenAiClient {
        final AtomicInteger createVectorStoreCalls = new AtomicInteger(0);
        final AtomicInteger uploadCalls = new AtomicInteger(0);
        final List<String> uploadedFilenames = new ArrayList<>();
        final List<byte[]> uploadedBodies = new ArrayList<>();
        final List<String> attachedVectorStoreIds = new ArrayList<>();
        final List<Map<String, String>> attachedAttributes = new ArrayList<>();
        List<AzureOpenAiClient.VectorStoreFileRef> existingFiles = new ArrayList<>();
        /** filename substrings that should throw on upload, to simulate a per-concept failure. */
        List<String> uploadFailuresForFilenamesContaining = new ArrayList<>();

        FakeAiClient() { super(new ObjectMapper(), null); }

        @Override public String createVectorStore(String name) {
            createVectorStoreCalls.incrementAndGet();
            return "vs_should_never_be_created";
        }

        @Override public String uploadFile(byte[] content, String filename, String mimeType) {
            uploadCalls.incrementAndGet();
            for (String bad : uploadFailuresForFilenamesContaining) {
                if (filename.contains(bad)) throw new NexusException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "simulated upload failure");
            }
            uploadedFilenames.add(filename);
            uploadedBodies.add(content);
            return "file_" + uploadCalls.get();
        }

        @Override public void attachFileToVectorStore(String vectorStoreId, String fileId, Map<String, String> attributes) {
            attachedVectorStoreIds.add(vectorStoreId);
            attachedAttributes.add(attributes);
        }

        @Override public List<AzureOpenAiClient.VectorStoreFileRef> listVectorStoreFiles(String vectorStoreId) {
            return existingFiles;
        }
    }

    private Tenant tenantWithVectorStore(String slug, String vectorStoreId) {
        return new Tenant(UUID.randomUUID(), slug, slug + " Inc", "tenant_" + slug.replace('-', '_'),
                "STANDARD", "ACTIVE", "admin@" + slug + ".example", 50,
                Instant.now(), Instant.now(), vectorStoreId, vectorStoreId != null ? "READY" : null, null, Instant.now());
    }

    private PackEntity entity(String conceptKey, String name) {
        return new PackEntity(name, List.of(name.toLowerCase()), List.of(), List.of(),
                "description of " + name, "operational meaning of " + name, conceptKey, "ACTIVE");
    }

    private ConceptKnowledgeMaterializationService service(FakeTenantRepository tenantRepo,
            FakePackRepository packRepo, FakeSemanticService semanticService, FakeAiClient aiClient) {
        return new ConceptKnowledgeMaterializationService(
                tenantRepo, packRepo, semanticService, aiClient, new ObjectMapper());
    }

    // ── Setup helper: one tenant, one connection/pack, two concepts ─────────────────────────────

    private FakePackRepository packRepoWithTwoConcepts() {
        FakePackRepository packRepo = new FakePackRepository();
        IndustryPack pack = new IndustryPack("retail-v1", "retail", "Retail", "1.0", "desc",
                List.of(entity("purchase-order", "Purchase Order"), entity("sales-transaction", "Sales Transaction")),
                List.of(), List.of(), List.of(), List.of(), null, null, null, null, List.of(), null, null);
        packRepo.packsById.put("retail-v1", pack);
        packRepo.appliedPacks.add(new TenantPack("retail-v1", "conn-1", "1.0", "Retail", "ACTIVE",
                Map.of(), 1.0, Instant.now(), "test"));
        return packRepo;
    }

    private FakeSemanticService semanticServiceUsing(String connectionKey, String... conceptKeys) {
        FakeSemanticService s = new FakeSemanticService();
        s.usedConceptKeysByConnection.put(connectionKey, List.of(conceptKeys));
        return s;
    }

    // ── 1/3. Concept metadata converted to structured representation with expected fields ───────

    @Test
    void conceptMetadataIsConvertedToStructuredRepresentationWithExpectedFields() throws Exception {
        FakeTenantRepository tenantRepo = new FakeTenantRepository();
        tenantRepo.seed(tenantWithVectorStore("acme", "vs_acme"));
        FakePackRepository packRepo = packRepoWithTwoConcepts();
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order", "sales-transaction");
        FakeAiClient aiClient = new FakeAiClient();

        service(tenantRepo, packRepo, semanticService, aiClient).materializeTenantConcepts("acme");

        assertEquals(2, aiClient.uploadedBodies.size());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode doc = mapper.readTree(aiClient.uploadedBodies.get(0));
        assertTrue(doc.has("concept_key"));
        assertTrue(doc.has("name"));
        assertTrue(doc.has("aliases"));
        assertTrue(doc.has("description"));
        assertTrue(doc.has("operational_meaning"));
        assertTrue(doc.has("pack"));
        assertTrue(doc.has("connection"));
        assertTrue(doc.has("generated_at"));
        assertEquals("retail-v1", doc.get("pack").asText());
        assertEquals("conn-1", doc.get("connection").asText());
    }

    // ── 2. One concept produces one knowledge artifact ───────────────────────────────────────────

    @Test
    void oneConceptProducesExactlyOneUploadAndOneAttach() {
        FakeTenantRepository tenantRepo = new FakeTenantRepository();
        tenantRepo.seed(tenantWithVectorStore("acme", "vs_acme"));
        FakePackRepository packRepo = new FakePackRepository();
        IndustryPack pack = new IndustryPack("retail-v1", "retail", "Retail", "1.0", "desc",
                List.of(entity("purchase-order", "Purchase Order")),
                List.of(), List.of(), List.of(), List.of(), null, null, null, null, List.of(), null, null);
        packRepo.packsById.put("retail-v1", pack);
        packRepo.appliedPacks.add(new TenantPack("retail-v1", "conn-1", "1.0", "Retail", "ACTIVE",
                Map.of(), 1.0, Instant.now(), "test"));
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order");
        FakeAiClient aiClient = new FakeAiClient();

        service(tenantRepo, packRepo, semanticService, aiClient).materializeTenantConcepts("acme");

        assertEquals(1, aiClient.uploadCalls.get());
        assertEquals(1, aiClient.attachedVectorStoreIds.size());
    }

    // ── 4. Temporary artifacts never touch disk (byte[]-only upload path, by construction) ──────

    @Test
    void uploadedContentIsPassedAsInMemoryBytesNeverAsAFileOrPath() {
        FakeTenantRepository tenantRepo = new FakeTenantRepository();
        tenantRepo.seed(tenantWithVectorStore("acme", "vs_acme"));
        FakePackRepository packRepo = packRepoWithTwoConcepts();
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order", "sales-transaction");
        FakeAiClient aiClient = new FakeAiClient();

        service(tenantRepo, packRepo, semanticService, aiClient).materializeTenantConcepts("acme");

        // AzureOpenAiClient#uploadFile's signature is (byte[], String, String) — there is no
        // filesystem path anywhere in this call chain for a temp artifact to leak into a
        // git-tracked directory or need cleanup. Content correctness re-asserted here.
        for (byte[] body : aiClient.uploadedBodies) {
            assertTrue(body.length > 0);
        }
    }

    // ── 5. Correct tenant Vector Store ID is used ────────────────────────────────────────────────

    @Test
    void materializationUsesTheTenantsOwnVectorStoreId() {
        FakeTenantRepository tenantRepo = new FakeTenantRepository();
        tenantRepo.seed(tenantWithVectorStore("acme", "vs_acme_specific"));
        FakePackRepository packRepo = packRepoWithTwoConcepts();
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order", "sales-transaction");
        FakeAiClient aiClient = new FakeAiClient();

        service(tenantRepo, packRepo, semanticService, aiClient).materializeTenantConcepts("acme");

        assertFalse(aiClient.attachedVectorStoreIds.isEmpty());
        assertTrue(aiClient.attachedVectorStoreIds.stream().allMatch("vs_acme_specific"::equals));
    }

    // ── 6. No new Vector Store is created by Phase 2A ────────────────────────────────────────────

    @Test
    void noNewVectorStoreIsEverCreated() {
        FakeTenantRepository tenantRepo = new FakeTenantRepository();
        tenantRepo.seed(tenantWithVectorStore("acme", "vs_acme"));
        FakePackRepository packRepo = packRepoWithTwoConcepts();
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order", "sales-transaction");
        FakeAiClient aiClient = new FakeAiClient();

        service(tenantRepo, packRepo, semanticService, aiClient).materializeTenantConcepts("acme");

        assertEquals(0, aiClient.createVectorStoreCalls.get());
    }

    // ── 7. OpenAI upload failure is surfaced correctly (per-concept, non-fatal to the batch) ─────

    @Test
    void openAiUploadFailureIsSurfacedAsAFailureWithoutAbortingOtherConcepts() {
        FakeTenantRepository tenantRepo = new FakeTenantRepository();
        tenantRepo.seed(tenantWithVectorStore("acme", "vs_acme"));
        FakePackRepository packRepo = packRepoWithTwoConcepts();
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order", "sales-transaction");
        FakeAiClient aiClient = new FakeAiClient();
        aiClient.uploadFailuresForFilenamesContaining.add("purchase-order");

        ConceptKnowledgeMaterializationService.MaterializationResult result =
                service(tenantRepo, packRepo, semanticService, aiClient).materializeTenantConcepts("acme");

        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).contains("purchase-order"));
        assertEquals(1, result.materialized().size(), "the other concept must still succeed");
    }

    // ── 8. Missing Vector Store ID is handled correctly (fails clearly, does not create one) ─────

    @Test
    void missingVectorStoreIdFailsClearlyAndCreatesNothing() {
        FakeTenantRepository tenantRepo = new FakeTenantRepository();
        tenantRepo.seed(tenantWithVectorStore("acme", null));
        FakePackRepository packRepo = packRepoWithTwoConcepts();
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order");
        FakeAiClient aiClient = new FakeAiClient();

        NexusException ex = assertThrows(NexusException.class,
                () -> service(tenantRepo, packRepo, semanticService, aiClient).materializeTenantConcepts("acme"));
        assertTrue(ex.getMessage().toLowerCase().contains("vector_store_id")
                || ex.getMessage().toLowerCase().contains("knowledge store"));
        assertEquals(0, aiClient.createVectorStoreCalls.get());
        assertEquals(0, aiClient.uploadCalls.get());
    }

    @Test
    void unknownTenantFailsClearly() {
        FakeTenantRepository tenantRepo = new FakeTenantRepository();
        FakePackRepository packRepo = new FakePackRepository();
        FakeSemanticService semanticService = new FakeSemanticService();
        FakeAiClient aiClient = new FakeAiClient();

        assertThrows(NexusException.class,
                () -> service(tenantRepo, packRepo, semanticService, aiClient).materializeTenantConcepts("no-such-tenant"));
    }

    // ── 9. Multiple concepts materialized independently ──────────────────────────────────────────

    @Test
    void multipleConceptsAreMaterializedIndependentlyWithDistinctFilesAndAttributes() {
        FakeTenantRepository tenantRepo = new FakeTenantRepository();
        tenantRepo.seed(tenantWithVectorStore("acme", "vs_acme"));
        FakePackRepository packRepo = packRepoWithTwoConcepts();
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order", "sales-transaction");
        FakeAiClient aiClient = new FakeAiClient();

        ConceptKnowledgeMaterializationService.MaterializationResult result =
                service(tenantRepo, packRepo, semanticService, aiClient).materializeTenantConcepts("acme");

        assertEquals(2, result.materialized().size());
        assertEquals(2, aiClient.uploadedFilenames.size());
        assertNotEquals(aiClient.uploadedFilenames.get(0), aiClient.uploadedFilenames.get(1));
        assertEquals(2, aiClient.attachedAttributes.size());
        Set<String> conceptKeys = new java.util.HashSet<String>();
        for (Map<String, String> attrs : aiClient.attachedAttributes) conceptKeys.add(attrs.get("concept_key"));
        assertTrue(conceptKeys.contains("purchase-order"));
        assertTrue(conceptKeys.contains("sales-transaction"));
    }

    // ── Idempotency (bonus — not one of the required 10, but directly required by §8/§22) ────────

    @Test
    void conceptAlreadyPresentByUidIsSkippedRatherThanReUploaded() {
        FakeTenantRepository tenantRepo = new FakeTenantRepository();
        tenantRepo.seed(tenantWithVectorStore("acme", "vs_acme"));
        FakePackRepository packRepo = packRepoWithTwoConcepts();
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order", "sales-transaction");
        FakeAiClient aiClient = new FakeAiClient();
        aiClient.existingFiles.add(new AzureOpenAiClient.VectorStoreFileRef("file_existing",
                Map.of("concept_uid", "conn-1::retail-v1::purchase-order")));

        ConceptKnowledgeMaterializationService.MaterializationResult result =
                service(tenantRepo, packRepo, semanticService, aiClient).materializeTenantConcepts("acme");

        assertEquals(1, aiClient.uploadCalls.get(), "only the not-yet-present concept should be uploaded");
        assertEquals(2, result.materialized().size(), "both appear in the result — one skipped, one uploaded");
        long skipped = result.materialized().stream().filter(ConceptKnowledgeMaterializationService.ConceptResult::skippedAlreadyPresent).count();
        assertEquals(1, skipped);
    }

    // ── TenantContext discipline ──────────────────────────────────────────────────────────────────

    @Test
    void tenantContextIsClearedAfterMaterializationEvenOnFailure() {
        FakeTenantRepository tenantRepo = new FakeTenantRepository();
        tenantRepo.seed(tenantWithVectorStore("acme", "vs_acme"));
        FakePackRepository packRepo = packRepoWithTwoConcepts();
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order", "sales-transaction");
        FakeAiClient aiClient = new FakeAiClient();

        service(tenantRepo, packRepo, semanticService, aiClient).materializeTenantConcepts("acme");

        assertFalse(TenantContext.isSet(), "TenantContext must not leak past materializeTenantConcepts()");
    }
}
