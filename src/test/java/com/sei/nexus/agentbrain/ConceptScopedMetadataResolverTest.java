package com.sei.nexus.agentbrain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.SemanticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concept-Scoped Metadata Narrowing — Stage 1 (tenant concept catalog + LLM concept selection)
 * and Stage 2 (targeted physical metadata retrieval), exercised against {@link
 * ConceptScopedMetadataResolver} directly. Hand-rolled fakes, no DB, no Mockito — this project's
 * convention (mirrors {@code BusinessObjectBatchAnalyzerConceptResolutionTest} and {@code
 * IndustryPackServiceBindingTest}).
 */
class ConceptScopedMetadataResolverTest {

    // ── fakes ─────────────────────────────────────────────────────────────────

    static class FakeIndustryPackRepository extends IndustryPackRepository {
        final Map<String, TenantPack> activeByConnection = new LinkedHashMap<>();
        final Map<String, IndustryPack> catalogue = new LinkedHashMap<>();

        FakeIndustryPackRepository() { super(null, new ObjectMapper()); }

        @Override public Optional<TenantPack> findActivePackForConnection(String connectionKey) {
            return Optional.ofNullable(activeByConnection.get(connectionKey));
        }
        @Override public Optional<IndustryPack> findPackById(String packId) {
            return Optional.ofNullable(catalogue.get(packId));
        }
        void assign(String connectionKey, IndustryPack pack) {
            catalogue.put(pack.packId(), pack);
            activeByConnection.put(connectionKey, new TenantPack(pack.packId(), connectionKey, "1.0.0",
                    pack.displayName(), "ACTIVE", Map.of(), 1.0, null, "user@x.com"));
        }
    }

    static class FakeSemanticService extends SemanticService {
        // connectionKey -> distinct concept_keys actually used (Stage 1 candidate universe)
        final Map<String, List<String>> usedConceptKeysByConnection = new LinkedHashMap<>();
        // connectionKey -> all ACTIVE entities bound to it (used to serve Stage 2 by filtering on conceptKeys)
        final Map<String, List<BusinessEntity>> entitiesByConnection = new LinkedHashMap<>();
        final List<String> connectionKeysQueriedForCatalog = new ArrayList<>();
        final List<List<String>> conceptSelectionsQueried = new ArrayList<>();

        FakeSemanticService() { super(null, null, null); }

        @Override
        public List<String> findDistinctConceptKeysForConnection(String connectionKey) {
            connectionKeysQueriedForCatalog.add(connectionKey);
            return usedConceptKeysByConnection.getOrDefault(connectionKey, List.of());
        }

        @Override
        public List<BusinessEntity> findEntitiesByConnectionAndConcepts(String connectionKey, List<String> conceptKeys) {
            conceptSelectionsQueried.add(conceptKeys);
            List<BusinessEntity> all = entitiesByConnection.getOrDefault(connectionKey, List.of());
            return all.stream().filter(e -> conceptKeys.contains(e.conceptKey())).toList();
        }
    }

    /** Captures the Stage 1 prompt and returns a scripted response instead of calling a real model. */
    static class CapturingAiClient extends AzureOpenAiClient {
        String lastUserMessage;
        String lastSystemPrompt;
        String scriptedResponse = "{\"metadataRequest\":{\"conceptKeys\":[]}}";
        boolean throwOnCall = false;

        CapturingAiClient() { super(new ObjectMapper(), null); }

