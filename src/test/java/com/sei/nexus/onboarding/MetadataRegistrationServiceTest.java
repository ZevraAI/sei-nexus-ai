package com.sei.nexus.onboarding;

import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapService;
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

        FakeEnterpriseMap() { super(null, null, null, null, null, null, null); }

        @Override
        public DataObject createOrUpdateObject(Map<String, Object> request, String userEmail) {
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

        FakeCandidates() { super(null); }

        @Override public Optional<BusinessEntity> findBoundEntity(String objectKey) {
            return Optional.ofNullable(boundByObjectKey.get(objectKey));
        }
        @Override public Optional<BusinessEntity> findEntity(String entityKey) {
            return Optional.ofNullable(entitiesByKey.get(entityKey));
        }
        @Override public List<Candidate> retrieve(String domainKey, String tableName) {
            return offered;
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
}
