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
        Map<String, List<String>> distinctValues = Map.of();
        final List<String> probedColumns = new ArrayList<>();
        int enumCalls = 0;
        int describeTableCalls = 0;

        FakeDynamicSql() { super(null); }

        @Override public List<Map<String, Object>> describeTable(String c, String s, String t) {
            describeTableCalls++;
            return describeResult;
        }
        @Override public Map<String, List<String>> listEnumDomains(String c, String s) {
            enumCalls++;
            return enumResult;
        }
        @Override public List<String> listDistinctValues(String c, String s, String t,
                                                          String column, int limit) {
            probedColumns.add(column);
            List<String> values = distinctValues.getOrDefault(column, List.of());
            return values.size() > limit ? values.subList(0, limit) : values;
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
                dynamicSql, new SqlSafetyService(), null, new ObjectMapper(), null, null);
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
                "store_status", "vdom-old", "INFERRED", Instant.now(), Instant.now()));
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

    // ── Optimization A (onboarding performance investigation) ────────────────
    // Apply reuses the schema Analyze already fetched instead of describing the
    // table again against the live source.

    @Test
    void precomputedColumnsAreReusedWithoutCallingDescribeTableAgain() {
        // describeResult deliberately left empty — if the live describeTable() path
        // were hit, no columns would be scanned/saved at all, so this also proves
        // the precomputed columns (not a fallback) are what actually got used.
        List<Map<String, Object>> precomputed = List.of(
                Map.of("column_name", "status", "data_type", "USER-DEFINED",
                       "is_nullable", "NO", "udt_name", "store_status"),
                Map.of("column_name", "store_name", "data_type", "character varying",
                       "is_nullable", "NO", "udt_name", "varchar"));
        dynamicSql.enumResult = Map.of("store_status",
                List.of("open", "temporarily_closed", "seasonal", "under_construction", "closed"));

        service.createOrUpdateObject(new java.util.HashMap<>(Map.of(
                "domainKey", "PLATFORM", "connectionKey", "conn-1",
                "schemaName", "retail_core", "tableName", "stores",
                "columns", precomputed)), "user@x.com");

        assertEquals(0, dynamicSql.describeTableCalls,
                "the Analyze phase already fetched this table's schema — Apply must not re-describe it");
        assertEquals(2, repository.savedColumns.size(), "both precomputed columns were scanned/saved");
        DataColumn statusCol = repository.savedColumns.stream()
                .filter(c -> c.columnName().equals("status")).findFirst().orElseThrow();
        assertEquals("store_status", statusCol.udtName());
        assertEquals("vdom-123", statusCol.valueDomainKey(),
                "value-domain discovery still runs normally over the reused columns");
    }

    @Test
    void absentColumnsFallBackToLiveDescribeTable() {
        // No "columns" in the request (e.g. Discover-from-DB, or an old caller) —
        // must behave exactly as before: a live describeTable() call.
        dynamicSql.describeResult = List.of(textCol("city"));
        dynamicSql.distinctValues = Map.of("city", List.of("Austin", "Dallas"));

        service.createOrUpdateObject(new java.util.HashMap<>(Map.of(
                "domainKey", "PLATFORM", "connectionKey", "conn-1",
                "schemaName", "retail_core", "tableName", "stores",
                "safeFilterColumns", "city")), "user@x.com");

        assertEquals(1, dynamicSql.describeTableCalls);
        assertEquals(1, repository.savedColumns.size());
    }

    @Test
    void emptyColumnsListAlsoFallsBackToLiveDescribeTable() {
        // An empty "columns" list is treated the same as absent — never mistaken
        // for "the table genuinely has zero columns".
        dynamicSql.describeResult = List.of(textCol("city"));

        service.createOrUpdateObject(new java.util.HashMap<>(Map.of(
                "domainKey", "PLATFORM", "connectionKey", "conn-1",
                "schemaName", "retail_core", "tableName", "stores",
                "columns", List.of())), "user@x.com");

        assertEquals(1, dynamicSql.describeTableCalls);
    }

    @Test
    void rescanAlwaysFetchesFreshSchemaEvenThoughCreateOrUpdatePrefersPrecomputed() {
        // scanObject() (manual rescan) always passes Map.of() for request — it must
        // keep re-describing the table fresh, never silently reusing stale data.
        repository.object = storesObject();
        dynamicSql.describeResult = List.of(textCol("city"));
        dynamicSql.distinctValues = Map.of("city", List.of("Austin"));

        service.scanObject(OBJ_KEY);

        assertEquals(1, dynamicSql.describeTableCalls, "rescan must always hit the live source");
    }

    // ── OBSERVED value-domain discovery (PRO-24) ─────────────────────────────

    private static Map<String, Object> textCol(String name) {
        return Map.of("column_name", name, "data_type", "character varying",
                "is_nullable", "YES", "udt_name", "varchar");
    }

    @Test
    void observedDomainDiscoveredForTextualDiscriminatorColumn() {
        repository.object = storesObject();
        dynamicSql.describeResult = List.of(textCol("state_province"), textCol("name"));
        dynamicSql.distinctValues = Map.of("state_province",
                List.of("Texas", "Alabama", "California"));   // unsorted on purpose

        service.scanObject(OBJ_KEY);

        assertEquals(List.of("state_province"), dynamicSql.probedColumns,
                "only the status-hinted column is probed; 'name' is not a discriminator");
        assertEquals(1, repository.upsertedDomains.size());
        ValueDomain d = repository.upsertedDomains.get(0);
        assertEquals("OBSERVED", d.source());
        assertFalse(d.isAuthoritative(), "sampled values are advisory");
        assertEquals("stores.state_province", d.domainName(),
                "table-qualified so the natural key never collides across tables");
        assertEquals("[\"Alabama\",\"California\",\"Texas\"]", d.domainValuesJson(),
                "values sorted for stable JSON across rescans");

        DataColumn col = repository.savedColumns.stream()
                .filter(c -> c.columnName().equals("state_province")).findFirst().orElseThrow();
        assertEquals("vdom-123", col.valueDomainKey(), "column linked to the observed domain");
    }

    @Test
    void cardinalityGateSkipsHighCardinalityColumns() {
        repository.object = storesObject();
        dynamicSql.describeResult = List.of(textCol("state_province"));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add("v" + i);       // > default cap of 25
        dynamicSql.distinctValues = Map.of("state_province", many);

        service.scanObject(OBJ_KEY);

        assertTrue(repository.upsertedDomains.isEmpty(), "cap+1 rows back → no domain");
        DataColumn col = repository.savedColumns.get(0);
        assertNull(col.valueDomainKey());
    }

    @Test
    void enumColumnsAreNeverProbed() {
        repository.object = storesObject();
        dynamicSql.describeResult = List.of(
                Map.of("column_name", "status", "data_type", "USER-DEFINED",
                       "is_nullable", "NO", "udt_name", "store_status"));
        dynamicSql.enumResult = Map.of("store_status", List.of("open", "closed"));

        service.scanObject(OBJ_KEY);

        assertTrue(dynamicSql.probedColumns.isEmpty(),
                "USER-DEFINED is not textual — enum discovery owns it");
        assertEquals("ENUM", repository.upsertedDomains.get(0).source());
    }

    @Test
    void identifierSensitiveAndNonTextualColumnsAreExcluded() {
        repository.object = storesObject();
        dynamicSql.describeResult = List.of(
                textCol("state_code"),                                      // "code" → identifier hint
                textCol("status_token"),                                    // "token" → sensitive
                Map.of("column_name", "priority_flag", "data_type", "integer",
                       "is_nullable", "YES", "udt_name", "int4"));          // status hint but not textual
        dynamicSql.distinctValues = Map.of(
                "state_code", List.of("TX"), "status_token", List.of("x"),
                "priority_flag", List.of("1"));

        service.scanObject(OBJ_KEY);

        assertTrue(dynamicSql.probedColumns.isEmpty(), "all three exclusion gates hold");
        assertTrue(repository.upsertedDomains.isEmpty());
    }

    @Test
    void probeBudgetCapsColumnsPerTable() {
        repository.object = storesObject();
        List<Map<String, Object>> cols = new ArrayList<>();
        Map<String, List<String>> values = new java.util.HashMap<>();
        for (int i = 0; i < 12; i++) {                        // 12 eligible, budget is 8
            cols.add(textCol("status_" + i));
            values.put("status_" + i, List.of("a", "b"));
        }
        dynamicSql.describeResult = cols;
        dynamicSql.distinctValues = values;

        service.scanObject(OBJ_KEY);

        assertEquals(8, dynamicSql.probedColumns.size(), "default observed-columns-per-table");
    }

    @Test
    void emptyProbePreservesExistingObservedLink() {
        repository.object = storesObject();
        repository.existingColumns = List.of(new DataColumn(
                "col-1", OBJ_KEY, "state_province", "character varying",
                true, "", false, true, false, false, true,
                "varchar", "vdom-old-observed", "INFERRED", Instant.now(), Instant.now()));
        dynamicSql.describeResult = List.of(textCol("state_province"));
        dynamicSql.distinctValues = Map.of();                 // probe fails / returns nothing

        service.scanObject(OBJ_KEY);

        DataColumn saved = repository.savedColumns.get(0);
        assertEquals("vdom-old-observed", saved.valueDomainKey(),
                "a failed probe must never erase an existing link");
    }

    // ── Semantic Role ownership (PRO-29) ─────────────────────────────────────

    @Test
    void confirmedHumanVetoBlocksProbeAndSurvivesRescan() {
        // Human un-marked state_province as a status column (PATCH → CONFIRMED).
        // The name hint says "status"; the persisted role says no. Role wins —
        // and the rescan must not recompute the flag back to true.
        repository.object = storesObject();
        repository.existingColumns = List.of(new DataColumn(
                "col-1", OBJ_KEY, "state_province", "character varying",
                true, "", false, false, false, false, false,
                "varchar", null, "CONFIRMED", Instant.now(), Instant.now()));
        dynamicSql.describeResult = List.of(textCol("state_province"));
        dynamicSql.distinctValues = Map.of("state_province", List.of("Texas", "Utah"));

        service.scanObject(OBJ_KEY);

        assertTrue(dynamicSql.probedColumns.isEmpty(),
                "human veto is authoritative over the name-hint producer");
        DataColumn saved = repository.savedColumns.get(0);
        assertFalse(saved.isStatus(), "CONFIRMED flags are never recomputed away");
        assertEquals("CONFIRMED", saved.roleSource());
    }

    @Test
    void confirmedHumanOptInProbesNonHintedColumn() {
        // Human marked "city" filterable via PATCH — no name hint, no request,
        // yet discovery must honor the persisted CONFIRMED role.
        repository.object = storesObject();
        repository.existingColumns = List.of(new DataColumn(
                "col-1", OBJ_KEY, "city", "character varying",
                true, "", false, false, false, false, true,
                "varchar", null, "CONFIRMED", Instant.now(), Instant.now()));
        dynamicSql.describeResult = List.of(textCol("city"));
        dynamicSql.distinctValues = Map.of("city", List.of("Austin", "Dallas"));

        service.scanObject(OBJ_KEY);

        assertEquals(List.of("city"), dynamicSql.probedColumns);
        assertEquals("stores.city", repository.upsertedDomains.get(0).domainName());
    }

    @Test
    void declaredRoleSurvivesRequestlessRescan() {
        // W2 closed: an onboarding declaration (safeFilterColumns) persisted the
        // role as DECLARED; the rescan passes NO request, and the persisted role
        // must keep the column eligible — previously it silently lost eligibility.
        repository.object = storesObject();
        repository.existingColumns = List.of(new DataColumn(
                "col-1", OBJ_KEY, "city", "character varying",
                true, "", false, false, false, false, true,
                "varchar", null, "DECLARED", Instant.now(), Instant.now()));
        dynamicSql.describeResult = List.of(textCol("city"));
        dynamicSql.distinctValues = Map.of("city", List.of("Austin", "Dallas"));

        service.scanObject(OBJ_KEY);   // request = Map.of() on this path

        assertEquals(List.of("city"), dynamicSql.probedColumns,
                "declared eligibility must not evaporate on rescan");
        DataColumn saved = repository.savedColumns.get(0);
        assertTrue(saved.isFilterable(), "declared flags kept verbatim");
        assertEquals("DECLARED", saved.roleSource());
    }

    @Test
    void inferredRolesRemainRecomputableAndProvenanced() {
        repository.object = storesObject();
        dynamicSql.describeResult = List.of(textCol("state_province"));
        dynamicSql.distinctValues = Map.of("state_province", List.of("Texas"));

        service.scanObject(OBJ_KEY);

        DataColumn saved = repository.savedColumns.get(0);
        assertTrue(saved.isStatus(), "name-hint inference still produces roles");
        assertEquals("INFERRED", saved.roleSource());
    }

    @Test
    void freshDeclarationUpgradesProvenanceToDeclared() {
        repository.object = storesObject();
        dynamicSql.describeResult = List.of(textCol("city"));
        dynamicSql.distinctValues = Map.of("city", List.of("Austin"));

        service.createOrUpdateObject(new java.util.HashMap<>(Map.of(
                "domainKey", "PLATFORM", "connectionKey", "conn-1",
                "schemaName", "retail_core", "tableName", "stores",
                "safeFilterColumns", "city")), "user@x.com");

        DataColumn saved = repository.savedColumns.get(0);
        assertTrue(saved.isFilterable());
        assertEquals("DECLARED", saved.roleSource());
    }

    @Test
    void piiShapedSamplesAreNeverPersisted() {
        // PRO-27: the W1 scenario — a small table whose probed column contains
        // person names. The probe runs (name gates passed), but content
        // classification blocks persistence and the column stays unlinked.
        repository.object = storesObject();
        dynamicSql.describeResult = List.of(textCol("owner_status"));   // status-hinted name
        dynamicSql.distinctValues = Map.of("owner_status",
                List.of("John Smith", "Mary Johnson", "Robert Lee"));

        service.scanObject(OBJ_KEY);

        assertEquals(List.of("owner_status"), dynamicSql.probedColumns, "probe itself runs");
        assertTrue(repository.upsertedDomains.isEmpty(),
                "person-name-shaped sample must never become a domain");
        assertNull(repository.savedColumns.get(0).valueDomainKey());
    }

    @Test
    void explicitSafeFilterColumnsMakeNonHintedColumnsEligible() {
        repository.object = storesObject();
        dynamicSql.describeResult = List.of(textCol("city"));  // no status hint in the name
        dynamicSql.distinctValues = Map.of("city", List.of("Austin", "Dallas"));

        service.createOrUpdateObject(new java.util.HashMap<>(Map.of(
                "domainKey", "PLATFORM", "connectionKey", "conn-1",
                "schemaName", "retail_core", "tableName", "stores",
                "safeFilterColumns", "city")), "user@x.com");

        assertEquals(List.of("city"), dynamicSql.probedColumns,
                "AI-declared safe-filter columns extend eligibility (PRO-21 passthrough consumer)");
        assertEquals("stores.city", repository.upsertedDomains.get(0).domainName());
    }

}
