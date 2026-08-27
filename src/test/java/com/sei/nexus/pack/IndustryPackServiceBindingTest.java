package com.sei.nexus.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.connection.NexusConnection;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.prompt.BusinessObjectBatchAnalyzer;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.OperationalVocabulary;
import com.sei.nexus.semantic.SemanticService;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        // Connection-Scoped Industry Pack Assignment: applyPack() now calls
        // findDataObjectsByConnection twice — once (via getDiscoveredTableNamesForConnection)
        // to build the table-name list the matcher runs against, and once (via
        // loadTableNameToObjectKeyForConnection) to resolve object_keys. Both reads normally
        // see the identical result, since it's the same query against the same table — but one
        // test below deliberately diverges them to exercise the defensive "matched table, no
        // object_key" branch, which cannot otherwise occur when both calls see the same data.
        List<DataObject> objectsForSecondCall;
        // Per-connection override, used to prove that applying a pack to one connection never
        // sees another connection's tables (Test — connection scoping / isolation).
        Map<String, List<DataObject>> objectsByConnection = new java.util.HashMap<>();
        // Records every connectionKey this fake was actually queried with, in order — used to
        // assert a sibling connection's tables were never even looked at.
        final List<String> connectionKeysQueried = new ArrayList<>();
        private int callCount = 0;

        FakeEnterpriseMapRepository() { super(null); }

        @Override
        public List<DataObject> findDataObjectsByConnection(String connectionKey) {
            callCount++;
            connectionKeysQueried.add(connectionKey);
            if (objectsByConnection.containsKey(connectionKey)) {
                return objectsByConnection.get(connectionKey);
            }
            return (callCount > 1 && objectsForSecondCall != null) ? objectsForSecondCall : objects;
        }

        // previewPack(packKey, domainKey) — the 2-arg, no-connectionKey overload kept for
        // backward compatibility — still falls back to the domain-wide scan.
        @Override
        public List<DataObject> findDataObjectsByDomain(String domainKey) {
            return objects;
        }
    }

    static class FakeConnectionRepository extends ConnectionRepository {
        Map<String, NexusConnection> connections = new java.util.HashMap<>();

        FakeConnectionRepository() { super(null); }

        @Override public Optional<NexusConnection> findByKey(String connectionKey) {
            return Optional.ofNullable(connections.get(connectionKey));
        }
    }

    static class FakeSemanticService extends SemanticService {
        List<Map<String, Object>> entityCalls = new ArrayList<>();
        List<Map<String, Object>> termCalls = new ArrayList<>();
        // Industry Pack Removal Lifecycle: a minimal in-memory store, keyed exactly like the real
        // UPSERT_ENTITY/UPSERT_TERM (entity_key / term_key primary key) — good enough to prove
        // archive/deactivate actually flip status and findEntityByKey sees it, without a real DB.
        final Map<String, BusinessEntity> entitiesByKey = new java.util.LinkedHashMap<>();
        final Map<String, String> termStatusByKey = new java.util.LinkedHashMap<>();
        final List<String> archivedEntityKeys = new ArrayList<>();
        final List<String> deactivatedTermKeys = new ArrayList<>();
        // Fix Remove Pack State + Pack Vocabulary Duplication: mirrors the real
        // primary_object_key -> nexus_data_object.connection_key relationship
        // clearPackAssociationForConnection joins through — tests populate this directly instead
        // of modeling a full fake DataObject repository.
        final Map<String, String> connectionKeyByObjectKey = new java.util.LinkedHashMap<>();

        FakeSemanticService() { super(null, null, null); }

        @Override
        public BusinessEntity createOrUpdateEntity(Map<String, Object> body, String userEmail) {
            entityCalls.add(body);
            Instant now = Instant.now();
            String entityKey = (String) body.getOrDefault("entityKey", "entity-fake");
            BusinessEntity entity = new BusinessEntity(
                    entityKey, (String) body.get("domainKey"), (String) body.get("entityName"),
                    (String) body.get("description"), (String) body.get("primaryObjectKey"),
                    (String) body.get("operationalMeaning"), null,
                    (String) body.getOrDefault("status", "ACTIVE"), userEmail, now, now,
                    null, (String) body.get("groupLabel"),
                    (String) body.get("packKey"), (String) body.get("conceptKey"));
            entitiesByKey.put(entityKey, entity);
            return entity;
        }

        @Override
        public Optional<BusinessEntity> findEntityByKey(String entityKey) {
            return Optional.ofNullable(entitiesByKey.get(entityKey));
        }

        @Override
        public void archiveEntity(String entityKey) {
            archivedEntityKeys.add(entityKey);
            BusinessEntity e = entitiesByKey.get(entityKey);
            if (e != null) {
                entitiesByKey.put(entityKey, new BusinessEntity(e.entityKey(), e.domainKey(), e.entityName(),
                        e.description(), e.primaryObjectKey(), e.operationalMeaning(), e.investigationHints(),
                        "ARCHIVED", e.createdBy(), e.createdAt(), e.updatedAt(),
                        e.entityType(), e.groupLabel(), e.packKey(), e.conceptKey()));
            }
        }

        // Fix Remove Pack State + Pack Vocabulary Duplication: mirrors
        // SemanticRepository.CLEAR_PACK_ASSOCIATION_FOR_CONNECTION's exact WHERE clause — pack_key
        // match AND primary_object_key resolves (via connectionKeyByObjectKey) to connectionKey.
        // Only pack_key/concept_key are cleared; every other field, including status, survives.
        @Override
        public int clearPackAssociationForConnection(String packKey, String connectionKey) {
            int cleared = 0;
            for (Map.Entry<String, BusinessEntity> entry : new java.util.ArrayList<>(entitiesByKey.entrySet())) {
                BusinessEntity e = entry.getValue();
                if (!packKey.equals(e.packKey())) continue;
                if (!connectionKey.equals(connectionKeyByObjectKey.get(e.primaryObjectKey()))) continue;
                entitiesByKey.put(entry.getKey(), new BusinessEntity(e.entityKey(), e.domainKey(), e.entityName(),
                        e.description(), e.primaryObjectKey(), e.operationalMeaning(), e.investigationHints(),
                        e.status(), e.createdBy(), e.createdAt(), e.updatedAt(),
                        e.entityType(), e.groupLabel(), null, null));
                cleared++;
            }
            return cleared;
        }

        // Fix Apply Pack Association Regression: mirrors
        // SemanticRepository.ASSOCIATE_PACK_KEY_FOR_CONNECTION's exact WHERE clause — every
        // entity whose primary_object_key resolves (via connectionKeyByObjectKey) to
        // connectionKey gets ONLY pack_key set; concept_key and every other field survive
        // untouched. Never adds a new entry to entitiesByKey — cannot create a row.
        @Override
        public int associatePackKeyForConnection(String packKey, String connectionKey) {
            int associated = 0;
            for (Map.Entry<String, BusinessEntity> entry : new java.util.ArrayList<>(entitiesByKey.entrySet())) {
                BusinessEntity e = entry.getValue();
                if (!connectionKey.equals(connectionKeyByObjectKey.get(e.primaryObjectKey()))) continue;
                entitiesByKey.put(entry.getKey(), new BusinessEntity(e.entityKey(), e.domainKey(), e.entityName(),
                        e.description(), e.primaryObjectKey(), e.operationalMeaning(), e.investigationHints(),
                        e.status(), e.createdBy(), e.createdAt(), e.updatedAt(),
                        e.entityType(), e.groupLabel(), packKey, e.conceptKey()));
                associated++;
            }
            return associated;
        }

        // Make Apply Pack Perform LLM Concept Classification: mirrors the real
        // `WHERE primary_object_key = ? AND status = 'ACTIVE'` lookup exactly, derived directly
        // from entitiesByKey — no separate map needed.
        @Override
        public Optional<BusinessEntity> findActiveByPrimaryObjectKey(String objectKey) {
            return entitiesByKey.values().stream()
                    .filter(e -> objectKey.equals(e.primaryObjectKey()) && "ACTIVE".equals(e.status()))
                    .findFirst();
        }

        final List<String[]> conceptKeySets = new ArrayList<>(); // [entityKey, conceptKey-or-null]

        @Override
        public void setConceptKey(String entityKey, String conceptKey) {
            conceptKeySets.add(new String[]{entityKey, conceptKey});
            BusinessEntity e = entitiesByKey.get(entityKey);
            if (e != null) {
                entitiesByKey.put(entityKey, new BusinessEntity(e.entityKey(), e.domainKey(), e.entityName(),
                        e.description(), e.primaryObjectKey(), e.operationalMeaning(), e.investigationHints(),
                        e.status(), e.createdBy(), e.createdAt(), e.updatedAt(),
                        e.entityType(), e.groupLabel(), e.packKey(), conceptKey));
            }
        }

        @Override
        public OperationalVocabulary createTerm(Map<String, Object> body) {
            termCalls.add(body);
            String termKey = (String) body.getOrDefault("termKey", "term-fake");
            termStatusByKey.put(termKey, (String) body.getOrDefault("status", "ACTIVE"));
            return null;
        }

        @Override
        public Optional<OperationalVocabulary> findTermByKey(String termKey) {
            return termStatusByKey.containsKey(termKey)
                    ? Optional.of(new OperationalVocabulary(termKey, null, null, null, null, null, null,
                            termStatusByKey.get(termKey), null, null))
                    : Optional.empty();
        }

        @Override
        public void deactivateTerm(String termKey) {
            deactivatedTermKeys.add(termKey);
            if (termStatusByKey.containsKey(termKey)) termStatusByKey.put(termKey, "INACTIVE");
        }
    }

    static class FakeIndustryPackRepository extends IndustryPackRepository {
        Map<String, IndustryPack> catalogue = Map.of();
        // Industry Pack Removal Lifecycle: keyed by pack_key, exactly like the real table's
        // UNIQUE(pack_key) — supports multiple simultaneous rows (different packs/connections)
        // for the multi-connection isolation and legacy-NULL-row tests, which a single field
        // could not represent. `savedTenantPack` below is kept as a convenience alias to the
        // most recently written row, so every existing single-pack test keeps compiling and
        // behaving identically.
        final Map<String, TenantPack> tenantPacksByPackKey = new java.util.LinkedHashMap<>();
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
            return tenantPacksByPackKey.values().stream()
                    .filter(tp -> "ACTIVE".equals(tp.status())).toList();
        }

        // Industry Pack Removal Lifecycle: fixed to match the real, corrected SQL — must filter
        // by status = 'ACTIVE', mirroring findAppliedPacks()/findActivePackForConnection() below.
        // Before this fix (in both the fake and the real repository) a DISABLED row still
        // satisfied this check, which was the exact root cause of "Pack is already applied"
        // persisting forever after Remove — see IndustryPackRepository#findAppliedPack javadoc.
        @Override
        public Optional<TenantPack> findAppliedPack(String packKey) {
            TenantPack tp = tenantPacksByPackKey.get(packKey);
            return tp != null && "ACTIVE".equals(tp.status()) ? Optional.of(tp) : Optional.empty();
        }

        @Override
        public void saveTenantPack(TenantPack tp) {
            tenantPacksByPackKey.put(tp.packKey(), tp);
            savedTenantPack = tp;
        }

        // Industry Pack Removal Lifecycle: the real implementation flips status, it never
        // deletes the row (nexus_tenant_pack has UNIQUE(pack_key), and disableTenantPack's SQL
        // is a plain UPDATE) — reproduced here since the "already applied" bug depended exactly
        // on the row surviving with a non-ACTIVE status.
        @Override
        public void disableTenantPack(String packKey) {
            TenantPack tp = tenantPacksByPackKey.get(packKey);
            if (tp != null) {
                TenantPack disabled = new TenantPack(tp.packKey(), tp.connectionKey(), tp.packVersion(),
                        tp.displayName(), "DISABLED", tp.entityMapping(), tp.coverageScore(),
                        tp.appliedAt(), tp.appliedBy());
                tenantPacksByPackKey.put(packKey, disabled);
                savedTenantPack = disabled;
            }
        }

        // Connection-Scoped Industry Pack Assignment: applyPack() now pre-checks this before
        // ever discovering tables or matching entities — must be overridden here just like
        // findAppliedPack(), or it would fall through to the real JDBC-backed implementation
        // and NPE on the null JdbcTemplate this fake was built with.
        @Override
        public Optional<TenantPack> findActivePackForConnection(String connectionKey) {
            return tenantPacksByPackKey.values().stream()
                    .filter(tp -> "ACTIVE".equals(tp.status()) && connectionKey.equals(tp.connectionKey()))
                    .findFirst();
        }
    }

    /**
     * Make Apply Pack Perform LLM Concept Classification: overrides {@code analyzeBatch}
     * directly rather than faking {@code AzureOpenAiClient}/{@code DynamicSqlService} — this
     * class's real prompt-building/LLM-calling logic is out of scope here (already covered by
     * {@code BusinessObjectBatchAnalyzerConceptResolutionTest}); this fixture only needs to
     * script "the LLM returned conceptKey X for table Y" and observe how the batch was formed.
     */
    static class FakeBusinessObjectBatchAnalyzer extends BusinessObjectBatchAnalyzer {
        // tableName -> conceptKey (a present, null value simulates "the LLM analyzed this table
        // but found no confident match"). A table absent from this map entirely, but present in
        // failingTables, simulates a genuine analysis failure (describeTable/AI call).
        final Map<String, String> conceptByTable = new HashMap<>();
        final Set<String> failingTables = new HashSet<>();
        final List<List<String>> batchesReceived = new ArrayList<>();
        final List<String> connectionKeysQueried = new ArrayList<>();

        FakeBusinessObjectBatchAnalyzer() { super(null, null, null, null); }

        @Override
        public Map<String, Map<String, Object>> analyzeBatch(String connectionKey, String schemaName,
                String domainKey, List<String> tableNames) {
            batchesReceived.add(new ArrayList<>(tableNames));
            connectionKeysQueried.add(connectionKey);
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (String tableName : tableNames) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("entityName", tableName);
                entry.put("category", "Other");
                if (failingTables.contains(tableName)) {
                    entry.put("error", "simulated describeTable failure");
                } else if (conceptByTable.containsKey(tableName)) {
                    entry.put("conceptKey", conceptByTable.get(tableName)); // may legitimately be null
                }
                result.put(tableName, entry);
            }
            return result;
        }
    }

    private FakeEnterpriseMapRepository enterpriseMapRepository;
    private FakeSemanticService semanticService;
    private FakeIndustryPackRepository packRepository;
    private FakeConnectionRepository connectionRepository;
    private FakeBusinessObjectBatchAnalyzer batchAnalyzer;
    private PackEntityMapper entityMapper;
    private IndustryPackService service;

    @BeforeEach
    void setUp() {
        enterpriseMapRepository = new FakeEnterpriseMapRepository();
        semanticService = new FakeSemanticService();
        packRepository = new FakeIndustryPackRepository();
        connectionRepository = new FakeConnectionRepository();
        connectionRepository.connections.put("conn-1", testConnection("conn-1"));
        connectionRepository.connections.put("conn-2", testConnection("conn-2"));
        batchAnalyzer = new FakeBusinessObjectBatchAnalyzer();
        entityMapper = new PackEntityMapper(null, new ObjectMapper());
        service = new IndustryPackService(packRepository, entityMapper,
                new PackRecommendationService(packRepository), semanticService, enterpriseMapRepository,
                connectionRepository, batchAnalyzer);
    }

    private static NexusConnection testConnection(String connectionKey) {
        return new NexusConnection(connectionKey, "Test Connection " + connectionKey, "POSTGRES",
                "test", null, null, null, null, null, null, true,
                null, null, null, "ACTIVE", Instant.now(), Instant.now());
    }

    private static PackEntity packEntity(String name, String tablePattern) {
        return new PackEntity(name, List.of(), List.of(tablePattern), List.of(), "desc", "meaning", null, null);
    }

    private static IndustryPack pack(String packId, PackEntity... entities) {
        return new IndustryPack(packId, "RETAIL", "Test Pack", "v1", "desc",
                List.of(entities), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, null);
    }

    /** Industry Pack Removal Lifecycle: like {@link #pack}, plus vocabulary terms — needed for
     *  the Apply/Remove artifact-cleanup tests. */
    private static IndustryPack packWithVocabulary(String packId, List<PackEntity> entities,
                                                     List<PackVocabularyTerm> vocabulary) {
        return new IndustryPack(packId, "RETAIL", "Test Pack", "v1", "desc",
                entities, vocabulary, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, null);
    }

    private static PackVocabularyTerm vocabTerm(String term) {
        return new PackVocabularyTerm(term, List.of(), "definition for " + term, "");
    }

    private static DataObject dataObject(String objectKey, String tableName) {
        return new DataObject(objectKey, "PLATFORM", tableName, "conn-1", "public", tableName,
                tableName, "purpose", null, null, null, null, null, null, null,
                null, false, "SCANNED", 1, null, null);
    }

    // ── Stop Apply Pack From Creating Tenant Business Entities ───────────────────

    // TEST 1 — Apply Pack does not create Business Entities, even when its concepts match
    // real, discovered tables.
    @Test
    void applyPackNeverCreatesAnyBusinessEntity() {
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertTrue(semanticService.entityCalls.isEmpty(),
                "Apply Pack must never call createOrUpdateEntity — a PackEntity is a canonical "
                        + "concept definition, not a tenant object, and must not be materialized as one");
        assertTrue(semanticService.entitiesByKey.isEmpty());
    }

    @Test
    void applyPackWithZeroDiscoveredObjectsStillSucceedsAndCreatesNoEntities() {
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(); // nothing discovered on this connection yet

        var result = service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertEquals(0, result.entitiesCreated());
        assertTrue(semanticService.entityCalls.isEmpty());
        assertNotNull(packRepository.savedTenantPack, "the assignment itself must still succeed");
        assertEquals("conn-1", packRepository.savedTenantPack.connectionKey());
    }

    // TEST 3 — an existing tenant Business Entity for the matched physical object is never
    // duplicated: Apply Pack does not touch it, does not create a second row, does not stamp it.
    @Test
    void existingBusinessEntityForTheMatchedPhysicalObjectIsNeverDuplicated() {
        semanticService.connectionKeyByObjectKey.put("object-A", "conn-1");
        BusinessEntity preExisting = new BusinessEntity(
                "product", "PLATFORM", "Product", "curated desc", "object-A", "", "",
                "ACTIVE", "steward@x.com", Instant.now(), Instant.now(), null, null, null, null);
        semanticService.entitiesByKey.put("product", preExisting);
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertEquals(1, semanticService.entitiesByKey.size(),
                "exactly one entity must exist for this physical object — no retail-v1-product row");
        assertNull(semanticService.entitiesByKey.get("retail-v1-product"),
                "no pack-namespaced duplicate must be created for an already-onboarded table");
        assertTrue(semanticService.entityCalls.isEmpty(),
                "association happens via a direct UPDATE, never through createOrUpdateEntity");
        BusinessEntity updated = semanticService.entitiesByKey.get("product");
        assertEquals("retail-v1", updated.packKey(),
                "Fix Apply Pack Association Regression: the EXISTING entity must now receive pack_key");
        assertNull(updated.conceptKey(), "concept_key must remain untouched — Apply Pack never assigns it");
        assertEquals(preExisting.entityKey(), updated.entityKey());
        assertEquals(preExisting.primaryObjectKey(), updated.primaryObjectKey());
        assertEquals(preExisting.description(), updated.description());
    }

    // TEST 4 — Apply Pack never assigns concept_key (trivially true once it never creates/updates
    // any entity at all, but asserted explicitly since this is the non-negotiable rule).
    @Test
    void applyPackNeverAssignsConceptKey() {
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertTrue(semanticService.entityCalls.stream().noneMatch(body -> body.containsKey("conceptKey")),
                "no createOrUpdateEntity call from Apply Pack may ever carry a conceptKey — "
                        + "there must be zero such calls at all");
    }

    // ── TEST — mapping_json / TenantPack behavior unchanged ────────────────────

    @Test
    void tenantPackMappingJsonStillRecordsTheEntityToTableMapping() {
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1",
                packEntity("Product", "product"), packEntity("Supplier", "supplier")));
        enterpriseMapRepository.objects = List.of(
                dataObject("object-A", "products"), dataObject("object-B", "suppliers"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertNotNull(packRepository.savedTenantPack);
        assertEquals(Map.of("Product", "products", "Supplier", "suppliers"),
                packRepository.savedTenantPack.entityMapping(),
                "the pack->table mapping recorded in nexus_tenant_pack.mapping_json is untouched by this fix");
        assertEquals(1.0, packRepository.savedTenantPack.coverageScore());
    }

    // ── Connection-Scoped Industry Pack Assignment ───────────────────────────────

    @Test
    void applyingAPackToAConnectionPersistsThatConnectionKey() {
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertNotNull(packRepository.savedTenantPack);
        assertEquals("conn-1", packRepository.savedTenantPack.connectionKey());
    }

    @Test
    void applyingADifferentPackToADifferentConnectionSucceeds() {
        packRepository.catalogue = Map.of("logistics-v1", pack("logistics-v1", packEntity("Shipment", "shipment")));
        enterpriseMapRepository.objects = List.of(dataObject("object-S", "shipments"));

        var result = service.applyPack("logistics-v1", "PLATFORM", "conn-2", "user@example.com");

        assertEquals(0, result.entitiesCreated(), "Apply Pack never creates Business Entities");
        assertEquals("conn-2", packRepository.savedTenantPack.connectionKey());
    }

    @Test
    void applyingASecondActivePackToAConnectionThatAlreadyHasOneIsRejected() {
        packRepository.catalogue = Map.of(
                "retail-v1",    pack("retail-v1",    packEntity("Product", "product")),
                "logistics-v1", pack("logistics-v1", packEntity("Shipment", "shipment")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));
        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        enterpriseMapRepository.objects = List.of(dataObject("object-S", "shipments"));
        NexusException ex = assertThrows(NexusException.class, () ->
                service.applyPack("logistics-v1", "PLATFORM", "conn-1", "user@example.com"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("retail-v1", packRepository.savedTenantPack.packKey(),
                "the connection's existing active pack must not be replaced by the rejected apply");
    }

    @Test
    void applyingAPackToAConnectionFromAnotherTenantIsRejected() {
        // A connectionKey that does not resolve via ConnectionRepository.findByKey is exactly
        // what a foreign tenant's connection looks like from here: nexus_connection is a
        // per-tenant-schema table, so a cross-tenant key is simply never found — no separate
        // authorization mechanism is exercised or needed.
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));

        NexusException ex = assertThrows(NexusException.class, () ->
                service.applyPack("retail-v1", "PLATFORM", "conn-from-other-tenant", "user@example.com"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertNull(packRepository.savedTenantPack, "nothing should be persisted when the connection cannot be resolved");
        assertTrue(semanticService.entityCalls.isEmpty(), "no entities should be created when the connection cannot be resolved");
    }

    @Test
    void tablesFromAnotherConnectionAreNeverConsideredWhenApplyingToOneConnection() {
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        // Connection A only has "products"; Connection B only has "shipments" — if scoping
        // leaked, the matcher would see both and "Product" would still match, but so could a
        // stray "Shipment"-shaped entity from B. Apply Pack creates no entities at all now, so
        // what's left to prove connection-scoping is: the repository was queried only for
        // conn-1's tables, and the informational entity_mapping/coverage recorded on the
        // TenantPack row only reflects conn-1's own tables.
        enterpriseMapRepository.objectsByConnection.put("conn-1", List.of(dataObject("object-A", "products")));
        enterpriseMapRepository.objectsByConnection.put("conn-2", List.of(dataObject("object-S", "shipments")));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertTrue(enterpriseMapRepository.connectionKeysQueried.stream().allMatch("conn-1"::equals),
                "applying a pack to conn-1 must never query conn-2's tables: " + enterpriseMapRepository.connectionKeysQueried);
        assertEquals(Map.of("Product", "products"), packRepository.savedTenantPack.entityMapping());
        assertTrue(semanticService.entityCalls.isEmpty(), "Apply Pack never creates Business Entities");
    }

    // ── Preview: connection-scoped when connectionKey is supplied ────────────────

    @Test
    void previewWithAConnectionKeyOnlyConsidersThatConnectionsTables() {
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objectsByConnection.put("conn-1", List.of(dataObject("object-A", "products")));
        enterpriseMapRepository.objectsByConnection.put("conn-2", List.of(dataObject("object-S", "shipments")));

        PackPreview preview = service.previewPack("retail-v1", "PLATFORM", "conn-1");

        assertEquals(Map.of("Product", "products"), preview.entityMapping());
        assertTrue(enterpriseMapRepository.connectionKeysQueried.stream().allMatch("conn-1"::equals),
                "preview scoped to conn-1 must never query conn-2's tables");
    }

    @Test
    void previewWithoutAConnectionKeyFallsBackToDomainWideForBackwardCompatibility() {
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        // 2-arg overload — pre-existing callers keep working unchanged.
        PackPreview preview = service.previewPack("retail-v1", "PLATFORM");

        assertEquals(Map.of("Product", "products"), preview.entityMapping());
    }

    @Test
    void previewWithAnUnresolvableConnectionKeyIsRejected() {
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));

        NexusException ex = assertThrows(NexusException.class, () ->
                service.previewPack("retail-v1", "PLATFORM", "conn-from-other-tenant"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // ── Industry Pack Removal Lifecycle ──────────────────────────────────────────

    private static IndustryPack retailPackWithVocab() {
        return packWithVocabulary("retail-v1",
                List.of(packEntity("Product", "product")),
                List.of(vocabTerm("sell-through rate")));
    }

    // TEST 1 (restated for this section) — Apply creates the pack's vocabulary artifacts, but
    // NOT any Business Entity — the split this whole task establishes.
    @Test
    void applyCreatesVocabularyArtifactsButNeverBusinessEntities() {
        packRepository.catalogue = Map.of("retail-v1", retailPackWithVocab());
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertEquals("ACTIVE", semanticService.termStatusByKey.get("retail-v1-sell-through-rate"),
                "applyPack must still create the deterministic, pack-namespaced vocabulary term — "
                        + "vocabulary ownership is a separate concern, unaffected by this task");
        assertNull(semanticService.entitiesByKey.get("retail-v1-product"),
                "applyPack must NOT create a Business Entity for the matched pack concept");
        assertTrue(semanticService.entityCalls.isEmpty());
    }

    // TEST 2 — Remove clears active Pack assignment.
    @Test
    void removeClearsTheActivePackAssignmentForTheConnection() {
        packRepository.catalogue = Map.of("retail-v1", retailPackWithVocab());
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));
        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");
        assertTrue(packRepository.findActivePackForConnection("conn-1").isPresent());

        service.removePack("retail-v1");

        assertTrue(packRepository.findActivePackForConnection("conn-1").isEmpty(),
                "no ACTIVE pack should remain for the connection after Remove");
    }

    // TEST 3 — Pre-existing vocabulary survives.
    @Test
    void preExistingVocabularySurvivesApplyAndRemove() {
        // Simulates the real-tenant shape: 106 pre-existing terms, none pack-namespaced.
        semanticService.termStatusByKey.put("fiscal-period", "ACTIVE");
        semanticService.termStatusByKey.put("sku", "ACTIVE");
        packRepository.catalogue = Map.of("retail-v1", retailPackWithVocab());
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");
        service.removePack("retail-v1");

        assertEquals("ACTIVE", semanticService.termStatusByKey.get("fiscal-period"),
                "a term that existed independently before the pack must survive Remove untouched");
        assertEquals("ACTIVE", semanticService.termStatusByKey.get("sku"));
        assertFalse(semanticService.deactivatedTermKeys.contains("fiscal-period"));
        assertFalse(semanticService.deactivatedTermKeys.contains("sku"));
    }

    // TEST 4 — Pack-created vocabulary is removed.
    @Test
    void packCreatedVocabularyIsDeactivatedOnRemove() {
        packRepository.catalogue = Map.of("retail-v1", retailPackWithVocab());
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));
        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");
        assertEquals("ACTIVE", semanticService.termStatusByKey.get("retail-v1-sell-through-rate"));

        service.removePack("retail-v1");

        assertEquals("INACTIVE", semanticService.termStatusByKey.get("retail-v1-sell-through-rate"),
                "the pack's own vocabulary term must be deactivated on Remove");
        assertTrue(semanticService.deactivatedTermKeys.contains("retail-v1-sell-through-rate"));
    }

    // TEST 5 — Pre-existing Business Entity's identity/metadata survive Apply and Remove.
    // Note: Apply now legitimately runs LLM classification against every existing entity bound
    // to the connection's objects (Make Apply Pack Perform LLM Concept Classification), so this
    // entity's concept_key IS written to (via setConceptKey) — the batch analyzer here isn't
    // scripted with a "products" concept, so classification finds nothing confident and writes
    // concept_key = null, which was already its value. That means a NEW BusinessEntity object
    // replaces the map entry (setConceptKey always constructs a fresh record), so this can no
    // longer assert reference identity (assertSame) — the correct invariant is that every field
    // is unchanged in VALUE, which record equality (assertEquals) verifies, and pack_key ends up
    // null again too since Remove clears it. entity_key/primary_object_key/entity_name/
    // description/operational_meaning/investigation_hints/status/created_by are all untouched.
    @Test
    void preExistingBusinessEntitySurvivesApplyAndRemove() {
        BusinessEntity preExisting = new BusinessEntity(
                "product", "PLATFORM", "Product", "curated desc", "object-A", "", "",
                "ACTIVE", "steward@x.com", Instant.now(), Instant.now(), null, null, null, null);
        semanticService.entitiesByKey.put("product", preExisting);
        packRepository.catalogue = Map.of("retail-v1", retailPackWithVocab());
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");
        service.removePack("retail-v1");

        assertEquals(preExisting, semanticService.entitiesByKey.get("product"),
                "a pre-existing curated entity's fields must be unchanged in value by Apply AND Remove "
                        + "(concept_key may be legitimately (re)written to null by classification, "
                        + "but every other field, and the final observable state, must match)");
        assertFalse(semanticService.archivedEntityKeys.contains("product"));
    }

    // TEST 6 (this task) — legacy Pack-created Business Entity rows (from before this fix) must
    // still be cleanable by Remove Pack: distinguishes "legacy Pack-created entity" from "real
    // tenant Business Entity" using exactly the same pack_key double-check removePack always
    // used — this behavior is deliberately preserved for backward compatibility even though
    // applyPack itself no longer produces such rows (see IndustryPackService#removePack javadoc,
    // "legacy-only as of Stop Apply Pack From Creating Tenant Business Entities").
    @Test
    void legacyPackCreatedEntityFromBeforeThisFixIsStillArchivedOnRemove() {
        // Simulates a row a pre-fix applyPack already created — seeded directly, NOT via
        // service.applyPack(), since the current implementation can no longer produce one.
        semanticService.entitiesByKey.put("retail-v1-product", new BusinessEntity(
                "retail-v1-product", "PLATFORM", "Product", "", "object-A", "", "",
                "ACTIVE", "user@example.com", Instant.now(), Instant.now(),
                null, null, "retail-v1", null));
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        packRepository.tenantPacksByPackKey.put("retail-v1", new TenantPack("retail-v1", "conn-1", "v1",
                "Test Pack", "ACTIVE", Map.of("Product", "products"), 1.0, null, "user@example.com"));

        service.removePack("retail-v1");

        assertEquals("ARCHIVED", semanticService.entitiesByKey.get("retail-v1-product").status(),
                "a legacy pack-created row (pack_key still matches) must still be archived on Remove");
        assertTrue(semanticService.archivedEntityKeys.contains("retail-v1-product"));
    }

    // TEST 7 / 8 — Re-apply after Remove succeeds and does not duplicate artifacts (vocabulary —
    // the only artifact Apply Pack still creates).
    @Test
    void applyRemoveApplySucceedsWithoutDuplicateArtifacts() {
        packRepository.catalogue = Map.of("retail-v1", retailPackWithVocab());
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");
        service.removePack("retail-v1");

        assertDoesNotThrow(() -> service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com"),
                "re-applying the same pack after Remove must succeed — this is the exact "
                        + "\"Pack is already applied\" regression an earlier task in this session fixed");

        assertTrue(packRepository.findActivePackForConnection("conn-1").isPresent());
        assertEquals("ACTIVE", semanticService.termStatusByKey.get("retail-v1-sell-through-rate"),
                "re-apply must reactivate the same deterministic vocabulary term, not create a second one");
        assertTrue(semanticService.entityCalls.isEmpty(), "no Business Entity is ever created, on first apply or re-apply");
        // No vocabulary duplicates: exactly one row exists for this deterministic term_key —
        // proven by the map itself never growing a second entry (LinkedHashMap keyed by
        // term_key, exactly like the real UPSERT's ON CONFLICT target).
        assertEquals(1, semanticService.termStatusByKey.keySet().stream()
                .filter(k -> k.equals("retail-v1-sell-through-rate")).count());
    }

    // TEST 9 — Different connection isolation.
    @Test
    void removingOneConnectionsPackNeverAffectsAnotherConnectionsPack() {
        packRepository.catalogue = Map.of(
                "retail-v1",    packWithVocabulary("retail-v1", List.of(packEntity("Product", "product")),
                        List.of(vocabTerm("sell-through rate"))),
                "logistics-v1", packWithVocabulary("logistics-v1", List.of(packEntity("Shipment", "shipment")),
                        List.of(vocabTerm("on-time delivery rate"))));
        enterpriseMapRepository.objectsByConnection.put("conn-1", List.of(dataObject("object-A", "products")));
        enterpriseMapRepository.objectsByConnection.put("conn-2", List.of(dataObject("object-S", "shipments")));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");
        service.applyPack("logistics-v1", "PLATFORM", "conn-2", "user@example.com");

        service.removePack("retail-v1");

        assertTrue(packRepository.findActivePackForConnection("conn-1").isEmpty(),
                "conn-1's retail-v1 assignment must be cleared");
        assertTrue(packRepository.findActivePackForConnection("conn-2").isPresent(),
                "conn-2's logistics-v1 assignment must be completely unaffected");
        assertEquals("logistics-v1", packRepository.findActivePackForConnection("conn-2").get().packKey());
        assertEquals("ACTIVE", semanticService.termStatusByKey.get("logistics-v1-on-time-delivery-rate"),
                "conn-2's pack vocabulary must not be deactivated by conn-1's pack removal");
        assertFalse(semanticService.deactivatedTermKeys.contains("logistics-v1-on-time-delivery-rate"));
    }

    // TEST 10 — Legacy connection_key = NULL rows are never touched or treated as blocking.
    @Test
    void legacyNullConnectionKeyRowNeverBlocksOrIsMutatedByAnUnrelatedApplyRemoveReapply() {
        TenantPack legacy = new TenantPack("legacy-pack", null, "1.0.0", "Legacy Pack",
                "ACTIVE", Map.of(), 1.0, null, "someone@x.com");
        packRepository.tenantPacksByPackKey.put("legacy-pack", legacy);
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");
        service.removePack("retail-v1");
        assertDoesNotThrow(() -> service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com"),
                "an unrelated legacy NULL-connection_key row must never block a valid connection-scoped apply");

        TenantPack stillLegacy = packRepository.tenantPacksByPackKey.get("legacy-pack");
        assertSame(legacy, stillLegacy, "the legacy row must be the exact same, untouched object — "
                + "never reassigned a connection_key, never disabled, never backfilled");
        assertNull(stillLegacy.connectionKey());
        assertEquals("ACTIVE", stillLegacy.status());
    }

    // ── Fix Remove Pack State + Pack Vocabulary Duplication ──────────────────────

    /** Simulates a real tenant Business Entity that Discover/Onboarding registered and stamped
     *  with pack_key/concept_key via MetadataRegistrationService — NOT via applyPack (which
     *  never creates entities at all, per the prior task). This is the shape Remove Pack's new
     *  connection-scoped clearing is meant to act on. */
    private BusinessEntity discoveredEntity(String entityKey, String objectKey, String connectionKey,
                                             String packKey, String conceptKey) {
        semanticService.connectionKeyByObjectKey.put(objectKey, connectionKey);
        BusinessEntity entity = new BusinessEntity(entityKey, "PLATFORM", "Product", "real desc", objectKey,
                "real meaning", "real hints", "ACTIVE", "user@example.com", Instant.now(), Instant.now(),
                "ENTITY_TYPE_X", "Procurement", packKey, conceptKey);
        semanticService.entitiesByKey.put(entityKey, entity);
        return entity;
    }

    // TEST 1 / 2 — Remove clears pack_key and concept_key on the connection's real entities.
    @Test
    void removeClearsPackKeyAndConceptKeyOnTheConnectionsRealBusinessEntities() {
        discoveredEntity("product", "object-A", "conn-1", "retail-v1", "product");
        packRepository.tenantPacksByPackKey.put("retail-v1", new TenantPack("retail-v1", "conn-1", "v1",
                "Test Pack", "ACTIVE", Map.of(), 1.0, null, "user@example.com"));

        service.removePack("retail-v1");

        BusinessEntity after = semanticService.entitiesByKey.get("product");
        assertNull(after.packKey(), "pack_key must be cleared");
        assertNull(after.conceptKey(), "concept_key must be cleared");
    }

    // TEST 3 — the entity itself survives (remains ACTIVE, not archived/deleted).
    @Test
    void removeNeverArchivesTheRealBusinessEntityItOnlyClearsPackAssociation() {
        discoveredEntity("product", "object-A", "conn-1", "retail-v1", "product");
        packRepository.tenantPacksByPackKey.put("retail-v1", new TenantPack("retail-v1", "conn-1", "v1",
                "Test Pack", "ACTIVE", Map.of(), 1.0, null, "user@example.com"));

        service.removePack("retail-v1");

        assertEquals("ACTIVE", semanticService.entitiesByKey.get("product").status(),
                "the Business Entity itself must remain ACTIVE — Remove Pack must never archive it");
        assertFalse(semanticService.archivedEntityKeys.contains("product"));
    }

    // TEST 4 — every other field of the entity is preserved untouched.
    @Test
    void removePreservesAllNonPackBusinessEntityMetadata() {
        BusinessEntity before = discoveredEntity("product", "object-A", "conn-1", "retail-v1", "product");
        packRepository.tenantPacksByPackKey.put("retail-v1", new TenantPack("retail-v1", "conn-1", "v1",
                "Test Pack", "ACTIVE", Map.of(), 1.0, null, "user@example.com"));

        service.removePack("retail-v1");

        BusinessEntity after = semanticService.entitiesByKey.get("product");
        assertEquals(before.entityKey(), after.entityKey());
        assertEquals(before.entityName(), after.entityName());
        assertEquals(before.description(), after.description());
        assertEquals(before.operationalMeaning(), after.operationalMeaning());
        assertEquals(before.investigationHints(), after.investigationHints());
        assertEquals(before.groupLabel(), after.groupLabel());
        assertEquals(before.entityType(), after.entityType());
        assertEquals(before.primaryObjectKey(), after.primaryObjectKey());
    }

    // TEST 5 — connection isolation: removing Connection A's pack must never clear Connection
    // B's entities, even ones stamped with a DIFFERENT pack.
    @Test
    void removeOnlyClearsPackAssociationForTheAffectedConnectionNeverASiblingConnection() {
        discoveredEntity("product", "object-A", "conn-1", "retail-v1", "product");
        discoveredEntity("shipment", "object-B", "conn-2", "logistics-v1", "shipment-order");
        packRepository.tenantPacksByPackKey.put("retail-v1", new TenantPack("retail-v1", "conn-1", "v1",
                "Retail", "ACTIVE", Map.of(), 1.0, null, "user@example.com"));
        packRepository.tenantPacksByPackKey.put("logistics-v1", new TenantPack("logistics-v1", "conn-2", "v1",
                "Logistics", "ACTIVE", Map.of(), 1.0, null, "user@example.com"));

        service.removePack("retail-v1");

        BusinessEntity retailEntity = semanticService.entitiesByKey.get("product");
        assertNull(retailEntity.packKey());
        assertNull(retailEntity.conceptKey());
        BusinessEntity logisticsEntity = semanticService.entitiesByKey.get("shipment");
        assertEquals("logistics-v1", logisticsEntity.packKey(),
                "connection B's entity must be completely unaffected by connection A's pack removal");
        assertEquals("shipment-order", logisticsEntity.conceptKey());
    }

    @Test
    void removeWithNoConnectionKeyOnTheAssignmentSkipsClearingRatherThanGuessing() {
        // A legacy assignment (connection_key = NULL) — nothing to scope the clear by, so no
        // entity's pack_key/concept_key may be touched, even one that happens to carry this
        // pack_key (there is no connection to prove it "belongs" to this assignment).
        discoveredEntity("product", "object-A", "conn-1", "retail-v1", "product");
        packRepository.tenantPacksByPackKey.put("retail-v1", new TenantPack("retail-v1", null, "v1",
                "Test Pack", "ACTIVE", Map.of(), 1.0, null, "user@example.com"));

        service.removePack("retail-v1");

        BusinessEntity after = semanticService.entitiesByKey.get("product");
        assertEquals("retail-v1", after.packKey(), "no connection_key to scope by — must not guess and clear anyway");
        assertEquals("product", after.conceptKey());
    }

    // TEST 8 — a genuine second Apply of the SAME pack while it is still ACTIVE is rejected
    // (the existing guard), so vocabulary can never be duplicated by that path.
    @Test
    void applyingTheSamePackTwiceWhileActiveIsRejectedAndNeverDuplicatesVocabulary() {
        packRepository.catalogue = Map.of("retail-v1", retailPackWithVocab());
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));
        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");
        int termCallsAfterFirstApply = semanticService.termCalls.size();

        NexusException ex = assertThrows(NexusException.class, () ->
                service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(termCallsAfterFirstApply, semanticService.termCalls.size(),
                "a rejected re-apply must never reach the vocabulary-creation loop at all");
        assertEquals(1, semanticService.termStatusByKey.keySet().stream()
                .filter(k -> k.equals("retail-v1-sell-through-rate")).count());
    }

    // ── Fix Apply Pack Association Regression ────────────────────────────────────

    private BusinessEntity preExistingOnConnection(String entityKey, String objectKey, String connectionKey,
                                                     String existingConceptKey) {
        semanticService.connectionKeyByObjectKey.put(objectKey, connectionKey);
        BusinessEntity entity = new BusinessEntity(entityKey, "PLATFORM", entityKey, "desc", objectKey,
                "meaning", "hints", "ACTIVE", "steward@x.com", Instant.now(), Instant.now(),
                "TYPE_X", "Procurement", null, existingConceptKey);
        semanticService.entitiesByKey.put(entityKey, entity);
        return entity;
    }

    // TEST 1 — Apply Pack associates ALL existing entities bound to the connection.
    @Test
    void applyAssociatesPackKeyWithEveryExistingEntityOnTheConnection() {
        preExistingOnConnection("product", "object-A", "conn-1", null);
        preExistingOnConnection("store", "object-B", "conn-1", null);
        preExistingOnConnection("supplier", "object-C", "conn-1", null);
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertEquals("retail-v1", semanticService.entitiesByKey.get("product").packKey());
        assertEquals("retail-v1", semanticService.entitiesByKey.get("store").packKey());
        assertEquals("retail-v1", semanticService.entitiesByKey.get("supplier").packKey());
    }

    // TEST 2 — Apply Pack never assigns concept_key, and never overwrites an EXISTING LLM decision.
    @Test
    void applyPreservesAnyExistingConceptKeyAndNeverInventsOne() {
        preExistingOnConnection("product", "object-A", "conn-1", null);           // no prior LLM decision
        preExistingOnConnection("purchase-order", "object-B", "conn-1", "transaction"); // an existing one
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertNull(semanticService.entitiesByKey.get("product").conceptKey(),
                "an entity with no prior LLM decision must remain concept_key = NULL after Apply");
        assertEquals("transaction", semanticService.entitiesByKey.get("purchase-order").conceptKey(),
                "an entity's EXISTING LLM decision must survive Apply completely unchanged");
    }

    // TEST 3 (restated) — Apply Pack still creates zero new Business Entity rows while associating.
    @Test
    void applyAssociatesWithoutCreatingAnyNewEntityRows() {
        preExistingOnConnection("product", "object-A", "conn-1", null);
        packRepository.catalogue = Map.of("retail-v1",
                pack("retail-v1", packEntity("Product", "product"), packEntity("Store", "store")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertEquals(1, semanticService.entitiesByKey.size(), "no new row for the unmatched 'Store' concept either");
        assertTrue(semanticService.entityCalls.isEmpty());
    }

    // TEST 6 — Connection isolation: associating Connection A's pack must never touch Connection B.
    @Test
    void associationIsScopedToTheAppliedConnectionOnly() {
        preExistingOnConnection("product", "object-A", "conn-1", null);
        preExistingOnConnection("shipment", "object-B", "conn-2", null);
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertEquals("retail-v1", semanticService.entitiesByKey.get("product").packKey());
        assertNull(semanticService.entitiesByKey.get("shipment").packKey(),
                "connection B's entity must never receive connection A's pack_key");
    }

    // Full round-trip: Apply associates, Remove clears — proving the two fixes integrate.
    @Test
    void applyAssociatesThenRemoveClearsThePackKeyOnTheSameExistingEntity() {
        preExistingOnConnection("product", "object-A", "conn-1", null);
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");
        assertEquals("retail-v1", semanticService.entitiesByKey.get("product").packKey());

        service.removePack("retail-v1");

        BusinessEntity afterRemove = semanticService.entitiesByKey.get("product");
        assertNull(afterRemove.packKey());
        assertNull(afterRemove.conceptKey());
        assertEquals("ACTIVE", afterRemove.status(), "the entity itself must still survive Remove");
    }

    // ── Make Apply Pack Perform LLM Concept Classification ───────────────────────
    //
    // These tests script the FakeBusinessObjectBatchAnalyzer to stand in for the real LLM
    // conceptResolution call. Candidate discovery for classification goes through
    // enterpriseMapRepository.findDataObjectsByConnection(connectionKey) (see
    // IndustryPackService#classifyExistingObjectsForConnection) — so, unlike the pure pack_key
    // association tests above (which only need connectionKeyByObjectKey), these tests must also
    // register a DataObject for every table via enterpriseMapRepository.objects/objectsByConnection.

    // TEST 1 — Apply Pack classifies existing objects: product/store/supplier each get
    // pack_key=retail-v1 and concept_key = whatever the (fake) LLM returned for their table.
    @Test
    void applyPackClassifiesExistingObjectsWithLlmReturnedConceptKeys() {
        preExistingOnConnection("product", "object-A", "conn-1", null);
        preExistingOnConnection("store", "object-B", "conn-1", null);
        preExistingOnConnection("supplier", "object-C", "conn-1", null);
        enterpriseMapRepository.objects = List.of(
                dataObject("object-A", "products"), dataObject("object-B", "stores"), dataObject("object-C", "suppliers"));
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1",
                packEntity("Product", "product"), packEntity("Store", "store"), packEntity("Supplier", "supplier")));
        batchAnalyzer.conceptByTable.put("products", "retail-v1-product");
        batchAnalyzer.conceptByTable.put("stores", "retail-v1-store");
        batchAnalyzer.conceptByTable.put("suppliers", "retail-v1-supplier");

        var result = service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertEquals("retail-v1", semanticService.entitiesByKey.get("product").packKey());
        assertEquals("retail-v1", semanticService.entitiesByKey.get("store").packKey());
        assertEquals("retail-v1", semanticService.entitiesByKey.get("supplier").packKey());
        assertEquals("retail-v1-product", semanticService.entitiesByKey.get("product").conceptKey());
        assertEquals("retail-v1-store", semanticService.entitiesByKey.get("store").conceptKey());
        assertEquals("retail-v1-supplier", semanticService.entitiesByKey.get("supplier").conceptKey());
        assertEquals(3, result.entitiesClassified());
        assertEquals(0, result.entitiesUnresolved());
    }

    // TEST 2 — Java does not determine concept_key: the persisted value exactly matches the
    // scripted fake LLM response, including a value with no relation whatsoever to the table
    // name — proving no Java pattern/slug matching is involved, only relaying the LLM's decision.
    @Test
    void persistedConceptKeyExactlyMatchesTheScriptedLlmResponseNotAnyJavaDerivedValue() {
        preExistingOnConnection("product", "object-A", "conn-1", null);
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        // Deliberately unrelated to "product"/"products" — if Java derived concept_key from the
        // table/entity name (slug matching, pattern matching, etc.) this exact value could never
        // appear; its presence proves the persisted value came only from the fake LLM response.
        batchAnalyzer.conceptByTable.put("products", "unrelated-concept-42-xyz");

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertEquals("unrelated-concept-42-xyz", semanticService.entitiesByKey.get("product").conceptKey());
        assertEquals(1, semanticService.conceptKeySets.size(), "exactly one persistence call, carrying the LLM's value verbatim");
        assertArrayEquals(new String[]{"product", "unrelated-concept-42-xyz"}, semanticService.conceptKeySets.get(0));
    }

    // TEST 3 — No Pack-created Business Entities, even once classification is wired in: entity
    // count is unchanged after Apply, and no retail-v1-* entity ever appears.
    @Test
    void applyPackStillNeverCreatesBusinessEntitiesEvenWhileClassifying() {
        preExistingOnConnection("product", "object-A", "conn-1", null);
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        batchAnalyzer.conceptByTable.put("products", "retail-v1-product");

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertEquals(1, semanticService.entitiesByKey.size(), "classification must never add a new entity row");
        assertNull(semanticService.entitiesByKey.get("retail-v1-product"));
        assertTrue(semanticService.entityCalls.isEmpty(), "concept_key is persisted via setConceptKey, never createOrUpdateEntity");
    }

    // TEST 4 — Existing entity identity/metadata is fully preserved by classification: only
    // pack_key/concept_key may change; entity_key, primary_object_key, and every curated field
    // are untouched.
    @Test
    void classificationNeverChangesEntityIdentityOrCuratedMetadata() {
        BusinessEntity before = preExistingOnConnection("product", "object-A", "conn-1", null);
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        batchAnalyzer.conceptByTable.put("products", "retail-v1-product");

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        BusinessEntity after = semanticService.entitiesByKey.get("product");
        assertEquals(before.entityKey(), after.entityKey());
        assertEquals(before.primaryObjectKey(), after.primaryObjectKey());
        assertEquals(before.entityName(), after.entityName());
        assertEquals(before.description(), after.description());
        assertEquals(before.operationalMeaning(), after.operationalMeaning());
        assertEquals(before.investigationHints(), after.investigationHints());
        assertEquals(before.status(), after.status());
        assertEquals(before.groupLabel(), after.groupLabel());
        assertEquals(before.entityType(), after.entityType());
        assertEquals("retail-v1-product", after.conceptKey(), "only concept_key (and pack_key) may change");
    }

    // TEST 5 — Unresolved concept: the (fake) LLM analyzed the table but returned no confident
    // concept (a present, null conceptByTable entry) → concept_key must be explicitly NULL, never
    // a Java-invented fallback, and never left as "unresolved is a failure."
    @Test
    void unresolvedLlmClassificationExplicitlyClearsConceptKeyToNull() {
        preExistingOnConnection("product", "object-A", "conn-1", "stale-prior-value");
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        batchAnalyzer.conceptByTable.put("products", null); // present key, null value == "analyzed, no confident match"

        var result = service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertNull(semanticService.entitiesByKey.get("product").conceptKey(),
                "an unresolved LLM classification must explicitly clear concept_key to NULL");
        assertEquals(0, result.entitiesClassified());
        assertEquals(1, result.entitiesUnresolved());
    }

    // TEST 6 — Batch processing: more candidate objects than one analyzer batch (default size 4)
    // — verify every object is still processed, across multiple analyzeBatch calls.
    @Test
    void moreObjectsThanOneBatchAreAllProcessedAcrossMultipleBatches() {
        List<DataObject> objects = new ArrayList<>();
        List<PackEntity> entities = new ArrayList<>();
        for (int i = 0; i < 5; i++) { // 5 > default batch size of 4 -> must span two analyzeBatch calls
            String table = "table" + i;
            preExistingOnConnection("entity" + i, "object-" + i, "conn-1", null);
            objects.add(dataObject("object-" + i, table));
            entities.add(packEntity("Entity" + i, table));
            batchAnalyzer.conceptByTable.put(table, "concept-" + i);
        }
        enterpriseMapRepository.objects = objects;
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", entities.toArray(new PackEntity[0])));

        var result = service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertEquals(2, batchAnalyzer.batchesReceived.size(), "5 objects at batch size 4 must span exactly 2 batches");
        assertEquals(4, batchAnalyzer.batchesReceived.get(0).size());
        assertEquals(1, batchAnalyzer.batchesReceived.get(1).size());
        assertEquals(5, result.entitiesClassified(), "every object across both batches must be classified");
        for (int i = 0; i < 5; i++) {
            assertEquals("concept-" + i, semanticService.entitiesByKey.get("entity" + i).conceptKey());
        }
    }

    // TEST 7 — Connection isolation: applying Retail to Connection A must only classify A's
    // objects — Connection B's entity/object must never be touched or even queried.
    @Test
    void classificationIsScopedToTheAppliedConnectionOnly() {
        preExistingOnConnection("product", "object-A", "conn-1", null);
        preExistingOnConnection("shipment", "object-B", "conn-2", null);
        enterpriseMapRepository.objectsByConnection.put("conn-1", List.of(dataObject("object-A", "products")));
        enterpriseMapRepository.objectsByConnection.put("conn-2", List.of(dataObject("object-B", "shipments")));
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        batchAnalyzer.conceptByTable.put("products", "retail-v1-product");
        batchAnalyzer.conceptByTable.put("shipments", "logistics-v1-shipment"); // scripted, must never be reached

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertEquals("retail-v1-product", semanticService.entitiesByKey.get("product").conceptKey());
        assertNull(semanticService.entitiesByKey.get("shipment").conceptKey(),
                "connection B's entity must never be classified by a pack applied to connection A");
        assertTrue(batchAnalyzer.connectionKeysQueried.stream().allMatch("conn-1"::equals),
                "the analyzer must never even be invoked with connection B's key: " + batchAnalyzer.connectionKeysQueried);
        assertTrue(batchAnalyzer.batchesReceived.stream().noneMatch(b -> b.contains("shipments")),
                "connection B's table must never appear in any batch sent for connection A's Apply");
    }

    // TEST 8 — Remove clears both pack_key and concept_key after classification, and the entity
    // remains ACTIVE (never archived/deleted).
    @Test
    void removeClearsPackKeyAndClassifiedConceptKeyButEntityStaysActive() {
        preExistingOnConnection("product", "object-A", "conn-1", null);
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        batchAnalyzer.conceptByTable.put("products", "retail-v1-product");
        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");
        assertEquals("retail-v1-product", semanticService.entitiesByKey.get("product").conceptKey());

        service.removePack("retail-v1");

        BusinessEntity after = semanticService.entitiesByKey.get("product");
        assertNull(after.packKey());
        assertNull(after.conceptKey());
        assertEquals("ACTIVE", after.status());
        assertFalse(semanticService.archivedEntityKeys.contains("product"));
    }

    // TEST 9 — Apply -> Remove -> Apply a DIFFERENT pack: the same real tenant object is
    // reclassified against the new pack's concepts, producing a fresh LLM-derived concept_key.
    @Test
    void removeThenApplyingADifferentPackReclassifiesTheSameObjectWithFreshConceptKey() {
        preExistingOnConnection("product", "object-A", "conn-1", null);
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));
        packRepository.catalogue = Map.of(
                "retail-v1",    pack("retail-v1",    packEntity("Product", "product")),
                "logistics-v1", pack("logistics-v1", packEntity("Product", "product")));
        batchAnalyzer.conceptByTable.put("products", "retail-v1-product");
        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");
        assertEquals("retail-v1-product", semanticService.entitiesByKey.get("product").conceptKey());
        service.removePack("retail-v1");
        assertNull(semanticService.entitiesByKey.get("product").conceptKey(), "Remove clears the prior classification first");

        batchAnalyzer.conceptByTable.put("products", "logistics-v1-widget"); // the LLM re-scored against the new pack
        service.applyPack("logistics-v1", "PLATFORM", "conn-1", "user@example.com");

        BusinessEntity after = semanticService.entitiesByKey.get("product");
        assertEquals("logistics-v1", after.packKey());
        assertEquals("logistics-v1-widget", after.conceptKey(),
                "the same real tenant object must be reclassified fresh against the new pack's concepts");
    }

    // TEST 10 — New object after Pack: Apply Retail once; the connection's active pack assignment
    // (which normal Discover/Onboarding's existing resolveActivePackContext mechanism reads to
    // classify newly-registered objects, per BusinessObjectBatchAnalyzer — out of scope to
    // re-test here) is in place without any second Apply/Discover being required.
    @Test
    void applyingAPackOnceLeavesAnActiveAssignmentAutomaticallyUsableByLaterOnboarding() {
        preExistingOnConnection("product", "object-A", "conn-1", null);
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        batchAnalyzer.conceptByTable.put("products", "retail-v1-product");

        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");

        assertTrue(packRepository.findActivePackForConnection("conn-1").isPresent(),
                "a subsequent Discover/Onboarding run for a NEW object on this connection must find an ACTIVE "
                        + "pack assignment already in place, with no re-apply required");
        assertEquals("retail-v1", packRepository.findActivePackForConnection("conn-1").get().packKey());
    }

    // TEST 11 — Apply the SAME pack twice while ACTIVE is rejected — no duplicate classification
    // job is ever started by the rejected second call.
    @Test
    void applyingTheSamePackTwiceNeverStartsASecondClassificationJob() {
        preExistingOnConnection("product", "object-A", "conn-1", null);
        enterpriseMapRepository.objects = List.of(dataObject("object-A", "products"));
        packRepository.catalogue = Map.of("retail-v1", pack("retail-v1", packEntity("Product", "product")));
        batchAnalyzer.conceptByTable.put("products", "retail-v1-product");
        service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com");
        int batchesAfterFirstApply = batchAnalyzer.batchesReceived.size();

        NexusException ex = assertThrows(NexusException.class, () ->
                service.applyPack("retail-v1", "PLATFORM", "conn-1", "user@example.com"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(batchesAfterFirstApply, batchAnalyzer.batchesReceived.size(),
                "a rejected re-apply must never reach the classification step at all");
        assertEquals(1, semanticService.conceptKeySets.size(), "concept_key must be persisted exactly once, not duplicated");
    }
}
