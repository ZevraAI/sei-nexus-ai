package com.sei.nexus.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.connection.NexusConnection;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.sql.SqlSafetyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-10 — value-domain discovery during column scan, and its consumption in the
 * planner context. Exercises the public paths (scanObject / operationalContext)
 * with hand-rolled fakes so no database (and no bytecode instrumentation) is
 * required.
 */
class ValueDomainDiscoveryTest {

    private static final String OBJ_KEY = "obj-stores";

    // ── fakes ────────────────────────────────────────────────────────────────

    static class FakeRepository extends EnterpriseMapRepository {
        DataObject       object;
        List<DataColumn> existingColumns = List.of();
        ValueDomain      domainByKey;
        List<DataObject> objectsByDomain = List.of();

        final List<DataColumn>  savedColumns    = new ArrayList<>();
        final List<ValueDomain> upsertedDomains = new ArrayList<>();
        int findValueDomainCalls = 0;

        FakeRepository() { super(null); }

        @Override public Optional<DataObject> findDataObjectByKey(String key) {
            return Optional.ofNullable(object);
        }
        @Override public List<DataColumn> findColumnsByObject(String key) {
            return existingColumns;
        }
        @Override public void saveColumn(DataColumn col) { savedColumns.add(col); }
        @Override public void saveDataObject(DataObject obj) { /* no-op */ }
        @Override public void saveDataObjectVersion(String k, int v, String json, String reason) { /* no-op */ }
        @Override public String upsertValueDomain(ValueDomain d) {
            upsertedDomains.add(d);
            return "vdom-123";
        }
        @Override public Optional<ValueDomain> findValueDomainByKey(String key) {
            findValueDomainCalls++;
            return Optional.ofNullable(domainByKey);
        }
        @Override public List<DataObject> findDataObjectsByDomain(String domainKey) {
            return objectsByDomain;
        }
        @Override public List<OperationalNote> findNotesByDomain(String domainKey) {
            return List.of();
        }
    }

    static class FakeDynamicSql extends DynamicSqlService {
        List<Map<String, Object>> describeResult = List.of();
        Map<String, List<String>> enumResult     = Map.of();
        int enumCalls = 0;

        FakeDynamicSql() { super(null); }

        @Override public List<Map<String, Object>> describeTable(String c, String s, String t) {
            return describeResult;
        }
        @Override public Map<String, List<String>> listEnumDomains(String c, String s) {
            enumCalls++;
            return enumResult;
        }
    }

    static class FakeConnectionRepository extends ConnectionRepository {
        FakeConnectionRepository() { super(null); }
        @Override public Optional<NexusConnection> findByKey(String key) {
            return Optional.of(new NexusConnection(key, "Retail DB", "POSTGRES", "",
                    "jdbc:postgresql://x/db", null, "u", "s", "", "", true,
                    null, null, null, "ACTIVE", Instant.now(), Instant.now()));
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private FakeRepository       repository;
    private FakeDynamicSql       dynamicSql;
    private EnterpriseMapService service;

    private static DataObject storesObject() {
        return new DataObject(OBJ_KEY, "PLATFORM", "Store", "conn-1",
                "retail_core", "stores", "Stores", "Store master data",
                "", "status", "", "", "", "", "",
                500, false, "SCANNED", 1, Instant.now(), Instant.now());
    }

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        dynamicSql = new FakeDynamicSql();
        service = new EnterpriseMapService(repository, new FakeConnectionRepository(),
                dynamicSql, new SqlSafetyService(), null, new ObjectMapper());
    }

    // ── discovery ────────────────────────────────────────────────────────────

