package com.sei.nexus.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.connection.NexusConnection;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapService;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.prompt.BusinessObjectBatchAnalyzer;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.EntityCandidateService;
import com.sei.nexus.semantic.OperationalVocabulary;
import com.sei.nexus.semantic.RelationshipDiscoveryService;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.sql.DynamicSqlService;
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
 * Connection-Scoped Industry Pack Semantic Assignment — end-to-end lifecycle, exercising the
 * FULL chain this task's acceptance criteria describe: {@link BusinessObjectBatchAnalyzer}
 * (analysis, LLM concept resolution) feeding directly into {@link MetadataRegistrationService}
 * (persistence), with no DB, no Mockito — hand-rolled fakes throughout, this project's
 * convention. Proves the core product claim: "Apply Pack once to a connection; every object
 * analyzed on that connection afterward — including ones discovered later — automatically
 * receives pack_key/concept_key without a second Pack application."
 */
class ConnectionScopedPackSemanticAssignmentLifecycleTest {

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

        /** "Apply Pack ONCE to a connection" — the one-time configuration step. */
        void applyPackToConnection(String connectionKey, IndustryPack pack) {
            catalogue.put(pack.packId(), pack);
            activeByConnection.put(connectionKey, new TenantPack(pack.packId(), connectionKey, "1.0.0",
                    pack.displayName(), "ACTIVE", Map.of(), 1.0, null, "user@x.com"));
        }
    }

    static class FakeEntityCandidateService extends EntityCandidateService {
        FakeEntityCandidateService() { super(null); }
        @Override public List<Candidate> retrieve(String domainKey, String tableName) { return List.of(); }
        // MetadataRegistrationService.selectEntity always consults these (tier-0 lookup, then a
        // collision check on the explicit entityKey) before honoring any AI reuse decision —
        // both empty forces the simple CREATE path, which is all this test needs (it supplies
        // its own explicit, always-fresh entityKey per table).
        @Override public Optional<BusinessEntity> findBoundEntity(String objectKey) { return Optional.empty(); }
        @Override public Optional<BusinessEntity> findEntity(String entityKey) { return Optional.empty(); }
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

    /** Scripts one conceptResolution answer per table name, simulating the LLM's decision. */
    static class ScriptedAiClient extends AzureOpenAiClient {
        final Map<String, String> conceptByTable = new LinkedHashMap<>();

        ScriptedAiClient() { super(new ObjectMapper(), null); }

        @Override
        public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
            StringBuilder json = new StringBuilder("{\"tables\":[");
            String userMessage = messages.get(0).content();
            boolean first = true;
            for (String table : conceptByTable.keySet()) {
                if (!userMessage.contains("Table: " + table)) continue;
                if (!first) json.append(",");
                first = false;
                String conceptKey = conceptByTable.get(table);
                json.append("{\"table_name\":\"").append(table).append("\",\"category\":\"Transactions\"");
                if (conceptKey != null) {
                    json.append(",\"conceptResolution\":{\"conceptKey\":\"").append(conceptKey)
                        .append("\",\"confidence\":\"HIGH\",\"reason\":\"matched by columns\"}");
                }
                json.append("}");
            }
            json.append("]}");
            return json.toString();
        }
    }

    static class FakeEnterpriseMap extends EnterpriseMapService {
        final List<Map<String, Object>> objectRequests = new ArrayList<>();

        FakeEnterpriseMap() { super(null, null, null, null, null, null, null, null); }

        @Override
        public NexusConnection resolveConnection(String connectionKey) {
            return new NexusConnection(connectionKey, connectionKey, "POSTGRES", "", "", "", "", "",
                    "", "", true, null, null, null, "ACTIVE", Instant.now(), Instant.now());
        }
        @Override
        public DataObject createOrUpdateObject(Map<String, Object> request, String userEmail) {
            return createOrUpdateObject(request, userEmail, null);
        }
        @Override
        public DataObject createOrUpdateObject(Map<String, Object> request, String userEmail, NexusConnection connection) {
            objectRequests.add(request);
            String table = (String) request.get("tableName");
            return new DataObject("obj-" + table, (String) request.get("domainKey"),
                    (String) request.get("entityName"), (String) request.get("connectionKey"),
                    (String) request.get("schemaName"), table,
                    (String) request.get("businessName"), (String) request.get("purpose"),
                    "", "", "", "", "", "", "", 500, false, "SCANNED", 1, Instant.now(), Instant.now());
        }
    }

    static class FakeSemantic extends SemanticService {
        final List<Map<String, Object>> entityBodies = new ArrayList<>();
        final Map<String, Map<String, Object>> byEntityKey = new LinkedHashMap<>();

        FakeSemantic() { super(null, null, null); }

        @Override
        public BusinessEntity createOrUpdateEntity(Map<String, Object> body, String userEmail) {
            entityBodies.add(body);
            // Simulate the real UPSERT_ENTITY's COALESCE: an omitted packKey/conceptKey on a
            // later call must not erase a value a prior call already set for the same entity.
            String entityKey = (String) body.get("entityKey");
            Map<String, Object> merged = byEntityKey.computeIfAbsent(entityKey, k -> new LinkedHashMap<>());
            body.forEach((k, v) -> { if (v != null) merged.put(k, v); });
            return null;
        }
        @Override
        public OperationalVocabulary createTerm(Map<String, Object> body) { return null; }
    }

    private static PackEntity concept(String conceptKey, String name, List<String> aliases, String description) {
        return new PackEntity(name, aliases, List.of(), List.of(), description, "", conceptKey, null);
    }

    private static IndustryPack retailPack() {
        return new IndustryPack("retail-v1", "RETAIL", "Retail & E-commerce", "1.0.0", "desc",
                List.of(
                        concept("purchase-order", "Purchase Order", List.of("PO"), "Order placed with a supplier."),
                        concept("product", "Product", List.of("SKU", "Item"), "A sellable item.")),
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, null);
    }

    private FakeIndustryPackRepository packRepository;
    private ScriptedAiClient aiClient;
    private BusinessObjectBatchAnalyzer analyzer;
    private FakeEnterpriseMap enterpriseMap;
    private FakeSemantic semantic;
    private MetadataRegistrationService registration;

    @BeforeEach
    void setUp() {
        packRepository = new FakeIndustryPackRepository();
        aiClient = new ScriptedAiClient();
        analyzer = new BusinessObjectBatchAnalyzer(aiClient, new FakeDynamicSqlService(),
                new FakeEntityCandidateService(), new ObjectMapper(), packRepository);
        enterpriseMap = new FakeEnterpriseMap();
        semantic = new FakeSemantic();
        registration = new MetadataRegistrationService(enterpriseMap, semantic,
                new RelationshipDiscoveryService(null, null, null) {
                    @Override public int discoverAndPersist(String c, String s, String d) { return 0; }
                },
                new FakeEntityCandidateService(), null, packRepository);
    }

    /** Runs one table through the exact chain both Onboarding and Discover share: analyze, then
     *  register the (single, approved) result — mirroring MetadataRegistrationService's own
     *  documented request shape. */
    private void analyzeAndRegisterOneTable(String connectionKey, String tableName, String entityKey) {
        Map<String, Map<String, Object>> analyzed =
                analyzer.analyzeBatch(connectionKey, "public", "PLATFORM", List.of(tableName));
        Map<String, Object> analysis = analyzed.get(tableName);

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("approved", true);
        entity.put("tableName", tableName);
        entity.put("entityKey", entityKey);
        entity.put("entityName", (String) analysis.getOrDefault("entityName", tableName));
        entity.put("purpose", analysis.getOrDefault("purpose", ""));
        entity.put("category", analysis.get("category"));
        // This is exactly the field the existing frontend does NOT yet forward (see this task's
        // final report, "UI forwarding gap") — supplied directly here to prove the backend
        // mechanism end-to-end, the same way a direct API call would.
        if (analysis.containsKey("conceptKey")) entity.put("conceptKey", analysis.get("conceptKey"));

        Map<String, Object> request = Map.of(
                "connectionKey", connectionKey, "schemaName", "public", "domainKey", "PLATFORM",
                "entities", List.of(entity));
        registration.register(request, "user@x.com");
    }

    // ── Test 10 (mandatory) — new object after Pack application, no reapplication ──

    @Test
    void newObjectDiscoveredAfterPackApplicationAutomaticallyInheritsTheConnectionsPack() {
        // Step 1: Apply retail-v1 to Connection A ONCE.
        packRepository.applyPackToConnection("conn-A", retailPack());
        aiClient.conceptByTable.put("ord_hdr", "purchase-order");

        // Step 2-3: Analyze object A; verify pack_key + concept_key.
        analyzeAndRegisterOneTable("conn-A", "ord_hdr", "order-header");
        Map<String, Object> entityA = semantic.byEntityKey.get("order-header");
        assertEquals("retail-v1", entityA.get("packKey"));
        assertEquals("purchase-order", entityA.get("conceptKey"));

        // Step 4-6: A NEW object appears later. Run its normal analysis. Do NOT touch Packs.
        aiClient.conceptByTable.put("item_master", "product");
        analyzeAndRegisterOneTable("conn-A", "item_master", "product-master"); // no re-apply call anywhere

        // Step 7: object B automatically receives the SAME connection's pack + its own concept.
        Map<String, Object> entityB = semantic.byEntityKey.get("product-master");
        assertEquals("retail-v1", entityB.get("packKey"),
                "the new object must inherit the connection's pack with zero Pack re-application");
        assertEquals("product", entityB.get("conceptKey"));
    }

    // ── Test 11 — existing entity preservation across re-analysis ────────────────

    @Test
    void reAnalysisThatResolvesNoConceptDoesNotEraseAPreviouslyResolvedOne() {
        packRepository.applyPackToConnection("conn-A", retailPack());
        aiClient.conceptByTable.put("ord_hdr", "purchase-order");
        analyzeAndRegisterOneTable("conn-A", "ord_hdr", "order-header");
        assertEquals("purchase-order", semantic.byEntityKey.get("order-header").get("conceptKey"));

        // Re-analysis: this time the LLM resolves nothing for the same table (e.g. schema noise).
        aiClient.conceptByTable.put("ord_hdr", null);
        analyzeAndRegisterOneTable("conn-A", "ord_hdr", "order-header");

        assertEquals("purchase-order", semantic.byEntityKey.get("order-header").get("conceptKey"),
                "a later analysis that resolves nothing must never erase a previously valid concept_key "
                        + "(mirrors the real UPSERT_ENTITY COALESCE this fake reproduces)");
        assertEquals("retail-v1", semantic.byEntityKey.get("order-header").get("packKey"),
                "pack_key likewise must never be erased by omission");
    }

    // ── Test 12 — multi-connection isolation through the full chain ──────────────

    @Test
    void twoConnectionsOnTheSameTenantResolveIndependentConceptsForTheSamePhysicalTableName() {
        IndustryPack logisticsPack = new IndustryPack("logistics-v1", "LOGISTICS", "Logistics & Supply Chain",
                "1.0.0", "desc",
                List.of(concept("shipment-order", "Shipment Order", List.of(), "An order to ship goods.")),
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, null);
        packRepository.applyPackToConnection("conn-A", retailPack());
        packRepository.applyPackToConnection("conn-B", logisticsPack);
        aiClient.conceptByTable.put("orders", "purchase-order"); // conn-A's answer for "orders"

        analyzeAndRegisterOneTable("conn-A", "orders", "orders-a");
        assertEquals("retail-v1", semantic.byEntityKey.get("orders-a").get("packKey"));
        assertEquals("purchase-order", semantic.byEntityKey.get("orders-a").get("conceptKey"));

        // Same physical table name "orders", different connection, different pack.
        aiClient.conceptByTable.put("orders", "shipment-order"); // conn-B's answer for "orders"
        analyzeAndRegisterOneTable("conn-B", "orders", "orders-b");
        assertEquals("logistics-v1", semantic.byEntityKey.get("orders-b").get("packKey"));
        assertEquals("shipment-order", semantic.byEntityKey.get("orders-b").get("conceptKey"));

        // The two entities never contaminate each other.
        assertEquals("purchase-order", semantic.byEntityKey.get("orders-a").get("conceptKey"),
                "conn-A's entity must be unaffected by conn-B's later, different resolution");
    }
}
