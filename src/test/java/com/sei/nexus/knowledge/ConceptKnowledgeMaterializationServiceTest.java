package com.sei.nexus.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.EmbeddingResult;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.LearnedMapping;
import com.sei.nexus.semantic.LearnedMappingRepository;
import com.sei.nexus.semantic.SemanticService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The authoritative concept projection and its retrieval document. Hand-rolled fakes throughout (no
 * Mockito, no DB, no live OpenAI) — {@link IndustryPackRepository}, {@link SemanticService}, {@link
 * LearnedMappingRepository}, {@link ConceptEmbeddingRepository} and {@link AzureOpenAiClient} are
 * subclassed with their I/O methods overridden.
 */
class ConceptKnowledgeMaterializationServiceTest {

    // ── Fakes (package-visible: the synchronization test reuses them) ─────────────────────────

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

    /** Records the text it is asked to embed and returns a vector derived from that text's length. */
    static class FakeEmbeddingClient extends AzureOpenAiClient {
        final List<String> embeddedTexts = new ArrayList<>();
        RuntimeException failure;
        FakeEmbeddingClient() { super(new ObjectMapper(), null); }
        @Override public EmbeddingResult embed(String text) {
            if (failure != null) throw failure;
            embeddedTexts.add(text);
            return new EmbeddingResult(new float[] {text.length(), 1f}, 3);
        }
    }

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

    /** In-memory stand-in for the {@code nexus_concept_embedding} table. */
    static class FakeEmbeddingRepository extends ConceptEmbeddingRepository {
        record Row(String conceptUid, String connectionKey, String packKey, String conceptKey,
                   String contentHash, String projectionVersion, String docText, float[] embedding) {}
        final Map<String, Row> rows = new LinkedHashMap<>();
        FakeEmbeddingRepository() { super(null); }
        @Override public void upsert(String conceptUid, String connectionKey, String packKey, String conceptKey,
                                     String contentHash, String projectionVersion, String docText, float[] embedding) {
            rows.put(conceptUid, new Row(conceptUid, connectionKey, packKey, conceptKey, contentHash,
                    projectionVersion, docText, embedding));
        }
        @Override public Map<String, String> findFingerprintsByUid() {
            Map<String, String> out = new LinkedHashMap<>();
            rows.forEach((uid, r) -> out.put(uid, r.contentHash() + "|" + r.projectionVersion()));
            return out;
        }
        @Override public void deleteByUid(String conceptUid) { rows.remove(conceptUid); }
        @Override public int countForConnection(String connectionKey) {
            return (int) rows.values().stream().filter(r -> r.connectionKey().equals(connectionKey)).count();
        }
        @Override public int countAll() { return rows.size(); }
    }

    static LearnedMapping learnedMapping(String mappingKey, String businessTerm, String sqlPattern, double confidence) {
        return new LearnedMapping(mappingKey, "PLATFORM", businessTerm, sqlPattern, "run-1",
                "QUERY_SUCCESS", confidence, 5, Instant.now(), true, Instant.now(), Instant.now(),
                "purchase-order");
    }

    static PackEntity entity(String conceptKey, String name) {
        return new PackEntity(name, List.of(name.toLowerCase()), List.of(), List.of(),
                "description of " + name, "operational meaning of " + name, conceptKey, "ACTIVE");
    }

    static FakePackRepository packRepoWithTwoConcepts() {
        FakePackRepository packRepo = new FakePackRepository();
        IndustryPack pack = new IndustryPack("retail-v1", "retail", "Retail", "1.0", "desc",
                List.of(entity("purchase-order", "Purchase Order"), entity("sales-transaction", "Sales Transaction")),
                List.of(), List.of(), List.of(), List.of(), null, null, null, null, List.of(), null, null);
        packRepo.packsById.put("retail-v1", pack);
        packRepo.appliedPacks.add(new TenantPack("retail-v1", "conn-1", "1.0", "Retail", "ACTIVE",
                Map.of(), 1.0, Instant.now(), "test"));
        return packRepo;
    }

    static FakeSemanticService semanticServiceUsing(String connectionKey, String... conceptKeys) {
        FakeSemanticService s = new FakeSemanticService();
        s.usedConceptKeysByConnection.put(connectionKey, List.of(conceptKeys));
        return s;
    }

    private ConceptKnowledgeMaterializationService service(FakePackRepository packRepo, FakeSemanticService semantic,
                                                           FakeEmbeddingClient ai, FakeLearnedMappingRepository learned,
                                                           FakeEmbeddingRepository repo) {
        return new ConceptKnowledgeMaterializationService(packRepo, semantic, ai, learned, repo);
    }

    private ConceptKnowledgeMaterializationService defaultService() {
        return service(packRepoWithTwoConcepts(), semanticServiceUsing("conn-1", "purchase-order", "sales-transaction"),
                new FakeEmbeddingClient(), new FakeLearnedMappingRepository(), new FakeEmbeddingRepository());
    }

    // ── collectConceptUnits ───────────────────────────────────────────────────────────────────

    @Test
    void unitsAreThePackConceptsActuallyUsedOnTheConnection() {
        var svc = service(packRepoWithTwoConcepts(), semanticServiceUsing("conn-1", "purchase-order"),
                new FakeEmbeddingClient(), new FakeLearnedMappingRepository(), new FakeEmbeddingRepository());

        var units = svc.collectConceptUnits();

        assertEquals(1, units.size());
        assertEquals("conn-1::retail-v1::purchase-order", units.get(0).uid());
    }