    @Test
    void scanDiscoversEnumDomainAndLinksColumn() {
        repository.object = storesObject();
        dynamicSql.describeResult = List.of(
                Map.of("column_name", "status", "data_type", "USER-DEFINED",
                       "is_nullable", "NO", "udt_name", "store_status"),
                Map.of("column_name", "store_name", "data_type", "character varying",
                       "is_nullable", "NO", "udt_name", "varchar"));
        dynamicSql.enumResult = Map.of("store_status",
                List.of("open", "temporarily_closed", "seasonal", "under_construction", "closed"));

        service.scanObject(OBJ_KEY);

        // Domain persisted once, with the right identity and ordered values as JSON
        assertEquals(1, repository.upsertedDomains.size());
        ValueDomain d = repository.upsertedDomains.get(0);
        assertEquals("conn-1",       d.connectionKey());
        assertEquals("retail_core",  d.sourceSchema());
        assertEquals("store_status", d.domainName());
        assertEquals("ENUM",         d.source());
        assertTrue(d.isAuthoritative());
        assertEquals("[\"open\",\"temporarily_closed\",\"seasonal\",\"under_construction\",\"closed\"]",
                d.domainValuesJson());

        // Enum column linked; plain column untouched
        DataColumn statusCol = repository.savedColumns.stream()
                .filter(c -> c.columnName().equals("status")).findFirst().orElseThrow();
        assertEquals("store_status", statusCol.udtName());
        assertEquals("vdom-123",     statusCol.valueDomainKey());

        DataColumn nameCol = repository.savedColumns.stream()
                .filter(c -> c.columnName().equals("store_name")).findFirst().orElseThrow();
        assertNull(nameCol.valueDomainKey());
    }

    @Test
    void scanWithoutEnumColumnsSkipsDiscovery() {
        repository.object = storesObject();
        dynamicSql.describeResult = List.of(
                Map.of("column_name", "store_name", "data_type", "character varying",
                       "is_nullable", "NO", "udt_name", "varchar"));

        service.scanObject(OBJ_KEY);

        assertEquals(0, dynamicSql.enumCalls, "no USER-DEFINED columns → no pg_enum query");
        assertTrue(repository.upsertedDomains.isEmpty());
    }

    @Test
    void rescanPreservesExistingDomainLinkWhenDiscoveryReturnsNothing() {
        repository.object = storesObject();
        repository.existingColumns = List.of(new DataColumn(
                "col-1", OBJ_KEY, "status", "USER-DEFINED",
                false, "", false, true, false, false, true,
                "store_status", "vdom-old", Instant.now(), Instant.now()));
        dynamicSql.describeResult = List.of(
                Map.of("column_name", "status", "data_type", "USER-DEFINED",
                       "is_nullable", "NO", "udt_name", "store_status"));
        dynamicSql.enumResult = Map.of();  // transient discovery failure → empty

        service.scanObject(OBJ_KEY);

        DataColumn saved = repository.savedColumns.get(0);
        assertEquals("store_status", saved.udtName());
        assertEquals("vdom-old",     saved.valueDomainKey(),
                "existing domain link must survive a scan that discovered nothing");
    }

    // ── runtime consumption ──────────────────────────────────────────────────

    @Test
    void operationalContextRendersDomainValuesInsteadOfOpaqueType() {
        repository.objectsByDomain = List.of(storesObject());
        repository.existingColumns = List.of(new DataColumn(
                "col-1", OBJ_KEY, "status", "USER-DEFINED",
                false, "", false, true, false, false, true,
                "store_status", "vdom-123", Instant.now(), Instant.now()));
        repository.domainByKey = new ValueDomain("vdom-123", "conn-1", "retail_core",
                "store_status", "ENUM", true,
                "[\"open\",\"temporarily_closed\",\"seasonal\",\"under_construction\",\"closed\"]",
                Instant.now());

        Map<String, Object> ctx = service.operationalContext(
                List.of("PLATFORM"), List.of("conn-1"), "show me stores");

        String entityContext = (String) ctx.get("entityContext");
        assertTrue(entityContext.contains(
                "status (store_status: open | temporarily_closed | seasonal | under_construction | closed)"),
                "planner context must name the legal enum values, got:\n" + entityContext);
        assertFalse(entityContext.contains("(USER-DEFINED)"),
                "opaque USER-DEFINED label must be replaced for domain-linked columns");
    }
}
