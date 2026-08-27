package com.sei.nexus.onboarding;

import com.sei.nexus.connection.NexusConnection;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapService;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.EntityCandidateService;
import com.sei.nexus.semantic.OperationalVocabulary;
import com.sei.nexus.semantic.RelationshipDiscoveryService;
import com.sei.nexus.semantic.SemanticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-21 — the canonical Metadata Registration Pipeline. Exercised with
 * hand-rolled fakes (no DB, repo convention): physical registration first,
 * semantic registration linked to it, vocabulary linked to the entity,
 * relationship discovery once per batch, and the corrected failure semantics
 * (no unlinked entity when the data object fails).
 */
class MetadataRegistrationServiceTest {

    // ── fakes ────────────────────────────────────────────────────────────────

    static class FakeEnterpriseMap extends EnterpriseMapService {
        final List<Map<String, Object>> objectRequests = new ArrayList<>();
        final Set<String> failTables = new HashSet<>();
        int resolveConnectionCalls = 0;

        FakeEnterpriseMap() { super(null, null, null, null, null, null, null, null); }

        // Optimization B: MetadataRegistrationService now resolves the connection
        // once via this method instead of once per entity inside
        // createOrUpdateObject — faked here rather than hitting the (null)
        // connectionRepository the real implementation would use.
        @Override
        public NexusConnection resolveConnection(String connectionKey) {
            resolveConnectionCalls++;
            return new NexusConnection(connectionKey, connectionKey, "POSTGRES", "", "", "", "", "",
                    "", "", true, null, null, null, "ACTIVE", Instant.now(), Instant.now());
        }

        @Override
        public DataObject createOrUpdateObject(Map<String, Object> request, String userEmail) {
            return createOrUpdateObject(request, userEmail, null);
        }

        @Override
        public DataObject createOrUpdateObject(Map<String, Object> request, String userEmail,
                                                NexusConnection connection) {
            objectRequests.add(request);
            String table = (String) request.get("tableName");
            if (failTables.contains(table)) {
                throw new RuntimeException("allow-list rejection for " + table);
            }
            return new DataObject("obj-" + table, (String) request.get("domainKey"),
                    (String) request.get("entityName"), (String) request.get("connectionKey"),
                    (String) request.get("schemaName"), table,
                    (String) request.get("businessName"), (String) request.get("purpose"),
                    "", "", "", "", "", "", "",
                    500, false, "SCANNED", 1, Instant.now(), Instant.now());
        }
    }

    static class FakeSemantic extends SemanticService {
        final List<Map<String, Object>> entityBodies = new ArrayList<>();
        final List<Map<String, Object>> termBodies   = new ArrayList<>();

        FakeSemantic() { super(null, null, null); }

        @Override
        public BusinessEntity createOrUpdateEntity(Map<String, Object> body, String userEmail) {
            entityBodies.add(body);
            return null;
        }

        @Override
        public OperationalVocabulary createTerm(Map<String, Object> body) {
            termBodies.add(body);
            return null;
        }
    }

    static class FakeDiscovery extends RelationshipDiscoveryService {
        final List<String[]> calls = new ArrayList<>();
        int relationships = 4;

        FakeDiscovery() { super(null, null, null); }

        @Override
        public int discoverAndPersist(String connectionKey, String schemaName, String domainKey) {
            calls.add(new String[]{connectionKey, schemaName, domainKey});
            return relationships;
        }
    }

    static class FakeCandidates extends EntityCandidateService {
        final Map<String, BusinessEntity> boundByObjectKey = new HashMap<>();
        final Map<String, BusinessEntity> entitiesByKey    = new HashMap<>();
        List<Candidate> offered = List.of();
        int findEntityCalls = 0;

        FakeCandidates() { super(null); }

        @Override public Optional<BusinessEntity> findBoundEntity(String objectKey) {
            return Optional.ofNullable(boundByObjectKey.get(objectKey));
        }
        @Override public Optional<BusinessEntity> findEntity(String entityKey) {
            findEntityCalls++;
            return Optional.ofNullable(entitiesByKey.get(entityKey));
        }
        @Override public List<Candidate> retrieve(String domainKey, String tableName) {
            return offered;
        }
    }

