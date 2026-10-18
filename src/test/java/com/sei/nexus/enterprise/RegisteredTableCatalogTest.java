package com.sei.nexus.enterprise;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisteredTableCatalogTest {

    private static DataObject object(String key, String schema, String table, String business) {
        return new DataObject(key, "d", "e", "conn", schema, table, business, null, null, null, null,
                null, null, null, null, null, false, "SCANNED", 1, null, null);
    }

    private static DataColumn column(String objectKey, String name) {
        return new DataColumn("c-" + name, objectKey, name, "text", true, null, false, false, false,
                false, false, null, null, null, null, null);
    }

    /** Serves registered objects for one connection; throws if anything else is asked. */
    private static final class FakeRepo extends EnterpriseMapRepository {
        FakeRepo() { super(null); }

        @Override public List<DataObject> findDataObjectsByConnection(String connectionKey) {
            return "conn".equals(connectionKey)
                    ? List.of(object("o1", "retail_core", "inventory_balances", "Inventory Balances"),
                              object("o2", null, "plain_table", null))
                    : List.of();
        }

        @Override public List<DataColumn> findColumnsByObject(String objectKey) {
            return "o1".equals(objectKey)
                    ? List.of(column("o1", "product_id"), column("o1", "on_hand_qty"))
                    : List.of(column("o2", "id"));
        }
    }

    private final RegisteredTableCatalog catalog = new RegisteredTableCatalog(new FakeRepo());

    @Test
    void tablesAreQualifiedWithTheirRealSchemaWhateverItIs() {
        List<RegisteredTableCatalog.Entry> t = catalog.tables("conn", false);
        assertEquals("retail_core.inventory_balances", t.get(0).qualifiedName());
        assertEquals("plain_table", t.get(1).qualifiedName());
        assertTrue(t.get(0).columns().isEmpty(), "columns only when asked for");
    }

    @Test
    void columnsAreIncludedOnRequest() {
        assertEquals(List.of("product_id", "on_hand_qty"), catalog.tables("conn", true).get(0).columns());
    }

    @Test
    void lineShowsQualifiedNameAndBusinessName() {
        assertEquals("retail_core.inventory_balances (Inventory Balances), plain_table",
                catalog.tableLine("conn"));
    }

    @Test
    void aConnectionWithNothingRegisteredYieldsNothingNeverAFixedSampleList() {
        assertEquals("", catalog.tableLine("other"));
        assertFalse(catalog.tableLine("other").contains("demo"));
    }
}
