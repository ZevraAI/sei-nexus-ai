package com.sei.nexus.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Global Pack Foundation — the Industry → Group → Global Business Concept data shape.
 * No database, no Spring context; pure Jackson parsing + record-shape assertions,
 * matching this package's existing convention (e.g. {@code IndustryPackServiceBindingTest}).
 */
class PackGlobalConceptModelTest {

    private static ObjectMapper snakeCaseMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return mapper;
    }

    // ── A. Pack parsing ──────────────────────────────────────────────────────────

    @Test
    void newGroupAndConceptFieldsParseCorrectly() throws Exception {
        String json = """
                {
                  "pack_id": "retail-v1",
                  "industry": "RETAIL",
                  "version": "1.1.0",
                  "groups": [
                    {
                      "group_key": "procurement",
                      "group_name": "Procurement",
                      "concepts": [
                        {
                          "name": "Purchase Order",
                          "concept_key": "purchase_order",
                          "status": "ACTIVE",
                          "aliases": ["po", "order header"],
                          "table_patterns": ["purchase_order", "po_header"],
                          "key_column_patterns": ["po_id"],
                          "description": "desc",
                          "operational_meaning": "meaning"
                        }
                      ]
                    }
                  ]
                }
                """;
        IndustryPack pack = snakeCaseMapper().readValue(json, IndustryPack.class);

        assertNotNull(pack.groups(), "groups must parse when present");
        assertEquals(1, pack.groups().size());
        PackGroup group = pack.groups().get(0);
        assertEquals("procurement", group.groupKey());
        assertEquals("Procurement", group.groupName());
        assertEquals(1, group.concepts().size());

        PackEntity concept = group.concepts().get(0);
        assertEquals("purchase_order", concept.conceptKey());
        assertEquals("ACTIVE", concept.status());
        assertEquals("Purchase Order", concept.name());
        // Preserved existing fields — untouched by this evolution.
        assertEquals(List.of("po", "order header"), concept.aliases());
        assertEquals(List.of("purchase_order", "po_header"), concept.tablePatterns());
        assertEquals(List.of("po_id"), concept.keyColumnPatterns());
    }

    @Test
    void conceptStatusIsOptionalAndTreatedAsAbsentWhenMissing() throws Exception {
        String json = """
                {
                  "pack_id": "retail-v1", "industry": "RETAIL", "version": "1.0.0",
                  "groups": [
                    { "group_key": "procurement", "group_name": "Procurement",
                      "concepts": [
                        { "name": "Supplier", "aliases": [], "table_patterns": [],
                          "key_column_patterns": [], "description": "d", "operational_meaning": "m" }
                      ]
                    }
                  ]
                }
                """;
        IndustryPack pack = snakeCaseMapper().readValue(json, IndustryPack.class);
        PackEntity concept = pack.groups().get(0).concepts().get(0);

        assertNull(concept.conceptKey(), "a concept authored before this field existed must parse with null, not fail");
        assertNull(concept.status(), "missing status must parse as null — every current reader must treat that as ACTIVE");
    }

    @Test
    void everyExistingPackFileStillParsesUnchanged() throws Exception {
        // Real, shipped pack files. Confirms the new `groups` field (absent from all of them)
        // does not break loading, and every pre-existing field (entities, vocabulary, etc.) is
        // still fully populated.
        //
        // Retail Pack V2: retail-v1.json is now the first (and, as of this task, only) shipped
        // pack to intentionally populate concept_key/status on every entity — the explicit,
        // pack-authored canonical concept identities this task added (see IndustryPackService /
        // BusinessObjectBatchAnalyzer#extractConcepts's slugify-fallback javadoc: an explicit
        // concept_key simply skips that fallback, it does not change how the LLM decides which
        // tenant object maps to it). Every OTHER shipped pack is untouched by this task and must
        // still parse with null concept_key/status exactly as before.
        String[] packFilesWithoutConceptKeys = {
                "logistics-v1.json", "finance-v1.json",
                "healthcare-v1.json", "hospitality-v1.json", "servicenow-itsm-v1.json"
        };
        ObjectMapper mapper = snakeCaseMapper();
        for (String file : packFilesWithoutConceptKeys) {
            try (InputStream is = getClass().getResourceAsStream("/industry-packs/" + file)) {
                assertNotNull(is, "pack resource must exist: " + file);
                IndustryPack pack = mapper.readValue(is, IndustryPack.class);

                assertNull(pack.groups(),
                        file + ": a pack file authored before this change must parse groups() as null");
                assertNotNull(pack.entities(), file + ": existing flat entities list must still populate");
                assertFalse(pack.entities().isEmpty(), file + ": existing entities must not become empty");
                for (PackEntity e : pack.entities()) {
                    assertNull(e.conceptKey(),
                            file + "/" + e.name() + ": no existing entity in a shipped pack file has a concept_key");
                    assertNull(e.status(),
                            file + "/" + e.name() + ": no existing entity in a shipped pack file has a status");
                    // Existing fields must still be present and non-null — proves the six
                    // original fields are completely unaffected by this evolution.
                    assertNotNull(e.name());
                    assertNotNull(e.description());
                }
            }
        }

        // retail-v1.json — Retail Pack V2: every entity now carries an explicit, stable,
        // pack-authored concept_key (never guessed from the tenant object — see
        // RetailPackV2SemanticCatalogTest for the full content-level assertions), and status
        // defaults to ACTIVE. groups() is still null/unused, entities/description are still
        // fully populated — only concept_key/status moved from "absent" to "explicitly set."
        try (InputStream is = getClass().getResourceAsStream("/industry-packs/retail-v1.json")) {
            assertNotNull(is, "pack resource must exist: retail-v1.json");
            IndustryPack pack = mapper.readValue(is, IndustryPack.class);

            assertNull(pack.groups(), "retail-v1.json: groups() is still unused by Retail Pack V2");
            assertNotNull(pack.entities());
            assertFalse(pack.entities().isEmpty());
            for (PackEntity e : pack.entities()) {
                assertNotNull(e.conceptKey(),
                        "retail-v1.json/" + e.name() + ": Retail Pack V2 explicitly sets concept_key on every entity");
                assertNotNull(e.name());
                assertNotNull(e.description());
            }
        }
    }

    // ── B. Concept identity ──────────────────────────────────────────────────────

    @Test
    void sameLocalConceptKeyInDifferentPacksIsNotTheSameConcept() throws Exception {
        String retailJson = """
                { "pack_id": "retail-v1", "industry": "RETAIL", "version": "1.0.0",
                  "groups": [{ "group_key": "sales", "group_name": "Sales",
                    "concepts": [{ "name": "Order", "concept_key": "order", "status": "ACTIVE",
                      "aliases": [], "table_patterns": [], "key_column_patterns": [],
                      "description": "A customer sales order", "operational_meaning": "m" }] }] }
                """;
        String logisticsJson = """
                { "pack_id": "logistics-v1", "industry": "LOGISTICS", "version": "1.0.0",
                  "groups": [{ "group_key": "fulfillment", "group_name": "Fulfillment",
                    "concepts": [{ "name": "Order", "concept_key": "order", "status": "ACTIVE",
                      "aliases": [], "table_patterns": [], "key_column_patterns": [],
                      "description": "A shipment/movement order", "operational_meaning": "m" }] }] }
                """;
        ObjectMapper mapper = snakeCaseMapper();
        IndustryPack retail = mapper.readValue(retailJson, IndustryPack.class);
        IndustryPack logistics = mapper.readValue(logisticsJson, IndustryPack.class);

        String retailConceptKey = retail.groups().get(0).concepts().get(0).conceptKey();
        String logisticsConceptKey = logistics.groups().get(0).concepts().get(0).conceptKey();
        assertEquals("order", retailConceptKey);
        assertEquals("order", logisticsConceptKey);
        assertEquals(retailConceptKey, logisticsConceptKey,
                "the bare local concept_key strings ARE identical — proving identity must never be "
                        + "just concept_key alone");

        // The stable, resolvable identity is the COMPOSITE (pack_id, concept_key) — these two
        // composite identities must be distinct even though the local keys match.
        String retailIdentity = retail.packId() + ":" + retailConceptKey;
        String logisticsIdentity = logistics.packId() + ":" + logisticsConceptKey;
        assertNotEquals(retailIdentity, logisticsIdentity,
                "retail-v1's 'order' and logistics-v1's 'order' must be distinguishable composite identities");

        Set<String> distinctConcepts = Set.of(retailIdentity, logisticsIdentity);
        assertEquals(2, distinctConcepts.size(),
                "two independently-defined concepts must never collapse into one, even sharing a local key");

        // And their descriptions genuinely differ — proving they really are different concepts,
        // not merely different identities for the same thing.
        assertNotEquals(
                retail.groups().get(0).concepts().get(0).description(),
                logistics.groups().get(0).concepts().get(0).description());
    }
}