    /** Connection-Scoped Industry Pack Semantic Assignment: maps connectionKey -> the active
     *  TenantPack, exactly the shape {@code IndustryPackRepository.findActivePackForConnection}
     *  already returns in production. */
    static class FakeIndustryPackRepository extends IndustryPackRepository {
        final Map<String, TenantPack> activeByConnection = new HashMap<>();

        FakeIndustryPackRepository() { super(null, new com.fasterxml.jackson.databind.ObjectMapper()); }

        @Override
        public Optional<TenantPack> findActivePackForConnection(String connectionKey) {
            return Optional.ofNullable(activeByConnection.get(connectionKey));
        }

        void assign(String connectionKey, String packKey) {
            activeByConnection.put(connectionKey, new TenantPack(packKey, connectionKey, "1.0.0",
                    packKey, "ACTIVE", Map.of(), 1.0, null, "user@x.com"));
        }
    }

    private static BusinessEntity entity(String key, String name, String primaryObjectKey, String status) {
        return new BusinessEntity(key, "PLATFORM", name, "desc", primaryObjectKey,
                "", "", status, "user@x.com", Instant.now(), Instant.now());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private FakeEnterpriseMap enterpriseMap;
    private FakeSemantic semantic;
    private FakeDiscovery discovery;
    private FakeCandidates candidates;
    private MetadataRegistrationService service;

    @BeforeEach
    void setUp() {
        enterpriseMap = new FakeEnterpriseMap();
        semantic      = new FakeSemantic();
        discovery     = new FakeDiscovery();
        candidates    = new FakeCandidates();
        service       = new MetadataRegistrationService(enterpriseMap, semantic, discovery, candidates);
    }

    private static Map<String, Object> storesEntity() {
        return new java.util.LinkedHashMap<>(Map.of(
                "approved", true,
                "tableName", "stores",
                "entityKey", "store",
                "entityName", "Store",
                "purpose", "Physical retail locations",
                "operationalMeaning", "Where sales happen",
                "investigationHints", "Filter by state_province",
                "vocabulary", List.of(Map.of(
                        "approved", true, "term", "outlet",
                        "definition", "a store", "sqlEquivalent", ""))));
    }

    private static Map<String, Object> suppliersEntity() {
        return new java.util.LinkedHashMap<>(Map.of(
                "approved", true,
                "tableName", "suppliers",
                "entityKey", "supplier",
                "entityName", "Supplier",
                "purpose", "External vendors",
                "vocabulary", List.of(Map.of(
                        "approved", true, "term", "vendor",
                        "definition", "a supplier", "sqlEquivalent", "approval_status = 'approved'"))));
    }

    @SafeVarargs
    private static Map<String, Object> request(Map<String, Object>... entities) {
        return Map.of(
                "connectionKey", "conn-1",
                "schemaName", "retail_core",
                "domainKey", "PLATFORM",
                "entities", List.of(entities));
    }

    // ── the canonical chain ──────────────────────────────────────────────────

    @Test
    void registersPhysicalThenLinkedSemanticThenBatchRelationships() {
        var result = service.register(request(storesEntity(), suppliersEntity()), "user@x.com");

        // Physical registration for every approved table
        assertEquals(2, enterpriseMap.objectRequests.size());
        Map<String, Object> obj = enterpriseMap.objectRequests.get(0);
        assertEquals("PLATFORM",    obj.get("domainKey"));
        assertEquals("conn-1",      obj.get("connectionKey"));
        assertEquals("retail_core", obj.get("schemaName"));
        assertEquals("stores",      obj.get("tableName"));
        assertEquals("Stores",      obj.get("businessName"), "wizard default: entityName + 's'");

        // Entities linked to the data objects the same batch created
        assertEquals(2, semantic.entityBodies.size());
        assertEquals("obj-stores",    semantic.entityBodies.get(0).get("primaryObjectKey"));
        assertEquals("obj-suppliers", semantic.entityBodies.get(1).get("primaryObjectKey"));

        // Vocabulary linked to its entity, term keys scoped per entity
        assertEquals(2, semantic.termBodies.size());
        Map<String, Object> outlet = semantic.termBodies.get(0);
        assertEquals("store",        outlet.get("entityKey"));
        assertEquals("outlet-store", outlet.get("termKey"));
        Map<String, Object> vendor = semantic.termBodies.get(1);
        assertEquals("supplier",        vendor.get("entityKey"));
        assertEquals("vendor-supplier", vendor.get("termKey"));

        // Relationship discovery once, after the whole batch, with the batch scope
        assertEquals(1, discovery.calls.size());
        assertArrayEquals(new String[]{"conn-1", "retail_core", "PLATFORM"}, discovery.calls.get(0));

        assertEquals(2, result.objectsCreated());
        assertEquals(2, result.entitiesCreated());
        assertEquals(2, result.vocabCreated());
        assertEquals(4, result.relationshipsDiscovered());
        assertTrue(result.failures().isEmpty());
    }

    // ── corrected failure semantics ──────────────────────────────────────────

    @Test
    void objectFailureSkipsSemanticRegistrationForThatTableOnly() {
        enterpriseMap.failTables.add("stores");

        var result = service.register(request(storesEntity(), suppliersEntity()), "user@x.com");

        // No unlinked entity: stores never reaches semantic registration
        assertEquals(1, semantic.entityBodies.size());
        assertEquals("supplier", semantic.entityBodies.get(0).get("entityKey"));
        assertEquals(1, semantic.termBodies.size(), "stores vocabulary skipped too");

        // Failure recorded; the rest of the batch still processed
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).startsWith("data object stores:"));
        assertEquals(1, result.objectsCreated());
        assertEquals(1, result.entitiesCreated());
        assertEquals(1, discovery.calls.size(), "relationship discovery still runs for the batch");
    }

    // ── approval gating ──────────────────────────────────────────────────────

    @Test
    void unapprovedEntitiesAndTermsAreSkipped() {
        Map<String, Object> rejectedEntity = new java.util.LinkedHashMap<>(storesEntity());
        rejectedEntity.put("approved", false);

        Map<String, Object> entityWithRejectedTerm = suppliersEntity();
        entityWithRejectedTerm.put("vocabulary", List.of(
                Map.of("approved", false, "term", "vendor", "definition", "x")));

        var result = service.register(request(rejectedEntity, entityWithRejectedTerm), "user@x.com");

        assertEquals(1, enterpriseMap.objectRequests.size());
        assertEquals("suppliers", enterpriseMap.objectRequests.get(0).get("tableName"));
        assertEquals(0, result.vocabCreated());
        assertTrue(semantic.termBodies.isEmpty());
    }

    // ── AI data-object fields pass through to their existing consumer ────────

    @Test
    void columnRolesAndGuidancePassThroughToDataObjectRequest() {
        Map<String, Object> entity = storesEntity();
        entity.put("identifierColumns", List.of("id", "store_code"));
        entity.put("statusColumns", "status");                      // already-joined form
        entity.put("usageGuidance", "Join to regions for rollups");
        entity.put("businessName", "Retail Stores");

        service.register(request(entity), "user@x.com");

        Map<String, Object> obj = enterpriseMap.objectRequests.get(0);
        assertEquals("id,store_code",              obj.get("identifierColumns"), "list joined to CSV");
        assertEquals("status",                     obj.get("statusColumns"));
        assertEquals("Join to regions for rollups", obj.get("usageGuidance"));
        assertEquals("Retail Stores",              obj.get("businessName"), "explicit businessName wins");
        assertFalse(obj.containsKey("safeFilterColumns"), "absent fields stay absent");
        assertFalse(obj.containsKey("avoidGuidance"));
    }

    // ── Grouping Foundation Fix: category → group_label ──────────────────────
    // Every table passes through this one register() call regardless of whether it
    // was AI-recommended or added via Browse All — there is no separate path to test.

    @Test
    void analyzedCategoryFlowsThroughAsGroupLabel() {
        Map<String, Object> entity = storesEntity();
        entity.put("category", "Operations");

        service.register(request(entity), "user@x.com");

        assertEquals("Operations", semantic.entityBodies.get(0).get("groupLabel"));
    }

    @Test
    void missingCategoryOmitsGroupLabelRatherThanErasingIt() {
        // No "category" key at all — mirrors a malformed/partial analysis result.
        // Must be OMITTED from the entity body (not sent as ""), so the repository's
        // COALESCE preserves any existing group_label instead of nulling it out.
        service.register(request(storesEntity()), "user@x.com");

        assertFalse(semantic.entityBodies.get(0).containsKey("groupLabel"),
                "absent category must stay absent, never sent as a blank/erasing value");
    }

    @Test
    void blankCategoryAlsoOmitsGroupLabel() {
        Map<String, Object> entity = storesEntity();
        entity.put("category", "   ");

        service.register(request(entity), "user@x.com");

        assertFalse(semantic.entityBodies.get(0).containsKey("groupLabel"));
    }

    @Test
    void differentEntitiesInTheSameBatchCanHaveDifferentCategories() {
        Map<String, Object> stores = storesEntity();
        stores.put("category", "Operations");
        Map<String, Object> suppliers = suppliersEntity();
        suppliers.put("category", "Procurement");

        service.register(request(stores, suppliers), "user@x.com");

        assertEquals("Operations",  semantic.entityBodies.get(0).get("groupLabel"));
        assertEquals("Procurement", semantic.entityBodies.get(1).get("groupLabel"));
    }

    /**
     * Regression test for the real Discover-from-DB defect: {@code Semantic.jsx}'s own
     * {@code saveApproved()} apply payload never read/forwarded {@code category} at all — a
     * gap distinct from (and never fixed by) the Onboarding Wizard's {@code category} threading,
     * since Discover has its own, separate frontend state. This exercises the EXACT request
     * shape {@code Semantic.jsx} now sends after the fix (identical field names: tableName,
     * entityKey, entityName, purpose, operationalMeaning, investigationHints, category,
     * businessName, identifierColumns, vocabulary, entityResolution — see
     * {@code Semantic.jsx}'s {@code saveApproved()}), proving the boundary that actually broke
     * — not just the Onboarding-shaped payload the earlier grouping tests already covered.
     */
    @Test
    void discoverShapedApplyPayloadCarriesCategoryThroughToGroupLabel() {
        Map<String, Object> discoverEntity = new java.util.LinkedHashMap<>();
        discoverEntity.put("approved", true);
        discoverEntity.put("tableName", "suppliers");
        discoverEntity.put("entityKey", "supplier");
        discoverEntity.put("entityName", "Supplier");
        discoverEntity.put("purpose", "External vendors");
        discoverEntity.put("operationalMeaning", "");
        discoverEntity.put("investigationHints", "");
        discoverEntity.put("category", "Procurement"); // the field Semantic.jsx now forwards
        discoverEntity.put("businessName", "Suppliers");
        discoverEntity.put("identifierColumns", List.of("id", "code"));
        discoverEntity.put("vocabulary", List.of());

        service.register(request(discoverEntity), "user@x.com");

        assertEquals("Procurement", semantic.entityBodies.get(0).get("groupLabel"),
                "a Discover-shaped apply payload carrying category must reach groupLabel exactly "
                        + "like an Onboarding-shaped one does");
    }

    // ── defaults and edge behavior ───────────────────────────────────────────

    @Test
    void entityKeyAndNameDefaultFromTableName() {
        Map<String, Object> minimal = new java.util.LinkedHashMap<>(Map.of(
                "approved", true, "tableName", "store_targets"));

        service.register(request(minimal), "user@x.com");

        assertEquals("store-targets", semantic.entityBodies.get(0).get("entityKey"));
        assertEquals("Store Targets", semantic.entityBodies.get(0).get("entityName"));
    }

    @Test
    void missingConnectionKeySkipsRelationshipDiscovery() {
        var result = service.register(Map.of(
                "schemaName", "retail_core", "domainKey", "PLATFORM",
                "entities", List.of(storesEntity())), "user@x.com");

        assertTrue(discovery.calls.isEmpty());
        assertEquals(0, result.relationshipsDiscovered());
    }

    @Test
    void schemaDefaultsToPublicForRelationshipDiscovery() {
        java.util.Map<String, Object> req = new java.util.LinkedHashMap<>();
        req.put("connectionKey", "conn-1");
        req.put("domainKey", "PLATFORM");
        req.put("entities", List.of(storesEntity()));

        service.register(req, "user@x.com");

        assertEquals("public", discovery.calls.get(0)[1]);
    }

    // ── Business Entity selection (PRO-22) ───────────────────────────────────

    @Test
    void tier0_reonboardingBoundTableReusesExistingEntityDespiteNamingDrift() {
        // The proven incident: the table is already bound to the curated entity
        // "store", but the AI drafted the drifted name/key "stores".
        candidates.boundByObjectKey.put("obj-stores", entity("store", "Store", "obj-stores", "ACTIVE"));
        Map<String, Object> drifted = storesEntity();
        drifted.put("entityKey", "stores");
        drifted.put("entityName", "stores");

        var result = service.register(request(drifted), "user@x.com");

        Map<String, Object> body = semantic.entityBodies.get(0);
        assertEquals("store", body.get("entityKey"), "existing identity reused — no duplicate");
        assertEquals("Store", body.get("entityName"), "curated name preserved against drift");
        assertEquals("obj-stores", body.get("primaryObjectKey"));
        assertEquals("outlet-store", semantic.termBodies.get(0).get("termKey"),
                "vocabulary attaches to the reused entity");
        assertTrue(result.failures().isEmpty());
    }

    @Test
    void tier2_validatedReuseFillsEmptyBinding() {
        // Pack/manual entity with no table binding; AI decides the new "vendors"
        // table is the same concept, from the offered candidate set.
        candidates.offered = List.of(new EntityCandidateService.Candidate(
                "supplier-concept", "Supplier", null, List.of("vendor"), "External vendors"));
        candidates.entitiesByKey.put("supplier-concept",
                entity("supplier-concept", "Supplier", null, "ACTIVE"));

        Map<String, Object> draft = new java.util.LinkedHashMap<>(Map.of(
                "approved", true, "tableName", "vendors",
                "entityKey", "vendor", "entityName", "Vendor",
                "entityResolution", Map.of("decision", "REUSE",
                        "entityKey", "supplier-concept", "confidence", 0.9)));

        var result = service.register(request(draft), "user@x.com");

        Map<String, Object> body = semantic.entityBodies.get(0);
        assertEquals("supplier-concept", body.get("entityKey"));
        assertEquals("Supplier", body.get("entityName"), "existing name preserved on reuse");
        assertEquals("obj-vendors", body.get("primaryObjectKey"), "empty binding filled");
        assertTrue(result.failures().isEmpty());
        // Optimization C: validateReuse() and selectEntity() used to each fetch the
        // same entity independently (2 calls for one reuse decision) — now exactly 1.
        assertEquals(1, candidates.findEntityCalls,
                "the entity fetched during validation must be reused, not re-queried");
    }

    @Test
    void tier2_hallucinatedKeyOutsideOfferedSetFallsBackToCreate() {
        candidates.offered = List.of();   // nothing was offered
        Map<String, Object> draft = storesEntity();
        draft.put("entityResolution", Map.of("decision", "REUSE",
                "entityKey", "ghost-entity", "confidence", 0.95));

        var result = service.register(request(draft), "user@x.com");

        assertEquals("store", semantic.entityBodies.get(0).get("entityKey"),
                "falls back to the drafted CREATE identity");
        assertTrue(result.failures().stream()
                .anyMatch(f -> f.contains("not in the offered candidate set")));
    }

    @Test
    void tier2_lowConfidenceReuseFallsBackToCreate() {
        candidates.offered = List.of(new EntityCandidateService.Candidate(
                "store", "Store", null, List.of(), ""));
        candidates.entitiesByKey.put("store", entity("store", "Store", null, "ACTIVE"));
        Map<String, Object> draft = storesEntity();
        draft.put("entityKey", "retail-store");
        draft.put("entityResolution", Map.of("decision", "REUSE",
                "entityKey", "store", "confidence", 0.3));

        var result = service.register(request(draft), "user@x.com");

        assertEquals("retail-store", semantic.entityBodies.get(0).get("entityKey"));
        assertTrue(result.failures().stream().anyMatch(f -> f.contains("below")));
    }

    @Test
    void tier2_archivedEntityRejected() {
        candidates.offered = List.of(new EntityCandidateService.Candidate(
                "store", "Store", null, List.of(), ""));
        candidates.entitiesByKey.put("store", entity("store", "Store", null, "ARCHIVED"));
        Map<String, Object> draft = storesEntity();
        draft.put("entityKey", "retail-store");
        draft.put("entityResolution", Map.of("decision", "REUSE",
                "entityKey", "store", "confidence", 0.9));

        var result = service.register(request(draft), "user@x.com");

        assertEquals("retail-store", semantic.entityBodies.get(0).get("entityKey"));
        assertTrue(result.failures().stream().anyMatch(f -> f.contains("archived")));
    }

    @Test
    void tier2_bindingConflictNeverRebinds() {
        // Candidate is already bound to a DIFFERENT table — reuse would silently
        // rebind the concept; must degrade to CREATE instead.
        candidates.offered = List.of(new EntityCandidateService.Candidate(
                "store", "Store", "other_table", List.of(), ""));
        candidates.entitiesByKey.put("store", entity("store", "Store", "obj-other", "ACTIVE"));
        Map<String, Object> draft = storesEntity();
        draft.put("entityKey", "retail-store");
        draft.put("entityResolution", Map.of("decision", "REUSE",
                "entityKey", "store", "confidence", 0.9));

        var result = service.register(request(draft), "user@x.com");

        assertEquals("retail-store", semantic.entityBodies.get(0).get("entityKey"));
        assertTrue(result.failures().stream().anyMatch(f -> f.contains("refusing to rebind")));
    }

    @Test
    void create_slugCollisionWithDifferentlyBoundEntitySuffixesInsteadOfMerging() {
        // Drafted key "store" already names an entity bound to another table —
        // the old ON CONFLICT upsert would have silently merged two concepts.
        candidates.entitiesByKey.put("store", entity("store", "Store", "obj-other", "ACTIVE"));

        var result = service.register(request(storesEntity()), "user@x.com");

        assertEquals("store-2", semantic.entityBodies.get(0).get("entityKey"));
        assertEquals("outlet-store-2", semantic.termBodies.get(0).get("termKey"));
        assertTrue(result.failures().stream().anyMatch(f -> f.contains("collision")));
    }

    @Test
    void create_sameSlugUnboundEntityStillMergesAsToday() {
        // Colliding with an UNBOUND entity (e.g. pack-created) keeps today's
        // upsert-merge behavior — and the merge fills the missing binding.
        candidates.entitiesByKey.put("store", entity("store", "Store", null, "ACTIVE"));

        var result = service.register(request(storesEntity()), "user@x.com");

        assertEquals("store", semantic.entityBodies.get(0).get("entityKey"));
        assertEquals("obj-stores", semantic.entityBodies.get(0).get("primaryObjectKey"));
        assertTrue(result.failures().isEmpty());
    }

    // ── Optimization B (onboarding performance investigation) ───────────────

    @Test
    void connectionIsResolvedOnceForAMultiEntityApply() {
        var result = service.register(request(storesEntity(), suppliersEntity()), "user@x.com");

        assertEquals(1, enterpriseMap.resolveConnectionCalls,
                "one connectionKey shared by every entity in the batch — resolve it once, not per entity");
        assertEquals(2, result.objectsCreated(), "both entities still register normally");
    }

    @Test
    void connectionResolutionFailureIsReportedPerEntityLikeBefore() {
        var failing = new FakeEnterpriseMap() {
            @Override public NexusConnection resolveConnection(String connectionKey) {
                resolveConnectionCalls++;
                throw new RuntimeException("Connection not found: " + connectionKey);
            }
        };
        service = new MetadataRegistrationService(failing, semantic, discovery, candidates);

        var result = service.register(request(storesEntity(), suppliersEntity()), "user@x.com");

        assertEquals(1, failing.resolveConnectionCalls, "resolved once even though it fails");
        assertEquals(0, result.objectsCreated());
        assertEquals(2, result.failures().size(), "every entity in the batch still gets its own failure message");
        assertTrue(result.failures().get(0).startsWith("data object stores: Connection not found"));
        assertTrue(result.failures().get(1).startsWith("data object suppliers: Connection not found"));
    }

    @Test
    void discoveryFailureIsNonFatalAndRecorded() {
        var failing = new FakeDiscovery() {
            @Override public int discoverAndPersist(String c, String s, String d) {
                throw new RuntimeException("fk scan failed");
            }
        };
        service = new MetadataRegistrationService(enterpriseMap, semantic, failing, candidates);

        var result = service.register(request(storesEntity()), "user@x.com");

        assertEquals(1, result.entitiesCreated(), "registration completed despite discovery failure");
        assertTrue(result.failures().stream().anyMatch(f -> f.startsWith("relationship discovery:")));
    }

    // ── Connection-Scoped Industry Pack Semantic Assignment ──────────────────────

    @Test
    void packKeyIsDerivedFromTheConnectionsActivePackNeverFromTheRequest() {
        FakeIndustryPackRepository packRepository = new FakeIndustryPackRepository();
        packRepository.assign("conn-1", "retail-v1");
        service = new MetadataRegistrationService(enterpriseMap, semantic, discovery, candidates, null, packRepository);

        service.register(request(storesEntity()), "user@x.com");

        assertEquals(1, semantic.entityBodies.size());
        assertEquals("retail-v1", semantic.entityBodies.get(0).get("packKey"),
                "pack_key comes from the connection's active pack — never supplied by the request itself");
    }

    @Test
    void noActivePackMeansNoPackKeyIsWritten() {
        FakeIndustryPackRepository packRepository = new FakeIndustryPackRepository(); // nothing assigned
        service = new MetadataRegistrationService(enterpriseMap, semantic, discovery, candidates, null, packRepository);

        service.register(request(storesEntity()), "user@x.com");

        assertFalse(semantic.entityBodies.get(0).containsKey("packKey"),
                "no active pack for this connection -> packKey must be omitted, not written as null/blank");
    }

    @Test
    void conceptKeyFlowsThroughWhenTheRequestSuppliesOne() {
        // Simulates BusinessObjectBatchAnalyzer's validated conceptResolution having already
        // flowed through the draft/review step into the entity the caller submits.
        Map<String, Object> withConcept = new java.util.LinkedHashMap<>(storesEntity());
        withConcept.put("conceptKey", "store");

        service.register(request(withConcept), "user@x.com");

        assertEquals("store", semantic.entityBodies.get(0).get("conceptKey"));
    }

    @Test
    void absentConceptKeyIsOmittedNotNulled() {
        service.register(request(storesEntity()), "user@x.com"); // no conceptKey in the request

        assertFalse(semantic.entityBodies.get(0).containsKey("conceptKey"),
                "an analysis that resolved no concept must omit the field so the existing " +
                "COALESCE preserves whatever concept_key the entity already has — never write null explicitly");
    }

    @Test
    void multipleConnectionsResolveIndependentPacksInTheSameBatchCall() {
        FakeIndustryPackRepository packRepository = new FakeIndustryPackRepository();
        packRepository.assign("conn-1", "retail-v1");
        packRepository.assign("conn-2", "logistics-v1");
        service = new MetadataRegistrationService(enterpriseMap, semantic, discovery, candidates, null, packRepository);

        service.register(request(storesEntity()), "user@x.com"); // request() hardcodes connectionKey=conn-1
        assertEquals("retail-v1", semantic.entityBodies.get(0).get("packKey"));

        Map<String, Object> req2 = Map.of("connectionKey", "conn-2", "schemaName", "logistics_core",
                "domainKey", "PLATFORM", "entities", List.of(suppliersEntity()));
        service.register(req2, "user@x.com");
        assertEquals("logistics-v1", semantic.entityBodies.get(1).get("packKey"),
                "a different connection in a separate call must resolve its own pack, not conn-1's");
    }
}
