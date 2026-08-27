package com.sei.nexus.semantic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.BufferedReader;
import java.io.FileReader;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DIAGNOSTIC (opt-in, real Postgres): proves Foundation Fix #2's COALESCE guard
 * behaves correctly against the actual database engine — a unit test on the SQL
 * string (see {@link SemanticRepositoryUpsertEntityTest}) cannot verify Postgres's
 * runtime evaluation of ON CONFLICT/EXCLUDED/COALESCE.
 *
 * <p>Runs entirely inside a dedicated, self-created scratch schema
 * ({@code zevra_fix2_scratch_test}) holding a private copy of just the
 * {@code nexus_business_entity} shape needed to exercise {@code UPSERT_ENTITY} —
 * it never reads or writes any tenant schema, any real {@code nexus_business_entity}
 * row, or any other production table. The schema is dropped in {@code @AfterEach}
 * even on failure.
 *
 * <p>Guarded by {@code -Dnexus.fix2.probe=true}; skipped otherwise (no DB access
 * required for a normal {@code mvn test} run).
 */
class SemanticRepositoryUpsertPreservationProbe {

    private static final String SCHEMA = "zevra_fix2_scratch_test";

    private JdbcTemplate jdbc;
    private SemanticRepository repository;

    private static Map<String, String> env() throws Exception {
        Map<String, String> e = new java.util.HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(".env.local"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                int i = line.indexOf('=');
                e.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        }
        return e;
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.fix2.probe"), "diagnostic disabled");
        Map<String, String> env = env();

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("jdbc:postgresql://([^:/]+):(\\d+)/([^?]+)")
                .matcher(env.get("NEXUS_DB_URL"));
        assumeTrue(m.find());
        // currentSchema is a real JDBC connection property (not a session-level SET),
        // so — unlike SET search_path executed on one throwaway connection — it applies
        // to every fresh connection DriverManagerDataSource opens (it does not pool).
        // Bug found and fixed while adding pack_key/concept_key columns below: without
        // this, saveEntity()'s own connection defaulted to "$user", public and silently
        // wrote/read the REAL public.nexus_business_entity table instead of the scratch
        // one — this test was not actually isolated before this fix.
        String url = "jdbc:postgresql://" + m.group(1) + ":" + m.group(2) + "/" + m.group(3)
                + "?sslmode=require&currentSchema=" + SCHEMA;

        DriverManagerDataSource ds = new DriverManagerDataSource(url,
                env.get("NEXUS_DB_USERNAME"), env.get("NEXUS_DB_PASSWORD"));
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        // Minimal shape: only the columns UPSERT_ENTITY touches. No FKs — this is a
        // throwaway scratch table, not a copy of the real nexus_business_entity
        // definition, and is dropped at the end of every test.
        jdbc.execute("""
                CREATE TABLE %s.nexus_business_entity (
                    entity_key           VARCHAR(120) PRIMARY KEY,
                    domain_key           VARCHAR(120),
                    entity_name          VARCHAR(255),
                    description          TEXT,
                    primary_object_key   VARCHAR(120),
                    operational_meaning  TEXT,
                    investigation_hints  TEXT,
                    status               VARCHAR(32),
                    created_by           VARCHAR(255),
                    created_at           TIMESTAMPTZ,
                    updated_at           TIMESTAMPTZ,
                    entity_type          VARCHAR(64),
                    group_label          VARCHAR(120),
                    pack_key             VARCHAR(255),
                    concept_key          VARCHAR(120)
                )
                """.formatted(SCHEMA));
        jdbc.execute("SET search_path TO " + SCHEMA);

        repository = new SemanticRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private BusinessEntity entity(String key, String primaryObjectKey) {
        Instant now = Instant.now();
        return new BusinessEntity(key, "PLATFORM", "Product", "desc", primaryObjectKey,
                "meaning", "hints", "ACTIVE", "test@example.com", now, now);
    }

    /** TEST 1 — Preserve Existing Binding: omitted primaryObjectKey must not erase object-A. */
    @Test
    void preservesExistingBindingWhenUpdateOmitsPrimaryObjectKey() {
        repository.saveEntity(entity("product-1", "object-A"));
        repository.saveEntity(entity("product-1", null));

        BusinessEntity result = repository.findEntityByKey("product-1").orElseThrow();
        assertEquals("object-A", result.primaryObjectKey());
    }

    /** TEST 2 — Set Previously NULL Binding: a real value must still take effect from NULL. */
    @Test
    void setsBindingWhenPreviouslyNull() {
        repository.saveEntity(entity("product-2", null));
        repository.saveEntity(entity("product-2", "object-B"));

        BusinessEntity result = repository.findEntityByKey("product-2").orElseThrow();
        assertEquals("object-B", result.primaryObjectKey());
    }

    /** TEST 3 — Explicit Replacement: a real value must still overwrite a different real value. */
    @Test
    void replacesExistingBindingWithNewValue() {
        repository.saveEntity(entity("product-3", "object-A"));
        repository.saveEntity(entity("product-3", "object-B"));

        BusinessEntity result = repository.findEntityByKey("product-3").orElseThrow();
        assertEquals("object-B", result.primaryObjectKey());
    }

    /** Sanity check: a genuinely-never-bound entity remains NULL, not accidentally defaulted. */
    @Test
    void neverBoundEntityStaysNull() {
        repository.saveEntity(entity("product-4", null));
        repository.saveEntity(entity("product-4", null));

        BusinessEntity result = repository.findEntityByKey("product-4").orElseThrow();
        assertNull(result.primaryObjectKey());
    }

    /**
     * REGRESSION — reproduces the exact "region" corruption: create a bound entity via
     * the onboarding-shaped path (primaryObjectKey set), then perform an update whose
     * payload is shaped exactly like the Semantic Layer entity-edit UI's save call
     * (no primaryObjectKey key in the body at all — the same field set as
     * Semantic.jsx's saveEntity()). The binding must survive.
     */
    @Test
    void semanticLayerUiEditPayloadDoesNotCorruptExistingBinding() {
        // Step 1: onboarding-shaped creation (MetadataRegistrationService.register()
        // passes primaryObjectKey explicitly).
        repository.saveEntity(entity("region", "platform-conn-5780d333-regions"));

        // Step 2: UI-edit-shaped update — mirrors Semantic.jsx saveEntity()'s payload,
        // which contains entityKey/entityName/description/operationalMeaning/
        // investigationHints/nodeType/groupLabel/status/domainKey and NOTHING else.
        // SemanticService.createOrUpdateEntity() reads primaryObjectKey via
        // str(body, "primaryObjectKey", "primary_object_key"), which returns null
        // when the key is absent from the map — reproduced directly here.
        BusinessEntity uiEdit = new BusinessEntity(
                "region", "PLATFORM", "Region", "updated description",
                null, // <- exactly what the UI form omits
                "updated meaning", "updated hints", "ACTIVE",
                "test@example.com", Instant.now(), Instant.now());
        repository.saveEntity(uiEdit);

        BusinessEntity result = repository.findEntityByKey("region").orElseThrow();
        assertEquals("platform-conn-5780d333-regions", result.primaryObjectKey(),
                "UI edit must not silently unbind a previously-correct primary_object_key");
        // The rest of the edit must still have taken effect — only the binding is protected.
        assertEquals("updated description", result.description());
    }

    /**
     * Grouping Foundation Fix, real Postgres — mirrors the "region" regression above
     * exactly, for group_label instead of primary_object_key: an onboarding analysis
     * sets a category, then a later partial update (e.g. a Semantic Layer edit that
     * touches only other fields, or a re-registration pass whose analysis omitted
     * category) must not erase it.
     */
    @Test
    void groupLabelSurvivesAnUpdateThatOmitsIt() {
        // Step 1: onboarding-shaped creation — analyzeTableBatch produced a category.
        Instant now = Instant.now();
        repository.saveEntity(new BusinessEntity("warehouse", "PLATFORM", "Warehouse", "desc",
                "platform-conn-1-warehouses", "meaning", "hints", "ACTIVE",
                "test@example.com", now, now, null, "Operations"));

        // Step 2: a partial update whose body has no group/category at all (mirrors
        // SemanticService.createOrUpdateEntity() receiving a request with no
        // "groupLabel"/"group_label" key — str(...) returns null, exactly reproduced here).
        repository.saveEntity(new BusinessEntity("warehouse", "PLATFORM", "Warehouse",
                "updated description", "platform-conn-1-warehouses", "updated meaning",
                "updated hints", "ACTIVE", "test@example.com", Instant.now(), Instant.now(),
                null, null));

        BusinessEntity result = repository.findEntityByKey("warehouse").orElseThrow();
        assertEquals("Operations", result.groupLabel(),
                "an update that omits category must not erase an existing group_label");
        assertEquals("updated description", result.description());
    }

    /**
     * Global Pack Foundation, real Postgres — same COALESCE discipline as
     * primary_object_key/group_label above, for pack_key/concept_key: a future pack
     * apply/mapping step might set these, and a later partial update (e.g. a Semantic
     * Layer edit, or a re-registration pass) that omits them must not erase the
     * reference.
     */
    @Test
    void packAndConceptKeySurviveAnUpdateThatOmitsThem() {
        // Step 1: a future mapping mechanism sets pack_key/concept_key (not exercised
        // by any current caller — this simulates the eventual write path directly).
        Instant now = Instant.now();
        repository.saveEntity(new BusinessEntity("purchase-order", "PLATFORM", "Purchase Order", "desc",
                "platform-conn-1-purchase-orders", "meaning", "hints", "ACTIVE",
                "test@example.com", now, now, null, "Transactions",
                "retail-v1", "purchase_order"));

        // Step 2: a partial update whose body has no pack/concept reference at all —
        // mirrors every current caller (Onboarding, Discover, Pack apply, manual edit),
        // none of which populate these fields in this task.
        repository.saveEntity(new BusinessEntity("purchase-order", "PLATFORM", "Purchase Order",
                "updated description", "platform-conn-1-purchase-orders", "updated meaning",
                "updated hints", "ACTIVE", "test@example.com", Instant.now(), Instant.now(),
                null, "Transactions"));

        BusinessEntity result = repository.findEntityByKey("purchase-order").orElseThrow();
        assertEquals("retail-v1", result.packKey(),
                "an update that omits pack_key must not erase an existing reference");
        assertEquals("purchase_order", result.conceptKey(),
                "an update that omits concept_key must not erase an existing reference");
        assertEquals("updated description", result.description());
    }

    /** Sanity check: an entity nobody has ever mapped to a concept stays unmapped, not defaulted. */
    @Test
    void neverMappedEntityStaysUnmappedOnPackAndConceptKey() {
        repository.saveEntity(entity("standalone-entity", null));

        BusinessEntity result = repository.findEntityByKey("standalone-entity").orElseThrow();
        assertNull(result.packKey());
        assertNull(result.conceptKey());
    }
}
