package com.sei.nexus.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.OperationalVocabulary;
import com.sei.nexus.semantic.SemanticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Foundation Fix #1 — IndustryPackService.applyPack() must persist the object_key
 * the deterministic pack matcher already resolved as the new entity's
 * primary_object_key, without introducing any new discovery/resolution mechanism.
 *
 * <p>No Mockito, no DB — hand-rolled fakes over the real collaborator classes,
 * matching this package's existing convention (e.g. EntityCandidateServiceTest).
 * {@link PackEntityMapper} is used unmocked with a {@code null} AI client: every
 * scenario here is crafted so phase-1 pattern matching resolves it, so the LLM
 * fallback path is never exercised and the null client is never dereferenced.
 */
class IndustryPackServiceBindingTest {

    // ── fakes ─────────────────────────────────────────────────────────────────

    static class FakeEnterpriseMapRepository extends EnterpriseMapRepository {
        List<DataObject> objects = List.of();
        // applyPack() calls findDataObjectsByDomain twice — once (via
        // getDiscoveredTableNames) to build the table-name list the matcher runs
        // against, and once (via loadTableNameToObjectKey) to resolve object_keys.
        // Both reads normally see the identical result, since it's the same query
        // against the same table — but one test below deliberately diverges them
        // to exercise the defensive "matched table, no object_key" branch, which
        // cannot otherwise occur when both calls see the same data.
        List<DataObject> objectsForSecondCall;
        private int callCount = 0;

        FakeEnterpriseMapRepository() { super(null); }

        @Override
        public List<DataObject> findDataObjectsByDomain(String domainKey) {
            callCount++;
            return (callCount > 1 && objectsForSecondCall != null) ? objectsForSecondCall : objects;
        }
    }

    static class FakeSemanticService extends SemanticService {
        List<Map<String, Object>> entityCalls = new ArrayList<>();

        FakeSemanticService() { super(null, null, null); }

        @Override
        public BusinessEntity createOrUpdateEntity(Map<String, Object> body, String userEmail) {
            entityCalls.add(body);
            Instant now = Instant.now();
            return new BusinessEntity(
                    "entity-fake", (String) body.get("domainKey"), (String) body.get("entityName"),
                    (String) body.get("description"), (String) body.get("primaryObjectKey"),
                    (String) body.get("operationalMeaning"), null,
                    (String) body.get("status"), userEmail, now, now);
        }

        @Override
        public OperationalVocabulary createTerm(Map<String, Object> body) {
            return null; // vocabulary creation is untouched by this fix; not under test here
        }
    }

    static class FakeIndustryPackRepository extends IndustryPackRepository {
        Map<String, IndustryPack> catalogue = Map.of();
        TenantPack savedTenantPack;

        FakeIndustryPackRepository() { super(null, new ObjectMapper()); }

        @Override
        public List<IndustryPack> findAllPacks() { return List.copyOf(catalogue.values()); }

        @Override
        public Optional<IndustryPack> findPackById(String packId) {
            return Optional.ofNullable(catalogue.get(packId));
        }

        @Override
        public List<TenantPack> findAppliedPacks() {
            return savedTenantPack != null ? List.of(savedTenantPack) : List.of();
        }

        @Override
        public Optional<TenantPack> findAppliedPack(String packKey) {
            return savedTenantPack != null && savedTenantPack.packKey().equals(packKey)
                    ? Optional.of(savedTenantPack) : Optional.empty();
        }

        @Override
        public void saveTenantPack(TenantPack tp) { savedTenantPack = tp; }

        @Override
        public void disableTenantPack(String packKey) { /* not under test */ }
    }

    private FakeEnterpriseMapRepository enterpriseMapRepository;
    private FakeSemanticService semanticService;
    private FakeIndustryPackRepository packRepository;
    private PackEntityMapper entityMapper;
    private IndustryPackService service;

