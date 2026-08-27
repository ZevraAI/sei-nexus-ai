package com.sei.nexus.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.enterprise.DataColumn;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.EntityRelationship;
import com.sei.nexus.semantic.SemanticRepository;
import com.sei.nexus.sql.DynamicSqlService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Global Concept Resolution — Phase 1, READ-ONLY. Hand-rolled fakes, no DB, no Mockito,
 * matching this project's convention. Nothing under test ever calls a persistence-writing
 * method — every fake below either has no write methods overridden at all, or (for the ones
 * that exist on the real class) is simply never invoked by {@link GlobalConceptResolver}.
 */
class GlobalConceptResolverTest {

    // ── fakes ────────────────────────────────────────────────────────────────────

    static class FakeEnterpriseMapRepository extends EnterpriseMapRepository {
        Map<String, List<DataObject>> objectsByConnection = new LinkedHashMap<>();
        Map<String, List<DataColumn>> columnsByObject = new LinkedHashMap<>();

        FakeEnterpriseMapRepository() { super(null); }

        @Override public List<DataObject> findDataObjectsByConnection(String connectionKey) {
            return objectsByConnection.getOrDefault(connectionKey, List.of());
        }
        @Override public List<DataColumn> findColumnsByObject(String objectKey) {
            return columnsByObject.getOrDefault(objectKey, List.of());
        }
    }

    static class FakeSemanticRepository extends SemanticRepository {
        Map<String, BusinessEntity> entityByObjectKey = new LinkedHashMap<>();
        Map<String, List<EntityRelationship>> relationshipsByEntity = new LinkedHashMap<>();

        FakeSemanticRepository() { super(null); }

        @Override public List<EntityRelationship> findRelationshipsByEntity(String entityKey) {
            return relationshipsByEntity.getOrDefault(entityKey, List.of());
        }

        @Override public Optional<BusinessEntity> findActiveByPrimaryObjectKey(String objectKey) {
            return Optional.ofNullable(entityByObjectKey.get(objectKey));
        }
    }

    static class FakeDynamicSqlService extends DynamicSqlService {
        Map<String, TableDescription> descriptionByTable = new LinkedHashMap<>();
        boolean throwOnDescribe = false;

        FakeDynamicSqlService() { super(null); }

        @Override
        public TableDescription describeTableWithComments(String connectionKey, String schemaName, String tableName) {
            if (throwOnDescribe) throw new RuntimeException("simulated unreachable source connection");
            TableDescription d = descriptionByTable.get(tableName);
            return d != null ? d : new TableDescription(List.of(), null);
        }
    }

    static class FakeIndustryPackRepository extends IndustryPackRepository {
        Map<String, TenantPack> activeAssignmentByConnection = new LinkedHashMap<>();
        Map<String, IndustryPack> packsByKey = new LinkedHashMap<>();

        FakeIndustryPackRepository() { super(null, new ObjectMapper()); }