    @Test
    void aPackAssignmentWithoutAConnectionKeyIsSkipped() {
        FakePackRepository packRepo = packRepoWithTwoConcepts();
        packRepo.appliedPacks.add(new TenantPack("retail-v1", null, "1.0", "Retail", "ACTIVE",
                Map.of(), 1.0, Instant.now(), "test"));
        var svc = service(packRepo, semanticServiceUsing("conn-1", "purchase-order", "sales-transaction"),
                new FakeEmbeddingClient(), new FakeLearnedMappingRepository(), new FakeEmbeddingRepository());

        assertEquals(2, svc.collectConceptUnits().size());
    }

    // ── retrievalDocument ─────────────────────────────────────────────────────────────────────

    @Test
    void theRetrievalDocumentLeadsWithTheConceptKeyThenNameAliasesDescriptionAndMeaning() {
        var svc = defaultService();
        var unit = svc.collectConceptUnits().get(0);

        String doc = svc.retrievalDocument(unit);

        assertEquals("purchase-order — Purchase Order (also called: purchase order). "
                + "description of Purchase Order operational meaning of Purchase Order", doc);
    }

    @Test
    void learnedBusinessTermsAreAppendedSortedByMappingKeyAndTheRenderIsDeterministic() {
        FakeLearnedMappingRepository learned = new FakeLearnedMappingRepository();
        learned.seed("purchase-order",
                learnedMapping("b-key", "late delivery", "expected_date < now()", 0.9),
                learnedMapping("a-key", "open po", "status = 'OPEN'", 0.8));
        var svc = service(packRepoWithTwoConcepts(), semanticServiceUsing("conn-1", "purchase-order"),
                new FakeEmbeddingClient(), learned, new FakeEmbeddingRepository());
        var unit = svc.collectConceptUnits().get(0);

        String doc = svc.retrievalDocument(unit);

        assertTrue(doc.endsWith("Business terms learned for this concept: "
                + "\"open po\" means status = 'OPEN'; \"late delivery\" means expected_date < now()."), doc);
        assertEquals(doc, svc.retrievalDocument(unit));
    }

    // ── contentHash ───────────────────────────────────────────────────────────────────────────

    @Test
    void theContentHashIsStableAndChangesWhenALearningIsPromoted() {
        FakeLearnedMappingRepository learned = new FakeLearnedMappingRepository();
        var svc = service(packRepoWithTwoConcepts(), semanticServiceUsing("conn-1", "purchase-order"),
                new FakeEmbeddingClient(), learned, new FakeEmbeddingRepository());
        String before = svc.contentHash(svc.collectConceptUnits().get(0));
        assertEquals(before, svc.contentHash(svc.collectConceptUnits().get(0)));

        learned.seed("purchase-order", learnedMapping("k", "term", "x = 1", 0.9));

        assertNotEquals(before, svc.contentHash(svc.collectConceptUnits().get(0)));
    }

    // ── embedAndStore ─────────────────────────────────────────────────────────────────────────

    @Test
    void embedAndStoreEmbedsTheRetrievalDocumentAndUpsertsItWithTheFingerprint() {
        FakeEmbeddingClient ai = new FakeEmbeddingClient();
        FakeEmbeddingRepository repo = new FakeEmbeddingRepository();
        var svc = service(packRepoWithTwoConcepts(), semanticServiceUsing("conn-1", "purchase-order"),
                ai, new FakeLearnedMappingRepository(), repo);
        var unit = svc.collectConceptUnits().get(0);

        svc.embedAndStore(unit);

        String doc = svc.retrievalDocument(unit);
        assertEquals(List.of(doc), ai.embeddedTexts, "the exact text stored is the text embedded");
        var row = repo.rows.get(unit.uid());
        assertEquals(doc, row.docText());
        assertEquals("purchase-order", row.conceptKey());
        assertEquals("conn-1", row.connectionKey());
        assertEquals("retail-v1", row.packKey());
        assertEquals(svc.contentHash(unit), row.contentHash());
        assertEquals(ConceptKnowledgeMaterializationService.PROJECTION_VERSION, row.projectionVersion());
        assertEquals(doc.length(), row.embedding()[0]);
    }

    @Test
    void anEmbeddingFailurePropagatesAndWritesNothing() {
        FakeEmbeddingClient ai = new FakeEmbeddingClient();
        ai.failure = new IllegalStateException("embedding service down");
        FakeEmbeddingRepository repo = new FakeEmbeddingRepository();
        var svc = service(packRepoWithTwoConcepts(), semanticServiceUsing("conn-1", "purchase-order"),
                ai, new FakeLearnedMappingRepository(), repo);

        assertThrows(IllegalStateException.class, () -> svc.embedAndStore(svc.collectConceptUnits().get(0)));
        assertTrue(repo.rows.isEmpty());
    }

    // ── listConceptCatalog ────────────────────────────────────────────────────────────────────

    @Test
    void theCatalogHasOneRowPerDistinctConceptSortedByName() {
        var catalog = defaultService().listConceptCatalog();

        assertEquals(List.of("purchase-order", "sales-transaction"),
                catalog.stream().map(r -> r.get("conceptKey")).toList());
        assertEquals("Purchase Order", catalog.get(0).get("name"));
    }
}
