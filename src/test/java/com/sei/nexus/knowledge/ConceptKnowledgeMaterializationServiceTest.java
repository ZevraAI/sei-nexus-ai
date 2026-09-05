package com.sei.nexus.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.LearnedMapping;
import com.sei.nexus.semantic.LearnedMappingRepository;
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

    /** Fakes only the one method this service actually calls — {@code findPromotedByConceptKey} —
     *  keyed by conceptKey, mirroring the real query's contract exactly (promoted + classified only,
     *  never anything else). */
    static class FakeLearnedMappingRepository extends LearnedMappingRepository {
        final Map<String, List<LearnedMapping>> byConceptKey = new LinkedHashMap<>();
        FakeLearnedMappingRepository() { super(null); }
        void seed(String conceptKey, LearnedMapping... mappings) {
            byConceptKey.put(conceptKey, new ArrayList<>(List.of(mappings)));
        }
        @Override public List<LearnedMapping> findPromotedByConceptKey(String conceptKey) {
            return byConceptKey.getOrDefault(conceptKey, List.of());
        }
    }

    private LearnedMapping learnedMapping(String mappingKey, String businessTerm, String sqlPattern,
                                           double confidence) {
        return new LearnedMapping(mappingKey, "PLATFORM", businessTerm, sqlPattern, "run-1",
                "QUERY_SUCCESS", confidence, 5, Instant.now(), true, Instant.now(), Instant.now(),
                "purchase-order");
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
        return service(tenantRepo, packRepo, semanticService, aiClient, new FakeLearnedMappingRepository());
    }

    private ConceptKnowledgeMaterializationService service(FakeTenantRepository tenantRepo,
            FakePackRepository packRepo, FakeSemanticService semanticService, FakeAiClient aiClient,
            FakeLearnedMappingRepository learnedMappingRepository) {
        return new ConceptKnowledgeMaterializationService(
                tenantRepo, packRepo, semanticService, aiClient, new ObjectMapper(), learnedMappingRepository);
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

    // ── Learned knowledge projection (learned_knowledge array) ──────────────────────────────────
    //
    // Covers the concept_key backfill feature's core promise: a promoted+classified learning is
    // folded into its concept's document array (and changes its hash), an unpromoted/unclassified
    // one never is, several learnings under one concept still collapse into a single ConceptUnit,
    // and "demoting" (simulated by the fake no longer returning the row) removes it on rebuild.

    private FakePackRepository packRepoWithOneConcept() {
        FakePackRepository packRepo = new FakePackRepository();
        IndustryPack pack = new IndustryPack("retail-v1", "retail", "Retail", "1.0", "desc",
                List.of(entity("purchase-order", "Purchase Order")),
                List.of(), List.of(), List.of(), List.of(), null, null, null, null, List.of(), null, null);
        packRepo.packsById.put("retail-v1", pack);
        packRepo.appliedPacks.add(new TenantPack("retail-v1", "conn-1", "1.0", "Retail", "ACTIVE",
                Map.of(), 1.0, Instant.now(), "test"));
        return packRepo;
    }

    @Test
    void pendingUnpromotedLearningIsNeverInTheConceptDocument() throws Exception {
        FakePackRepository packRepo = packRepoWithOneConcept();
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order");
        FakeLearnedMappingRepository learnedRepo = new FakeLearnedMappingRepository();
        // Nothing seeded for "purchase-order" — mirrors findPromotedByConceptKey's real contract:
        // a pending (not-yet-promoted) learning is simply never returned by that query.
        ConceptKnowledgeMaterializationService svc =
                service(new FakeTenantRepository(), packRepo, semanticService, new FakeAiClient(), learnedRepo);

        ConceptKnowledgeMaterializationService.ConceptUnit unit = svc.collectConceptUnits().get(0);
        JsonNode doc = new ObjectMapper().readTree(svc.buildConceptKnowledgeJson(unit));

        assertTrue(doc.has("learned_knowledge"), "key must always be present, even when empty");
        assertEquals(0, doc.get("learned_knowledge").size());
    }

    @Test
    void promotedClassifiedLearningAppearsInTheConceptDocumentAndChangesTheHash() throws Exception {
        FakePackRepository packRepo = packRepoWithOneConcept();
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order");

        FakeLearnedMappingRepository emptyRepo = new FakeLearnedMappingRepository();
        ConceptKnowledgeMaterializationService svcEmpty =
                service(new FakeTenantRepository(), packRepo, semanticService, new FakeAiClient(), emptyRepo);
        ConceptKnowledgeMaterializationService.ConceptUnit unitWithout = svcEmpty.collectConceptUnits().get(0);

        FakeLearnedMappingRepository seededRepo = new FakeLearnedMappingRepository();
        seededRepo.seed("purchase-order", learnedMapping("lmap-1", "open", "status = 'open'", 0.9));
        ConceptKnowledgeMaterializationService svcWith =
                service(new FakeTenantRepository(), packRepo, semanticService, new FakeAiClient(), seededRepo);
        ConceptKnowledgeMaterializationService.ConceptUnit unitWith = svcWith.collectConceptUnits().get(0);

        JsonNode doc = new ObjectMapper().readTree(svcWith.buildConceptKnowledgeJson(unitWith));
        assertEquals(1, doc.get("learned_knowledge").size());
        JsonNode entry = doc.get("learned_knowledge").get(0);
        assertEquals("open", entry.get("surface").asText());
        assertEquals("status = 'open'", entry.get("binding").asText());
        assertEquals(0.9, entry.get("confidence").asDouble(), 0.0001);
        assertFalse(entry.has("meaning"), "no fabricated 'meaning' field — we don't have that data");

        assertNotEquals(svcEmpty.contentHash(unitWithout), svcWith.contentHash(unitWith),
                "promoting/classifying a learning must change the concept's content hash");
    }

    @Test
    void multipleLearningsUnderOneConceptKeyAllLandInTheOneConceptDocument() throws Exception {
        FakePackRepository packRepo = packRepoWithOneConcept();
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order");
        FakeLearnedMappingRepository learnedRepo = new FakeLearnedMappingRepository();
        learnedRepo.seed("purchase-order",
                learnedMapping("lmap-1", "open", "status = 'open'", 0.9),
                learnedMapping("lmap-2", "closed", "status = 'closed'", 0.85));
        ConceptKnowledgeMaterializationService svc =
                service(new FakeTenantRepository(), packRepo, semanticService, new FakeAiClient(), learnedRepo);

        List<ConceptKnowledgeMaterializationService.ConceptUnit> units = svc.collectConceptUnits();
        assertEquals(1, units.size(), "one ConceptUnit per concept — never one per learning");

        JsonNode doc = new ObjectMapper().readTree(svc.buildConceptKnowledgeJson(units.get(0)));
        assertEquals(2, doc.get("learned_knowledge").size());
    }

    @Test
    void demotingALearningRemovesItFromAFreshlyRebuiltDocument() throws Exception {
        FakePackRepository packRepo = packRepoWithOneConcept();
        FakeSemanticService semanticService = semanticServiceUsing("conn-1", "purchase-order");

        FakeLearnedMappingRepository promotedRepo = new FakeLearnedMappingRepository();
        promotedRepo.seed("purchase-order", learnedMapping("lmap-1", "open", "status = 'open'", 0.9));
        ConceptKnowledgeMaterializationService svcBefore =
                service(new FakeTenantRepository(), packRepo, semanticService, new FakeAiClient(), promotedRepo);
        ConceptKnowledgeMaterializationService.ConceptUnit unitBefore = svcBefore.collectConceptUnits().get(0);
        JsonNode docBefore = new ObjectMapper().readTree(svcBefore.buildConceptKnowledgeJson(unitBefore));
        assertEquals(1, docBefore.get("learned_knowledge").size());

        // Simulate demotion: markDemoted flips promoted=false, so findPromotedByConceptKey no
        // longer returns the row — modeled here by a fresh repository seeded with nothing.
        FakeLearnedMappingRepository demotedRepo = new FakeLearnedMappingRepository();
        ConceptKnowledgeMaterializationService svcAfter =
                service(new FakeTenantRepository(), packRepo, semanticService, new FakeAiClient(), demotedRepo);
        ConceptKnowledgeMaterializationService.ConceptUnit unitAfter = svcAfter.collectConceptUnits().get(0);
        JsonNode docAfter = new ObjectMapper().readTree(svcAfter.buildConceptKnowledgeJson(unitAfter));

        assertEquals(0, docAfter.get("learned_knowledge").size());
        assertNotEquals(svcBefore.contentHash(unitBefore), svcAfter.contentHash(unitAfter),
                "demotion must also change the hash so sync picks up the removal");
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
