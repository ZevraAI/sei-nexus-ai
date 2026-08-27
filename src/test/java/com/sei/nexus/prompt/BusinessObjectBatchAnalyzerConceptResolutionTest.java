package com.sei.nexus.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.EntityCandidateService;
import com.sei.nexus.sql.DynamicSqlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Connection-Scoped Industry Pack Semantic Assignment — {@link BusinessObjectBatchAnalyzer}'s
 * upstream candidate-preparation layer: the connection's ACTIVE Industry Pack's canonical
 * concepts (sourced from {@code pack.entities()} — see the class's own javadoc for why, given
 * every shipped pack today has an empty {@code groups()}) are offered to the existing LLM as
 * additive context; the LLM's own {@code conceptResolution} decision is validated against that
 * exact list and flattened into a top-level {@code conceptKey}, never assigned by Java itself.
 * Hand-rolled fakes, no DB, no Mockito — this project's convention.
 */
class BusinessObjectBatchAnalyzerConceptResolutionTest {

    static class FakeEntityCandidateService extends EntityCandidateService {
        FakeEntityCandidateService() { super(null); }
        @Override public List<Candidate> retrieve(String domainKey, String tableName) { return List.of(); }
    }

    static class FakeDynamicSqlService extends DynamicSqlService {
        FakeDynamicSqlService() { super(null); }
        @Override
        public DynamicSqlService.TableDescription describeTableWithComments(
                String connectionKey, String schemaName, String tableName) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("column_name", "id");
            col.put("data_type", "uuid");
            col.put("is_nullable", "NO");
            return new DynamicSqlService.TableDescription(List.of(col), null);
        }
    }

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

    /** Captures both prompt halves instead of calling a real model; returns a scripted response. */
    static class CapturingAiClient extends AzureOpenAiClient {
        String lastUserMessage;
        String lastSystemPrompt;
        String scriptedResponse = "{\"tables\":[{\"table_name\":\"t\",\"category\":\"Other\"}]}";

        CapturingAiClient() { super(new ObjectMapper(), null); }

        @Override
        public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
            lastUserMessage  = messages.get(0).content();
            lastSystemPrompt = systemPrompt;
            return scriptedResponse;
        }
    }

    private static PackEntity concept(String conceptKey, String name, List<String> aliases, String description) {
        return new PackEntity(name, aliases, List.of(), List.of(), description, "", conceptKey, null);
    }

    // Retail Pack V2 / Send operationalMeaning to the LLM: a variant carrying a real, non-blank
    // operationalMeaning — used only by the tests proving it now reaches the rendered prompt.
    private static PackEntity conceptWithOperationalMeaning(String conceptKey, String name,
            List<String> aliases, String description, String operationalMeaning) {
        return new PackEntity(name, aliases, List.of(), List.of(), description, operationalMeaning, conceptKey, null);
    }

    private static IndustryPack retailPack() {
        return new IndustryPack("retail-v1", "RETAIL", "Retail & E-commerce", "1.0.0", "desc",
                List.of(
                        concept("purchase-order", "Purchase Order", List.of("PO", "Procurement Order"),
                                "An order placed with a supplier for goods or services."),
                        concept("product", "Product", List.of("SKU", "Item", "Article"),
                                "A sellable item carried in inventory.")),
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, null);
    }

    private CapturingAiClient aiClient;
    private FakeDynamicSqlService dynamicSql;
    private FakeIndustryPackRepository packRepository;
    private BusinessObjectBatchAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        aiClient = new CapturingAiClient();
        dynamicSql = new FakeDynamicSqlService();
        packRepository = new FakeIndustryPackRepository();
        analyzer = new BusinessObjectBatchAnalyzer(aiClient, dynamicSql,
                new FakeEntityCandidateService(), new ObjectMapper(), packRepository);
    }

    // ── Test 1 — connection Pack lookup (automatic, no manual pack-per-object) ───

    @Test
    void connectionsActivePackIsRetrievedAutomaticallyWithoutTheCallerSupplyingIt() {
        packRepository.assign("conn-1", retailPack());

        // Caller passes only what it always has — connectionKey — never a pack key.
        analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("ord_hdr"));

        assertTrue(aiClient.lastUserMessage.contains("Industry Pack: retail-v1"),
                "the connection's active pack must be resolved automatically from connectionKey alone");
    }

    @Test
    void aConnectionWithNoActivePackAddsNoConceptContextAtAll() {
        // packRepository has no assignment for conn-1
        analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("orders"));

        assertFalse(aiClient.lastUserMessage.contains("Industry Pack:"));
        assertFalse(aiClient.lastSystemPrompt.contains("conceptResolution"),
                "no active pack -> the prompt/response contract must be byte-identical to before this feature");
    }

    // ── Test 2 — Pack concepts reach the LLM (canonical keys/names present) ──────

    @Test
    void packConceptsReachTheLlmRequest() {
        packRepository.assign("conn-1", retailPack());

        analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("ord_hdr"));

        assertTrue(aiClient.lastUserMessage.contains("concept_key: purchase-order"));
        assertTrue(aiClient.lastUserMessage.contains("name: Purchase Order"));
        assertTrue(aiClient.lastUserMessage.contains("PO, Procurement Order"));
        assertTrue(aiClient.lastUserMessage.contains("concept_key: product"));
        assertTrue(aiClient.lastSystemPrompt.contains("conceptResolution"),
                "the response contract must request the model's concept decision when a pack is active");
    }

    @Test
    void thePromptExplicitlyWarnsAgainstAssumingPhysicalNamesMatchConceptNames() {
        packRepository.assign("conn-1", retailPack());

        analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("ord_hdr"));

        assertTrue(aiClient.lastSystemPrompt.toLowerCase().contains("business meaning"),
                "the rule must steer the model toward actual business meaning, not physical name similarity");
    }

    // ── Test 3 / 5 — LLM concept result survives, and is flattened to a top-level field ──

    @Test
    void aValidLlmConceptResolutionSurvivesAsATopLevelConceptKey() {
        packRepository.assign("conn-1", retailPack());
        aiClient.scriptedResponse = """
                {"tables":[{"table_name":"ord_hdr","category":"Transactions",
                  "conceptResolution":{"conceptKey":"purchase-order","confidence":"HIGH","reason":"vendor+order columns"}}]}""";

        var result = analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("ord_hdr"));

        assertEquals("purchase-order", result.get("ord_hdr").get("conceptKey"));
        assertEquals("retail-v1", result.get("ord_hdr").get("packKey"));
        // Existing fields must remain untouched by this addition.
        assertEquals("Transactions", result.get("ord_hdr").get("category"));
    }

    // ── Test 6 — invalid/invented concept is rejected, never persisted ───────────

    @Test
    void anInventedConceptKeyNotOfferedByThePackIsDiscarded() {
        packRepository.assign("conn-1", retailPack());
        aiClient.scriptedResponse = """
                {"tables":[{"table_name":"t","category":"Other",
                  "conceptResolution":{"conceptKey":"made-up-concept","confidence":"HIGH","reason":"guess"}}]}""";

        var result = analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("t"));

        assertFalse(result.get("t").containsKey("conceptKey"),
                "a concept_key the model invented (not in the offered list) must never be persisted");
        assertEquals("retail-v1", result.get("t").get("packKey"),
                "packKey is independent of whether a concept resolved — it's connection metadata, not an LLM decision");
    }

    // ── Test 7 — unresolved concept (LLM declines) leaves concept_key absent ─────

    @Test
    void aNullConceptResolutionLeavesConceptKeyAbsentNoFakeConcept() {
        packRepository.assign("conn-1", retailPack());
        aiClient.scriptedResponse = """
                {"tables":[{"table_name":"xref_04","category":"Other",
                  "conceptResolution":{"conceptKey":null,"confidence":"LOW","reason":"no clear match"}}]}""";

        var result = analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("xref_04"));

        assertFalse(result.get("xref_04").containsKey("conceptKey"),
                "an unresolved table must never receive a fabricated concept_key");
    }

    @Test
    void aResponseWithNoConceptResolutionFieldAtAllLeavesConceptKeyAbsent() {
        packRepository.assign("conn-1", retailPack());
        aiClient.scriptedResponse = "{\"tables\":[{\"table_name\":\"t\",\"category\":\"Other\"}]}"; // no conceptResolution at all

        var result = analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("t"));

        assertFalse(result.get("t").containsKey("conceptKey"));
        assertEquals("retail-v1", result.get("t").get("packKey"));
    }

    // ── Test 12 — multiple connections resolve independent packs, no cross-contamination ──

    @Test
    void differentConnectionsResolveIndependentPacksNeverEachOthers() {
        IndustryPack logisticsPack = new IndustryPack("logistics-v1", "LOGISTICS", "Logistics & Supply Chain",
                "1.0.0", "desc",
                List.of(concept("shipment-order", "Shipment Order", List.of("Shipment"), "An order to ship goods.")),
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, null);
        packRepository.assign("conn-1", retailPack());
        packRepository.assign("conn-2", logisticsPack);

        analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("orders"));
        assertTrue(aiClient.lastUserMessage.contains("Industry Pack: retail-v1"));
        assertTrue(aiClient.lastUserMessage.contains("concept_key: purchase-order"));
        assertFalse(aiClient.lastUserMessage.contains("shipment-order"),
                "conn-1's prompt must never see conn-2's Logistics concepts");

        analyzer.analyzeBatch("conn-2", "logistics_core", "PLATFORM", List.of("orders"));
        assertTrue(aiClient.lastUserMessage.contains("Industry Pack: logistics-v1"));
        assertTrue(aiClient.lastUserMessage.contains("concept_key: shipment-order"));
        assertFalse(aiClient.lastUserMessage.contains("purchase-order"),
                "conn-2's prompt must never see conn-1's Retail concepts, even though 'orders' is the same table name");
    }

    // ── Existing behavior preserved (no active pack anywhere in the call) ────────

    @Test
    void existingCallersWithoutAPackRepositoryStillWorkUnchanged() {
        // The 4-arg backward-compatible constructor — no pack awareness at all.
        var legacyAnalyzer = new BusinessObjectBatchAnalyzer(aiClient, dynamicSql,
                new FakeEntityCandidateService(), new ObjectMapper());

        var result = legacyAnalyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("orders"));

        assertFalse(aiClient.lastUserMessage.contains("Industry Pack:"));
        assertFalse(aiClient.lastSystemPrompt.contains("conceptResolution"));
        assertFalse(result.get("orders").containsKey("packKey"));
        assertFalse(result.get("orders").containsKey("conceptKey"));
    }

    // ── Retail Pack V2 — operationalMeaning now reaches the LLM concept catalog ──

    @Test
    void operationalMeaningReachesTheRenderedConceptCatalog() {
        IndustryPack pack = new IndustryPack("retail-v1", "RETAIL", "Retail & E-commerce", "2.0.0", "desc",
                List.of(conceptWithOperationalMeaning("purchase-order", "Purchase Order",
                        List.of("PO"), "A procurement commitment to a supplier.",
                        "Used for procurement spend tracking and supplier commitment management.")),
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, null);
        packRepository.assign("conn-1", pack);

        analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("ord_hdr"));

        assertTrue(aiClient.lastUserMessage.contains(
                        "Used for procurement spend tracking and supplier commitment management."),
                "operationalMeaning must now be rendered into the concept catalog sent to the LLM — "
                        + "confirmed missing before this fix");
        // description must still be present too — this is additive, not a replacement.
        assertTrue(aiClient.lastUserMessage.contains("A procurement commitment to a supplier."));
    }

    @Test
    void aConceptWithNoOperationalMeaningRendersExactlyAsBeforeThisFeature() {
        packRepository.assign("conn-1", retailPack()); // uses concept(), operationalMeaning == ""
        analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("ord_hdr"));

        assertFalse(aiClient.lastUserMessage.contains("operational meaning:"),
                "a blank operationalMeaning must add nothing to the prompt — no empty label emitted");
    }

    // ── Retail Pack V2 — Java never assigns concept_key, even with a richer catalog ─

    @Test
    void javaNeverAutoAssignsAConceptKeyWhenTheLlmOffersNoResolutionAtAllAcrossAFullCatalog() {
        // A realistically sized catalog (several concepts) — proves Java doesn't fall back to
        // "the closest" or "the first" concept when the LLM simply omits conceptResolution.
        IndustryPack pack = new IndustryPack("retail-v1", "RETAIL", "Retail & E-commerce", "2.0.0", "desc",
                List.of(
                        concept("purchase-order", "Purchase Order", List.of("PO"), "desc"),
                        concept("sales-transaction", "Sales Transaction", List.of("Sale"), "desc"),
                        concept("goods-receipt", "Goods Receipt", List.of("Receiving"), "desc"),
                        concept("shipment", "Shipment", List.of("Shipping"), "desc")),
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, null);
        packRepository.assign("conn-1", pack);
        aiClient.scriptedResponse = "{\"tables\":[{\"table_name\":\"t\",\"category\":\"Other\"}]}"; // no conceptResolution

        var result = analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("t"));

        assertFalse(result.get("t").containsKey("conceptKey"),
                "with no conceptResolution from the LLM, Java must leave conceptKey entirely absent — "
                        + "never default to any of the 4 offered concepts");
        assertEquals("retail-v1", result.get("t").get("packKey"), "packKey is connection metadata, independent of resolution");
    }
}
