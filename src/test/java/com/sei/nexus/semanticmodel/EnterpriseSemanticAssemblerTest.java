package com.sei.nexus.semanticmodel;

import com.sei.nexus.enterprise.DataColumn;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0003 semantic model — Phase 1A. Verifies the {@link EnterpriseSemanticAssembler}
 * projects Enterprise Map persistence into the canonical semantic model: objects compose
 * attributes, each attribute has a stable {@code attributeKey}, and roles derive from
 * existing column metadata. Hand-rolled fakes; no database.
 */
class EnterpriseSemanticAssemblerTest {

    static class FakeEnterpriseMap extends EnterpriseMapRepository {
        List<String> seenKeys;
        final List<DataObject> objects;
        final Map<String, List<DataColumn>> columnsByObject;
        FakeEnterpriseMap(List<DataObject> objects, Map<String, List<DataColumn>> columns) {
            super(null); this.objects = objects; this.columnsByObject = columns;
        }
        @Override public List<DataObject> findDataObjectsByConnectionKeys(List<String> keys) {
            this.seenKeys = keys; return objects;
        }
        @Override public List<DataColumn> findColumnsByObject(String objectKey) {
            return columnsByObject.getOrDefault(objectKey, List.of());
        }
    }

    private static DataObject inventoryBalance() {
        return new DataObject("obj-inv", "PLATFORM", "Inventory Balance", "conn-1",
                "retail_core", "inventory_balances", "Inventory Balance", "Inventory on hand",
                "id", "status", "", "status", "use for stock levels", "", "",
                100, false, "SCANNED", 1, Instant.now(), Instant.now());
    }

    private static DataColumn col(String key, String name, String type,
                                  boolean id, boolean status, boolean filterable, String meaning) {
        return new DataColumn(key, "obj-inv", name, type, false, meaning,
                id, status, false, false, filterable, type, null, "INFERRED",
                Instant.now(), Instant.now());
    }

    private EnterpriseSemanticAssembler assemblerWith(FakeEnterpriseMap repo) {
        return new EnterpriseSemanticAssembler(repo);
    }

    @Test
    void assemblesObjectsComposedOfAttributesWithDerivedRoles() {
        FakeEnterpriseMap repo = new FakeEnterpriseMap(
                List.of(inventoryBalance()),
                Map.of("obj-inv", List.of(
                        col("col-id",     "id",           "integer",           true,  false, false, ""),
                        col("col-status", "status",       "character varying", false, true,  true,  ""),
                        col("col-onhand", "on_hand_qty",  "integer",           false, false, false, "On Hand Quantity"),
                        col("col-name",   "product_name", "character varying", false, false, false, ""))));

        SemanticModel sm = assemblerWith(repo).assemble(List.of("conn-1"));
        List<BusinessObject> model = sm.objects();

        // read against the agent's connection scope
        assertEquals(List.of("conn-1"), repo.seenKeys);

        // objects compose attributes
        assertEquals(1, model.size());
        BusinessObject inv = model.get(0);
        assertEquals("obj-inv", inv.objectKey());
        assertEquals("Inventory Balance", inv.businessName());
        assertEquals(4, inv.attributes().size());
        assertTrue(inv.relationships().isEmpty(), "relationships arrive in Phase 3");

        // every attribute has a stable attributeKey (the DataColumn's stable key)
        assertTrue(inv.attributes().stream().allMatch(a -> a.attributeKey() != null && !a.attributeKey().isBlank()));

        // role derivation
        assertEquals(AttributeRole.IDENTIFIER, roleOf(inv, "col-id"));
        assertEquals(AttributeRole.DIMENSION,  roleOf(inv, "col-status"));
        assertEquals(AttributeRole.MEASURE,    roleOf(inv, "col-onhand"), "numeric, unflagged → measure");
        assertEquals(AttributeRole.ATTRIBUTE,  roleOf(inv, "col-name"));

        // the reported column is a first-class MEASURE with a stable key and business name
        BusinessAttribute onHand = attr(inv, "col-onhand");
        assertEquals("col-onhand", onHand.attributeKey());
        assertEquals("On Hand Quantity", onHand.businessName(), "businessMeaning becomes the business name");
        assertEquals(AttributeRole.MEASURE, onHand.role());
    }

    @Test
    void emptyScopeAndColumnlessObjectAreSafe() {
        assertTrue(assemblerWith(new FakeEnterpriseMap(List.of(), Map.of()))
                .assemble(List.of()).objects().isEmpty());

        FakeEnterpriseMap repo = new FakeEnterpriseMap(List.of(inventoryBalance()), Map.of());
        List<BusinessObject> model = assemblerWith(repo).assemble(List.of("conn-1")).objects();
        assertEquals(1, model.size());
        assertTrue(model.get(0).attributes().isEmpty(), "an unscanned object yields no attributes, not an error");
    }

    @Test
    void businessObjectAttributesAreImmutable() {
        BusinessObject o = new BusinessObject("k", "Obj", "", List.of(
                new BusinessAttribute("a1", "A1", AttributeRole.MEASURE)), List.of());
        assertThrows(UnsupportedOperationException.class,
                () -> o.attributes().add(new BusinessAttribute("a2", "A2", AttributeRole.DIMENSION)));
    }

    private static AttributeRole roleOf(BusinessObject o, String attributeKey) {
        return attr(o, attributeKey).role();
    }

    private static BusinessAttribute attr(BusinessObject o, String attributeKey) {
        return o.attributes().stream().filter(a -> a.attributeKey().equals(attributeKey))
                .findFirst().orElseThrow();
    }
}