        @Override
        public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
            if (throwOnCall) throw new RuntimeException("simulated LLM failure");
            lastUserMessage  = messages.get(0).content();
            lastSystemPrompt = systemPrompt;
            return scriptedResponse;
        }
    }

    private static PackEntity concept(String conceptKey, String name, List<String> aliases,
                                       String description, String operationalMeaning) {
        return new PackEntity(name, aliases, List.of(), List.of(), description, operationalMeaning, conceptKey, null);
    }

    private static IndustryPack retailPack() {
        return new IndustryPack("retail-v1", "RETAIL", "Retail & E-commerce", "2.0.0", "desc",
                List.of(
                        concept("product", "Product", List.of("SKU", "Item"),
                                "A sellable product.", "Used for merchandising."),
                        concept("store", "Store", List.of("Retail Location"),
                                "A customer-facing selling location.", "Used for store performance."),
                        concept("supplier", "Supplier", List.of("Vendor"),
                                "The external partner goods are bought from.", "Used for procurement."),
                        concept("inventory-balance", "Inventory Balance", List.of("Stock Level"),
                                "Current on-hand quantity.", "Used for stockout risk."),
                        concept("purchase-order", "Purchase Order", List.of("PO"),
                                "A procurement commitment to a supplier.", "Used for spend tracking.")),
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, null);
    }

    private static BusinessEntity entity(String entityKey, String primaryObjectKey, String conceptKey) {
        Instant now = Instant.now();
        return new BusinessEntity(entityKey, "PLATFORM", entityKey, "desc", primaryObjectKey,
                "", "", "ACTIVE", "steward@x.com", now, now, null, null, "retail-v1", conceptKey);
    }

    private FakeIndustryPackRepository packRepository;
    private FakeSemanticService semanticService;
    private CapturingAiClient aiClient;
    private ConceptScopedMetadataResolver resolver;

    @BeforeEach
    void setUp() {
        packRepository = new FakeIndustryPackRepository();
        semanticService = new FakeSemanticService();
        aiClient = new CapturingAiClient();
        resolver = new ConceptScopedMetadataResolver(packRepository, semanticService, aiClient, new ObjectMapper());
    }

    // ── Item 1 — distinct concept_keys only, for the current connection ──────────

    @Test
    void tenantConceptCatalogContainsOnlyDistinctConceptKeysForTheConnection() {
        packRepository.assign("conn-1", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("product", "store"));
        aiClient.scriptedResponse = "{\"metadataRequest\":{\"conceptKeys\":[]}}";

        resolver.resolveObjectKeys("conn-1", "any question");

        assertTrue(aiClient.lastUserMessage.contains("concept_key: product"));
        assertTrue(aiClient.lastUserMessage.contains("concept_key: store"));
        assertFalse(aiClient.lastUserMessage.contains("concept_key: supplier"),
                "a concept not used by this tenant connection must never reach the catalog");
        assertFalse(aiClient.lastUserMessage.contains("concept_key: inventory-balance"));
        assertFalse(aiClient.lastUserMessage.contains("concept_key: purchase-order"));
    }

    // ── Item 2 — another connection's concepts are excluded ─────────────────────

    @Test
    void anotherConnectionsConceptsAreExcludedFromThisConnectionsCatalog() {
        packRepository.assign("conn-1", retailPack());
        packRepository.assign("conn-2", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("product"));
        semanticService.usedConceptKeysByConnection.put("conn-2", List.of("purchase-order"));

        resolver.resolveObjectKeys("conn-1", "q");

        assertTrue(aiClient.lastUserMessage.contains("concept_key: product"));
        assertFalse(aiClient.lastUserMessage.contains("concept_key: purchase-order"),
                "connection B's tenant concepts must never leak into connection A's catalog");
        assertEquals(List.of("conn-1"), semanticService.connectionKeysQueriedForCatalog,
                "only connection A must ever be queried for connection A's request");
    }

    // ── Item 3 — NULL concept_keys are excluded (nothing to intersect with) ──────

    @Test
    void connectionWithOnlyNullConceptKeysHasNoCatalogAndFallsBackToFullAssembly() {
        packRepository.assign("conn-1", retailPack());
        // No entry in usedConceptKeysByConnection == every Business Entity's concept_key is NULL —
        // findDistinctConceptKeysForConnection (which filters "concept_key IS NOT NULL" in the
        // real SQL) would return nothing for this connection.
        Optional<List<String>> result = resolver.resolveObjectKeys("conn-1", "q");

        assertTrue(result.isEmpty(), "no tenant concept catalog ⇒ Stage 1 does not apply ⇒ caller must fall back");
        assertNull(aiClient.lastUserMessage, "the LLM must never even be called when there is no catalog to offer it");
    }

    // ── Item 4 — Pack concepts the tenant does NOT use are excluded ──────────────

    @Test
    void packConceptsNotUsedByTheTenantAreExcludedFromTheCatalog() {
        packRepository.assign("conn-1", retailPack()); // pack has 5 concepts
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("store")); // tenant uses only 1

        resolver.resolveObjectKeys("conn-1", "q");

        long conceptLines = aiClient.lastUserMessage.lines().filter(l -> l.contains("concept_key:")).count();
        assertEquals(1, conceptLines, "only the tenant's actually-used concept must reach the LLM, not the full Pack catalogue");
        assertTrue(aiClient.lastUserMessage.contains("concept_key: store"));
    }

    // ── Item 5 — full Pack-authored semantic information reaches the LLM ─────────

    @Test
    void fullPackSemanticInformationReachesTheLlmCatalog() {
        packRepository.assign("conn-1", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("product"));

        resolver.resolveObjectKeys("conn-1", "q");

        assertTrue(aiClient.lastUserMessage.contains("concept_key: product"));
        assertTrue(aiClient.lastUserMessage.contains("name: Product"));
        assertTrue(aiClient.lastUserMessage.contains("SKU, Item"), "aliases must reach the LLM");
        assertTrue(aiClient.lastUserMessage.contains("A sellable product."), "description must reach the LLM");
        assertTrue(aiClient.lastUserMessage.contains("Used for merchandising."), "operationalMeaning must reach the LLM");
    }

    // ── Items 6/7 — no physical table/column names in the Stage 1 context ───────

    @Test
    void stage1ContextContainsNoPhysicalTableNames() {
        packRepository.assign("conn-1", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("product", "store"));
        semanticService.entitiesByConnection.put("conn-1", List.of(
                entity("product", "platform-conn-1-products", "product"),
                entity("store", "platform-conn-1-stores", "store")));

        resolver.resolveObjectKeys("conn-1", "q");

        assertFalse(aiClient.lastUserMessage.toLowerCase().contains("products"),
                "physical table name must never appear in the Stage 1 concept-selection context");
        assertFalse(aiClient.lastUserMessage.toLowerCase().contains("stores"));
        assertFalse(aiClient.lastUserMessage.contains("platform-conn-1"), "physical object_key must never appear either");
    }

    @Test
    void stage1ContextContainsNoPhysicalColumnNames() {
        packRepository.assign("conn-1", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("inventory-balance"));

        resolver.resolveObjectKeys("conn-1", "q");

        assertFalse(aiClient.lastUserMessage.contains("on_hand_qty"));
        assertFalse(aiClient.lastUserMessage.contains("available_qty"));
        assertFalse(aiClient.lastUserMessage.contains("reorder_point"),
                "no column-level metadata belongs in Stage 1 at all");
    }

    // ── Item 8 — the LLM can select multiple concepts ────────────────────────────

    @Test
    void llmCanSelectMultipleConcepts() {
        packRepository.assign("conn-1", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("product", "store", "inventory-balance"));
        semanticService.entitiesByConnection.put("conn-1", List.of(
                entity("product", "obj-product", "product"),
                entity("store", "obj-store", "store"),
                entity("inv", "obj-inv", "inventory-balance")));
        aiClient.scriptedResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\",\"store\",\"inventory-balance\"]}}";

        Optional<List<String>> result = resolver.resolveObjectKeys("conn-1",
                "Which stores had products below the reorder point last month?");

        assertTrue(result.isPresent());
        assertEquals(3, result.get().size());
        assertTrue(result.get().containsAll(List.of("obj-product", "obj-store", "obj-inv")));
    }

    // ── Item 9 — the LLM can select zero concepts ────────────────────────────────

    @Test
    void llmCanSelectZeroConceptsAndThatIsAValidResult() {
        packRepository.assign("conn-1", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("product"));
        aiClient.scriptedResponse = "{\"metadataRequest\":{\"conceptKeys\":[]}}";

        Optional<List<String>> result = resolver.resolveObjectKeys("conn-1", "What is the weather today?");

        assertTrue(result.isPresent(), "Stage 1 IS applicable (a catalog existed) — the LLM just found nothing relevant");
        assertTrue(result.get().isEmpty());
    }

    // ── Item 10 — Java validates but does not choose ─────────────────────────────

    @Test
    void javaValidatesReturnedConceptKeysButNeverChoosesThemItself() {
        packRepository.assign("conn-1", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("product", "store"));
        semanticService.entitiesByConnection.put("conn-1", List.of(entity("product", "obj-product", "product")));
        // The LLM's own decision — Java must relay it verbatim, not derive it from the question text.
        aiClient.scriptedResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]}}";

        resolver.resolveObjectKeys("conn-1", "totally unrelated question text with no keyword overlap");

        assertEquals(List.of(List.of("product")), semanticService.conceptSelectionsQueried,
                "Stage 2 must receive exactly the LLM's validated selection, unmodified by any Java keyword matching");
    }

    // ── Item 11 — invalid/invented concept_keys are rejected ────────────────────

    @Test
    void invalidOrInventedConceptKeysAreRejected() {
        packRepository.assign("conn-1", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("product"));
        aiClient.scriptedResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\",\"made-up-concept\"]}}";

        resolver.resolveObjectKeys("conn-1", "q");

        assertEquals(List.of(List.of("product")), semanticService.conceptSelectionsQueried,
                "an invented concept_key not in the offered catalog must be dropped, never passed to Stage 2");
    }

    // ── Item 12 — Stage 2 retrieves ALL Business Entities for the selected concepts ─

    @Test
    void stage2RetrievesAllBusinessEntitiesForTheSelectedConcepts() {
        packRepository.assign("conn-1", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("product", "store", "supplier"));
        semanticService.entitiesByConnection.put("conn-1", List.of(
                entity("product", "obj-product", "product"),
                entity("store", "obj-store", "store"),
                entity("supplier", "obj-supplier", "supplier")));
        aiClient.scriptedResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\",\"store\"]}}";

        Optional<List<String>> result = resolver.resolveObjectKeys("conn-1", "q");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
        assertTrue(result.get().containsAll(List.of("obj-product", "obj-store")));
        assertFalse(result.get().contains("obj-supplier"), "an unselected concept's object must not be retrieved");
    }

    // ── Item 13 — multiple physical objects with the same concept_key ───────────

    @Test
    void multiplePhysicalObjectsWithTheSameConceptKeyAreAllReturned() {
        packRepository.assign("conn-1", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("purchase-order"));
        semanticService.entitiesByConnection.put("conn-1", List.of(
                entity("pos-sales", "obj-pos-sales", "purchase-order"),
                entity("ecommerce-orders", "obj-ecommerce-orders", "purchase-order")));
        aiClient.scriptedResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"purchase-order\"]}}";

        Optional<List<String>> result = resolver.resolveObjectKeys("conn-1", "q");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size(), "both physical objects for the one selected concept must be returned, never just one");
        assertTrue(result.get().containsAll(List.of("obj-pos-sales", "obj-ecommerce-orders")));
    }

    // ── Item 14 — only selected concepts cause physical metadata retrieval ──────

    @Test
    void onlySelectedConceptsCauseStage2RetrievalNotTheWholeCatalog() {
        packRepository.assign("conn-1", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1",
                List.of("product", "store", "supplier", "inventory-balance", "purchase-order"));
        semanticService.entitiesByConnection.put("conn-1", List.of(
                entity("product", "obj-product", "product"),
                entity("store", "obj-store", "store"),
                entity("supplier", "obj-supplier", "supplier"),
                entity("inv", "obj-inv", "inventory-balance"),
                entity("po", "obj-po", "purchase-order")));
        aiClient.scriptedResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"inventory-balance\"]}}";

        Optional<List<String>> result = resolver.resolveObjectKeys("conn-1", "how much stock do we have?");

        assertEquals(List.of("obj-inv"), result.orElseThrow(),
                "retrieval must be scoped to exactly the 1 selected concept out of the tenant's 5 available concepts");
    }

    // ── No active pack / any failure ⇒ Stage 1 inapplicable ─────────────────────

    @Test
    void noActivePackForConnectionMeansStage1DoesNotApply() {
        // packRepository has no assignment for conn-1 at all
        Optional<List<String>> result = resolver.resolveObjectKeys("conn-1", "q");
        assertTrue(result.isEmpty());
        assertNull(aiClient.lastUserMessage, "the LLM must never be called when there is no active pack");
    }

    @Test
    void anLlmFailureDuringConceptSelectionFallsBackGracefullyRatherThanThrowing() {
        packRepository.assign("conn-1", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("product"));
        aiClient.throwOnCall = true;

        Optional<List<String>> result = resolver.resolveObjectKeys("conn-1", "q");

        // The catalog existed and Stage 1 "ran" (just failed) — selecting zero concepts is the
        // safe degradation, not a thrown exception that would break the caller's whole request.
        assertTrue(result.isPresent());
        assertTrue(result.get().isEmpty());
    }
}
