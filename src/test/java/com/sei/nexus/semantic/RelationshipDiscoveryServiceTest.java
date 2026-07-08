package com.sei.nexus.semantic;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PRO-9 — schema-qualified relationship discovery.
 *
 * Verifies that {@link RelationshipDiscoveryService} generates join_guidance with the
 * joined table qualified by the discovery schema, and that tenants on the default
 * schema (blank/null schemaName) keep the original unqualified form.
 *
 * The method under test is private; invoked via reflection so no production code is
 * widened for test access.
 */
class RelationshipDiscoveryServiceTest {

    private String buildJoinGuidance(String schema, String srcTable, String srcCol,
                                     String tgtTable, String tgtCol) throws Exception {
        RelationshipDiscoveryService service =
                new RelationshipDiscoveryService(null, null, null);
        Method m = RelationshipDiscoveryService.class.getDeclaredMethod(
                "buildJoinGuidance",
                String.class, String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, schema, srcTable, srcCol, tgtTable, tgtCol);
    }

    @Test
    void fkDiscoveryQualifiesJoinedTableWithSchema() throws Exception {
        // FK shape: stores.region_id REFERENCES regions.region_id in schema retail_core
        String guidance = buildJoinGuidance("retail_core", "stores", "region_id", "regions", "region_id");
        assertEquals("JOIN retail_core.regions ON regions.region_id = stores.region_id", guidance);
    }

    @Test
    void inverseHasManyAlsoQualifiesJoinedTable() throws Exception {
        // Inverse HAS_MANY: regions 1:N stores
        String guidance = buildJoinGuidance("retail_core", "regions", "region_id", "stores", "region_id");
        assertEquals("JOIN retail_core.stores ON stores.region_id = regions.region_id", guidance);
    }

    @Test
    void heuristicDiscoveryQualifiesJoinedTableWithSchema() throws Exception {
        // Heuristic shape: same column name on both sides
        String guidance = buildJoinGuidance("retail_core", "store_targets", "store_id", "stores", "store_id");
        assertEquals("JOIN retail_core.stores ON stores.store_id = store_targets.store_id", guidance);
    }

    @Test
    void blankSchemaKeepsUnqualifiedJoinForDefaultSchemaTenants() throws Exception {
        String guidance = buildJoinGuidance("", "orders", "customer_id", "customers", "customer_id");
        assertEquals("JOIN customers ON customers.customer_id = orders.customer_id", guidance);
    }

    @Test
    void nullSchemaKeepsUnqualifiedJoinForDefaultSchemaTenants() throws Exception {
        String guidance = buildJoinGuidance(null, "orders", "customer_id", "customers", "customer_id");
        assertEquals("JOIN customers ON customers.customer_id = orders.customer_id", guidance);
    }
}