        @Override public Optional<TenantPack> findActivePackForConnection(String connectionKey) {
            return Optional.ofNullable(activeAssignmentByConnection.get(connectionKey));
        }
        @Override public Optional<IndustryPack> findPackById(String packId) {
            return Optional.ofNullable(packsByKey.get(packId));
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private FakeEnterpriseMapRepository enterpriseMap;
    private FakeSemanticRepository semantic;
    private FakeDynamicSqlService dynamicSql;
    private FakeIndustryPackRepository packRepo;
    private GlobalConceptResolver resolver;

    private void build() {
        enterpriseMap = new FakeEnterpriseMapRepository();
        semantic = new FakeSemanticRepository();
        dynamicSql = new FakeDynamicSqlService();
        packRepo = new FakeIndustryPackRepository();
        resolver = new GlobalConceptResolver(packRepo, enterpriseMap, semantic, dynamicSql);
    }

    private static PackEntity concept(String conceptKey, String name, List<String> tablePatterns,
                                       List<String> keyColumnPatterns, List<String> aliases) {
        return new PackEntity(name, aliases, tablePatterns, keyColumnPatterns,
                "desc for " + name, "meaning for " + name, conceptKey, "ACTIVE");
    }

    private static IndustryPack retailPack() {
        PackEntity purchaseOrder = concept("purchase_order", "Purchase Order",
                List.of("purchase_order", "purchase_orders", "po_header"),
                List.of("po_id", "purchase_order_id", "po_number"),
                List.of("po", "purchase order"));
        PackEntity salesOrder = concept("sales_order", "Sales Order",
                List.of("sales_order", "sales_orders", "order_header"),
                List.of("so_id", "sales_order_id", "customer_id"),
                List.of("so", "sales order"));
        PackGroup procurement = new PackGroup("procurement", "Procurement", List.of(purchaseOrder));
        PackGroup sales = new PackGroup("sales", "Sales", List.of(salesOrder));
        return new IndustryPack("retail-v1", "RETAIL", "Retail", "1.1.0", "desc",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null,
                List.of(procurement, sales));
    }

    /** Both concepts share the SAME table_pattern "order_header" — CASE 3's ambiguity setup. */
    private static IndustryPack retailPackWithSharedOrderHeaderPattern() {
        PackEntity purchaseOrder = concept("purchase_order", "Purchase Order",
                List.of("order_header"), List.of("po_id"), List.of("po"));
        PackEntity salesOrder = concept("sales_order", "Sales Order",
                List.of("order_header"), List.of("so_id"), List.of("so"));
        PackGroup g1 = new PackGroup("procurement", "Procurement", List.of(purchaseOrder));
        PackGroup g2 = new PackGroup("sales", "Sales", List.of(salesOrder));
        return new IndustryPack("retail-v1", "RETAIL", "Retail", "1.1.0", "desc",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(g1, g2));
    }

    private static IndustryPack logisticsPack() {
        PackEntity shipment = concept("shipment", "Shipment",
                List.of("shipment", "shipments", "shipment_header"),
                List.of("shipment_id"), List.of("ship"));
        PackGroup fulfillment = new PackGroup("fulfillment", "Fulfillment", List.of(shipment));
        return new IndustryPack("logistics-v1", "LOGISTICS", "Logistics", "1.0.0", "desc",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(fulfillment));
    }

    private static BusinessEntity entity(String key, String name, String primaryObjectKey) {
        Instant now = Instant.now();
        return new BusinessEntity(key, "PLATFORM", name, "desc", primaryObjectKey,
                "meaning", "hints", "ACTIVE", "test@example.com", now, now);
    }

    private static DataObject object(String objectKey, String connectionKey, String tableName) {
        return new DataObject(objectKey, "PLATFORM", tableName, connectionKey, "public", tableName,
                tableName, "purpose", null, null, null, null, null, null, null,
                null, false, "SCANNED", 1, null, null);
    }

    private static DataColumn column(String objectKey, String columnName, boolean isIdentifier, String udtName) {
        return new DataColumn(columnName + "-key", objectKey, columnName, "varchar", false,
                null, isIdentifier, false, false, false, true, udtName, null,
                DataColumn.ROLE_INFERRED, Instant.now(), Instant.now());
    }

    // ── CASE 1 — clear via table_pattern + identifier ────────────────────────────

    @Test
    void case1_purchaseOrdersResolvesClearlyToPurchaseOrder() {
        build();
        enterpriseMap.objectsByConnection.put("conn-a", List.of(object("obj-1", "conn-a", "purchase_orders")));
        enterpriseMap.columnsByObject.put("obj-1", List.of(column("obj-1", "purchase_order_id", true, null)));
        semantic.entityByObjectKey.put("obj-1", entity("po-1", "Purchase Order", "obj-1"));

        List<BusinessObjectResolution> results = resolver.resolveAgainstPack("conn-a", retailPack());

        assertEquals(1, results.size());
        BusinessObjectResolution r = results.get(0);
        assertEquals(ResolutionOutcome.CLEAR, r.outcome());
        assertEquals(1, r.candidates().size());
        assertEquals("purchase_order", r.candidates().get(0).conceptKey());
        assertEquals(EvidenceStrength.STRONG, r.candidates().get(0).overallStrength());
    }

    // ── CASE 2 — clear via a different physical name ─────────────────────────────

    @Test
    void case2_poHeaderResolvesClearlyToPurchaseOrder() {
        build();
        enterpriseMap.objectsByConnection.put("conn-b", List.of(object("obj-2", "conn-b", "po_header")));
        enterpriseMap.columnsByObject.put("obj-2", List.of(column("obj-2", "po_number", true, null)));
        semantic.entityByObjectKey.put("obj-2", entity("po-2", "Purchase Order", "obj-2"));

        List<BusinessObjectResolution> results = resolver.resolveAgainstPack("conn-b", retailPack());

        assertEquals(ResolutionOutcome.CLEAR, results.get(0).outcome());
        assertEquals("purchase_order", results.get(0).candidates().get(0).conceptKey());
    }

    // ── CASE 3 — ambiguous ────────────────────────────────────────────────────────

    @Test
    void case3_orderHeaderIsAmbiguousBetweenPurchaseAndSalesOrder() {
        build();
        enterpriseMap.objectsByConnection.put("conn-c", List.of(object("obj-3", "conn-c", "order_header")));
        enterpriseMap.columnsByObject.put("obj-3", List.of()); // no corroborating column evidence either way
        semantic.entityByObjectKey.put("obj-3", entity("oh-1", "Order Header", "obj-3"));

        List<BusinessObjectResolution> results = resolver.resolveAgainstPack("conn-c", retailPackWithSharedOrderHeaderPattern());

        BusinessObjectResolution r = results.get(0);
        assertEquals(ResolutionOutcome.AMBIGUOUS, r.outcome());
        assertEquals(2, r.candidates().size());
        List<String> conceptKeys = r.candidates().stream().map(ConceptCandidate::conceptKey).toList();
        assertTrue(conceptKeys.contains("purchase_order"));
        assertTrue(conceptKeys.contains("sales_order"));
    }

    // ── CASE 4 — cryptic table name, strong column evidence ──────────────────────

    @Test
    void case4_crypticTableNameResolvesViaColumnEvidenceNotTableName() {
        build();
        enterpriseMap.objectsByConnection.put("conn-d", List.of(object("obj-4", "conn-d", "t_x9")));
        // t_x9 matches NO table_pattern for any concept — only column evidence can resolve this.
        enterpriseMap.columnsByObject.put("obj-4", List.of(column("obj-4", "po_number", true, null)));
        semantic.entityByObjectKey.put("obj-4", entity("cryptic-1", "T X9", "obj-4"));
        dynamicSql.descriptionByTable.put("t_x9", new DynamicSqlService.TableDescription(
                List.of(Map.of("column_name", "po_number")),
                "This table records purchase order header data."));

        List<BusinessObjectResolution> results = resolver.resolveAgainstPack("conn-d", retailPack());

        BusinessObjectResolution r = results.get(0);
        assertEquals(ResolutionOutcome.CLEAR, r.outcome(),
                "column-level and source-comment evidence alone must be able to resolve a cryptic table name");
        assertEquals("purchase_order", r.candidates().get(0).conceptKey());
        List<String> signals = r.candidates().get(0).evidence().stream().map(ConceptEvidence::signal).toList();
        assertFalse(signals.contains("table_pattern"), "no table_pattern evidence should have fired for t_x9");
        assertTrue(signals.contains("identifier_role"));
    }

    // ── CASE 5 — no useful evidence ───────────────────────────────────────────────

    @Test
    void case5_noUsefulEvidenceIsUnresolved() {
        build();
        enterpriseMap.objectsByConnection.put("conn-e", List.of(object("obj-5", "conn-e", "ord_hdr")));
        enterpriseMap.columnsByObject.put("obj-5", List.of(column("obj-5", "id", false, null)));
        semantic.entityByObjectKey.put("obj-5", entity("weak-1", "Ord Hdr", "obj-5"));

        List<BusinessObjectResolution> results = resolver.resolveAgainstPack("conn-e", retailPack());

        assertEquals(ResolutionOutcome.UNRESOLVED, results.get(0).outcome());
        assertTrue(results.get(0).candidates().isEmpty());
    }

    // ── CASE 6 — Logistics connection never sees Retail concepts ────────────────

    @Test
    void case6_logisticsConnectionNeverEvaluatesRetailConcepts() {
        build();
        enterpriseMap.objectsByConnection.put("conn-f", List.of(object("obj-6", "conn-f", "shipment_header")));
        enterpriseMap.columnsByObject.put("obj-6", List.of(column("obj-6", "shipment_id", true, null)));
        semantic.entityByObjectKey.put("obj-6", entity("ship-1", "Shipment Header", "obj-6"));

        List<BusinessObjectResolution> results = resolver.resolveAgainstPack("conn-f", logisticsPack());

        BusinessObjectResolution r = results.get(0);
        assertEquals(ResolutionOutcome.CLEAR, r.outcome());
        assertEquals("shipment", r.candidates().get(0).conceptKey());
        for (ConceptCandidate c : r.candidates()) {
            assertFalse(c.conceptKey().equals("purchase_order") || c.conceptKey().equals("sales_order"),
                    "a Logistics-pack resolution must never produce a Retail concept as a candidate");
        }
    }

    // ── CASE 7 — one tenant, multiple connections, each resolved against its own pack ──

    @Test
    void case7_oneTenantMultipleConnectionsEachUseOnlyTheirAssignedPack() {
        build();
        packRepo.activeAssignmentByConnection.put("conn-retail",
                new TenantPack("retail-v1", "conn-retail", "1.1.0", "Retail", "ACTIVE", Map.of(), 1.0, null, "a@b.com"));
        packRepo.activeAssignmentByConnection.put("conn-logistics",
                new TenantPack("logistics-v1", "conn-logistics", "1.0.0", "Logistics", "ACTIVE", Map.of(), 1.0, null, "a@b.com"));
        packRepo.packsByKey.put("retail-v1", retailPack());
        packRepo.packsByKey.put("logistics-v1", logisticsPack());

        enterpriseMap.objectsByConnection.put("conn-retail", List.of(object("obj-r", "conn-retail", "purchase_orders")));
        enterpriseMap.columnsByObject.put("obj-r", List.of(column("obj-r", "purchase_order_id", true, null)));
        semantic.entityByObjectKey.put("obj-r", entity("po-r", "Purchase Order", "obj-r"));

        enterpriseMap.objectsByConnection.put("conn-logistics", List.of(object("obj-l", "conn-logistics", "shipment_header")));
        enterpriseMap.columnsByObject.put("obj-l", List.of(column("obj-l", "shipment_id", true, null)));
        semantic.entityByObjectKey.put("obj-l", entity("ship-l", "Shipment", "obj-l"));

        List<BusinessObjectResolution> retailResults = resolver.resolveForConnection("conn-retail");
        List<BusinessObjectResolution> logisticsResults = resolver.resolveForConnection("conn-logistics");

        assertEquals("purchase_order", retailResults.get(0).candidates().get(0).conceptKey());
        assertEquals("retail-v1", retailResults.get(0).packKey());
        assertEquals("shipment", logisticsResults.get(0).candidates().get(0).conceptKey());
        assertEquals("logistics-v1", logisticsResults.get(0).packKey());
    }

    @Test
    void case7b_connectionWithNoActivePackAssignmentThrows() {
        build();
        assertThrows(com.sei.nexus.common.NexusException.class,
                () -> resolver.resolveForConnection("conn-unassigned"));
    }

    // ── CASE 8 — two unrelated tenants, same industry, independent resolution ───

    @Test
    void case8_twoUnrelatedTenantsIndependentlyResolveToTheSameGlobalConcept() {
        build();
        // Tenant A
        enterpriseMap.objectsByConnection.put("conn-tenant-a", List.of(object("obj-a", "conn-tenant-a", "po_header")));
        enterpriseMap.columnsByObject.put("obj-a", List.of(column("obj-a", "po_number", true, null)));
        semantic.entityByObjectKey.put("obj-a", entity("po-a", "Purchase Order", "obj-a"));
        List<BusinessObjectResolution> tenantAResults = resolver.resolveAgainstPack("conn-tenant-a", retailPack());

        // Tenant B — a completely separate resolver invocation, different fake data underneath;
        // nothing here shares state with tenant A's resolution above.
        build();
        enterpriseMap.objectsByConnection.put("conn-tenant-b", List.of(object("obj-b", "conn-tenant-b", "purchase_orders")));
        enterpriseMap.columnsByObject.put("obj-b", List.of(column("obj-b", "purchase_order_id", true, null)));
        semantic.entityByObjectKey.put("obj-b", entity("po-b", "Purchase Order", "obj-b"));
        List<BusinessObjectResolution> tenantBResults = resolver.resolveAgainstPack("conn-tenant-b", retailPack());

        assertEquals(ResolutionOutcome.CLEAR, tenantAResults.get(0).outcome());
        assertEquals(ResolutionOutcome.CLEAR, tenantBResults.get(0).outcome());
        assertEquals("purchase_order", tenantAResults.get(0).candidates().get(0).conceptKey());
        assertEquals("purchase_order", tenantBResults.get(0).candidates().get(0).conceptKey());
        assertNotEquals(tenantAResults.get(0).entityKey(), tenantBResults.get(0).entityKey(),
                "the two tenants' Business Entities must remain distinct even though the Global Concept matches");
    }

    // ── graceful degradation: source connection unreachable ──────────────────────

    @Test
    void sourceCommentRetrievalFailureDoesNotFailResolution() {
        build();
        dynamicSql.throwOnDescribe = true;
        enterpriseMap.objectsByConnection.put("conn-g", List.of(object("obj-7", "conn-g", "purchase_orders")));
        enterpriseMap.columnsByObject.put("obj-7", List.of(column("obj-7", "purchase_order_id", true, null)));
        semantic.entityByObjectKey.put("obj-7", entity("po-7", "Purchase Order", "obj-7"));

        List<BusinessObjectResolution> results = assertDoesNotThrow(
                () -> resolver.resolveAgainstPack("conn-g", retailPack()));

        assertEquals(ResolutionOutcome.CLEAR, results.get(0).outcome(),
                "a stale/unreachable source connection must not prevent resolution via other evidence");
    }

    // ── Phase 1B — relationship evidence excludes outgoing FK columns from identity evidence ──

    private static EntityRelationship outgoingRelationship(String sourceEntityKey, String targetEntityKey, String sourceColumn) {
        return new EntityRelationship("rel-" + sourceEntityKey + "-" + targetEntityKey, sourceEntityKey, targetEntityKey,
                "REFERENCES", sourceColumn, "id", "join guidance", false, null, Instant.now());
    }

    /** The real Retail scenario this phase exists to fix: purchase_orders' own outgoing FKs. */
    private static IndustryPack retailPackWithReferencedConcepts() {
        PackEntity purchaseOrder = concept("purchase_order", "Purchase Order",
                List.of("purchase_order", "purchase_orders"),
                List.of("po_id", "purchase_order_id", "po_number"), List.of("po"));
        PackEntity supplier = concept("supplier", "Supplier",
                List.of("supplier", "suppliers"), List.of("supplier_id"), List.of("vendor"));
        PackEntity warehouse = concept("warehouse", "Warehouse",
                List.of("warehouse", "warehouses"), List.of("warehouse_id"), List.of("dc"));
        PackEntity fiscalPeriod = concept("fiscal_period", "Fiscal Period",
                List.of("fiscal_period", "fiscal_periods"), List.of("fiscal_period_id"), List.of("period"));
        PackGroup procurement = new PackGroup("procurement", "Procurement", List.of(purchaseOrder, supplier, warehouse));
        PackGroup merchandising = new PackGroup("merchandising", "Merchandising", List.of(fiscalPeriod));
        return new IndustryPack("retail-v1", "RETAIL", "Retail", "1.1.0", "desc",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(procurement, merchandising));
    }

    private void buildPurchaseOrderScenario() {
        build();
        enterpriseMap.objectsByConnection.put("conn-po", List.of(object("obj-po", "conn-po", "purchase_orders")));
        enterpriseMap.columnsByObject.put("obj-po", List.of(
                column("obj-po", "po_number", true, null),
                column("obj-po", "supplier_id", true, null),
                column("obj-po", "destination_warehouse_id", true, null),
                column("obj-po", "fiscal_period_id", true, null)));
        semantic.entityByObjectKey.put("obj-po", entity("purchase-order", "Purchase Order", "obj-po"));
        semantic.relationshipsByEntity.put("purchase-order", List.of(
                outgoingRelationship("purchase-order", "supplier", "supplier_id"),
                outgoingRelationship("purchase-order", "warehouse", "destination_warehouse_id"),
                outgoingRelationship("purchase-order", "fiscal-period", "fiscal_period_id")));
    }

    @Test
    void supplierIdDoesNotCreateSupplierIdentityEvidenceWhenAnOutgoingRelationshipExists() {
        buildPurchaseOrderScenario();

        List<BusinessObjectResolution> results = resolver.resolveAgainstPack("conn-po", retailPackWithReferencedConcepts());

        List<String> conceptKeys = results.get(0).candidates().stream().map(ConceptCandidate::conceptKey).toList();
        assertFalse(conceptKeys.contains("supplier"),
                "supplier_id is a confirmed outgoing reference — it must not make Supplier a candidate at all");
    }

    @Test
    void destinationWarehouseIdDoesNotCreateWarehouseIdentityEvidence() {
        buildPurchaseOrderScenario();

        List<BusinessObjectResolution> results = resolver.resolveAgainstPack("conn-po", retailPackWithReferencedConcepts());

        List<String> conceptKeys = results.get(0).candidates().stream().map(ConceptCandidate::conceptKey).toList();
        assertFalse(conceptKeys.contains("warehouse"),
                "destination_warehouse_id is a confirmed outgoing reference — it must not make Warehouse a candidate");
    }

    @Test
    void fiscalPeriodIdDoesNotCreateFiscalPeriodIdentityEvidence() {
        buildPurchaseOrderScenario();

        List<BusinessObjectResolution> results = resolver.resolveAgainstPack("conn-po", retailPackWithReferencedConcepts());

        List<String> conceptKeys = results.get(0).candidates().stream().map(ConceptCandidate::conceptKey).toList();
        assertFalse(conceptKeys.contains("fiscal_period"),
                "fiscal_period_id is a confirmed outgoing reference — it must not make Fiscal Period a candidate");
    }

    @Test
    void poNumberRemainsEligibleAsPurchaseOrderIdentityEvidenceBecauseItIsNotAnOutgoingRelationshipColumn() {
        buildPurchaseOrderScenario();

        List<BusinessObjectResolution> results = resolver.resolveAgainstPack("conn-po", retailPackWithReferencedConcepts());

        BusinessObjectResolution r = results.get(0);
        assertEquals(ResolutionOutcome.CLEAR, r.outcome(),
                "with the FK columns correctly excluded, Purchase Order must resolve CLEAR, not CONFLICTING — "
                        + "this is the exact real-tenant regression this phase fixes");
        assertEquals(1, r.candidates().size());
        ConceptCandidate purchaseOrder = r.candidates().get(0);
        assertEquals("purchase_order", purchaseOrder.conceptKey());
        assertTrue(purchaseOrder.evidence().stream().anyMatch(e -> e.signal().equals("identifier_role")
                        && e.detail().contains("po_number")),
                "po_number must still contribute identifier_role evidence — it has no outgoing relationship row");
    }

    @Test
    void businessObjectWithNoRelationshipRowsPreservesExistingResolverBehavior() {
        build();
        // Identical to case1, but explicitly with an EMPTY relationshipsByEntity map (the
        // default) — proving the absence of relationship data changes nothing.
        enterpriseMap.objectsByConnection.put("conn-a", List.of(object("obj-1", "conn-a", "purchase_orders")));
        enterpriseMap.columnsByObject.put("obj-1", List.of(column("obj-1", "purchase_order_id", true, null)));
        semantic.entityByObjectKey.put("obj-1", entity("po-1", "Purchase Order", "obj-1"));
        assertTrue(semantic.relationshipsByEntity.isEmpty(), "precondition: no relationship rows configured");

        List<BusinessObjectResolution> results = resolver.resolveAgainstPack("conn-a", retailPack());

        assertEquals(ResolutionOutcome.CLEAR, results.get(0).outcome());
        assertEquals("purchase_order", results.get(0).candidates().get(0).conceptKey());
    }

    @Test
    void resolverNeverCallsAnyWritePath() {
        // FakeSemanticRepository/FakeEnterpriseMapRepository/FakeIndustryPackRepository above
        // override ONLY read methods. If GlobalConceptResolver ever called a write method
        // (saveEntity, saveTenantPack, saveDataObject, saveColumn, createRelationship, ...), it
        // would fall through to the REAL base-class implementation with a null JdbcTemplate and
        // throw immediately — this test simply exercises the full resolve path once more and
        // confirms it completes without any such failure, across every fake in this suite.
        buildPurchaseOrderScenario();
        assertDoesNotThrow(() -> resolver.resolveAgainstPack("conn-po", retailPackWithReferencedConcepts()));
    }
}