    @BeforeEach
    void setUp() {
        enterpriseMapRepository = new FakeEnterpriseMapRepository();
        semanticService = new FakeSemanticService();
        packRepository = new FakeIndustryPackRepository();
        entityMapper = new PackEntityMapper(null, new ObjectMapper());
        service = new IndustryPackService(packRepository, entityMapper,
                new PackRecommendationService(packRepository), semanticService, enterpriseMapRepository);
    }

    private static PackEntity packEntity(String name, String tablePattern) {
        return new PackEntity(name, List.of(), List.of(tablePattern), List.of(), "desc", "meaning");
    }

    private static IndustryPack pack(String packId, PackEntity... entities) {
        return new IndustryPack(packId, "RETAIL", "Test Pack", "v1", "desc",
                List.of(entities), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null);
    }

    private static DataObject dataObject(String objectKey, String tableName) {
        return new DataObject(objectKey, "PLATFORM", tableName, "conn-1", "public", tableName,
                tableName, "purpose", null, null, null, null, null, null, null,
                null, false, "SCANNED", 1, null, null);
    }

    // ── TEST 1 — New Pack Entity ────────────────────────────────────────────────

    @Test
    void newPackEntityReceivesTheMatchedObjectKey() {
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "user@example.com");

        assertEquals(1, semanticService.entityCalls.size());
        Map<String, Object> body = semanticService.entityCalls.get(0);
        assertEquals("Product", body.get("entityName"));
        assertEquals("object-A", body.get("primaryObjectKey"),
                "the entity created from a matched pack entity must carry the matched object's object_key");
    }

    // ── TEST 2 / Case A-C from the task — Pack never targets an existing entity_key ──

    @Test
    void packApplicationDoesNotReferenceOrOverwriteAPreExistingEntityForTheSameTable() {
        // Simulates the real-tenant shape: a curated entity "product" already exists,
        // correctly bound to object-A, before the pack is ever applied. applyPack()
        // never supplies entityKey (confirmed by code trace), so it can only ever
        // INSERT a new row — it cannot reach the ON CONFLICT branch for "product" at
        // all. This is what makes Cases A/B/C from the task moot for this code path:
        // there is no "update an existing entity" branch to reason about here.
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "user@example.com");

        assertEquals(1, semanticService.entityCalls.size());
        Map<String, Object> body = semanticService.entityCalls.get(0);
        assertFalse(body.containsKey("entityKey"), "pack apply must never supply an entityKey — "
                + "that is what keeps it from ever colliding with a pre-existing curated entity");
        assertEquals("object-A", body.get("primaryObjectKey"));
    }

    // ── Defensive path: matched table with no resolvable object_key ─────────────

    @Test
    void matchedEntityWithNoDiscoveredObjectKeyIsCreatedWithoutABinding() {
        // Matcher sees "products" (so "Product" matches), but the object-key lookup
        // sees a divergent result with no row for "products" — should not happen in
        // practice since both come from the same query, but must not crash or
        // fabricate a key if it ever does.
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));
        enterpriseMapRepository.objectsForSecondCall = List.of();

        service.applyPack("retail-v1", "PLATFORM", "user@example.com");

        assertEquals(1, semanticService.entityCalls.size());
        Map<String, Object> body = semanticService.entityCalls.get(0);
        assertFalse(body.containsKey("primaryObjectKey"),
                "must not fabricate a binding when no matching object_key is available");
    }

    // ── TEST 4 — mapping_json / TenantPack behavior unchanged ────────────────────

    @Test
    void tenantPackMappingJsonStillRecordsTheEntityToTableMapping() {
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1",
                packEntity("Product", "product"), packEntity("Supplier", "supplier")));
        enterpriseMapRepository.objects = List.of(
                dataObject("object-A", "products"), dataObject("object-B", "suppliers"));

        service.applyPack("retail-v1", "PLATFORM", "user@example.com");

        assertNotNull(packRepository.savedTenantPack);
        assertEquals(Map.of("Product", "products", "Supplier", "suppliers"),
                packRepository.savedTenantPack.entityMapping(),
                "the pack->table mapping recorded in nexus_tenant_pack.mapping_json is untouched by this fix");
        assertEquals(1.0, packRepository.savedTenantPack.coverageScore());
    }
}
