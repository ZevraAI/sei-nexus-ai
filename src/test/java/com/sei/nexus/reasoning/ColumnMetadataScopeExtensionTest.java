package com.sei.nexus.reasoning;

import com.sei.nexus.agentbrain.ExecutionBindings;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.ExecutionContractBuilder;
import com.sei.nexus.agentbrain.PromptAssembler;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.SemanticView;
import com.sei.nexus.enterprise.DataColumn;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.semanticmodel.AttributeRole;
import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scope extension on a validated metadata request.
 *
 * <p>The planner (the LLM) asks for an object concept selection did not include; Java confirms it
 * exists, by exact identity, in the catalog of the SAME already-approved connections — and then
 * (and only then) the object becomes executable for that run. These tests pin the mechanics and
 * their bounds. Fixtures are domain-neutral on purpose: the mechanism must hold for any industry.
 */
class ColumnMetadataScopeExtensionTest {

    private static final String CONN = "conn-1";
    private static final SqlTableReferenceExtractor EXTRACTOR = new SqlTableReferenceExtractor();

    private static BusinessObject object(String objectKey, String businessName, String table, List<String> columns,
                                         Map<String, ExecutionBindings.ExecutionTarget> attributeBindingsOut) {
        List<BusinessAttribute> attributes = new ArrayList<>();
        for (String c : columns) {
            String attrKey = objectKey + "." + c;
            attributes.add(new BusinessAttribute(attrKey, c, AttributeRole.ATTRIBUTE));
            attributeBindingsOut.put(attrKey, new ExecutionBindings.ExecutionTarget("SQL", CONN, "public", table, c));
        }
        return new BusinessObject(objectKey, businessName, null, null, attributes, List.of());
    }

    /** A contract that resolved only an "order lines" object. */
    private static ExecutionContract orderLinesOnly() {
        Map<String, ExecutionBindings.ExecutionTarget> attributeBindings = new LinkedHashMap<>();
        BusinessObject lines = object("obj-order-lines", "Order Line Item", "order_lines",
                List.of("id", "ordered_qty", "order_id"), attributeBindings);
        return new ExecutionContract("ctr-1", Instant.now(), "agent-1", List.of(CONN), "hash",
                new SemanticView(List.of(lines)),
                new ExecutionBindings(
                        Map.of("obj-order-lines", new ExecutionBindings.ExecutionTarget("SQL", CONN, "public", "order_lines", null)),
                        attributeBindings,
                        Set.of(new ExecutionBindings.ApprovedAsset(CONN, EXTRACTOR.canonical("public.order_lines")),
                                new ExecutionBindings.ApprovedAsset(CONN, EXTRACTOR.canonical("order_lines")))));
    }

    private static DataObject dataObject(String objectKey, String businessName, String table) {
        return new DataObject(objectKey, "domain-1", table, CONN, "public", table, businessName,
                null, null, null, null, null, null, null, null, null, false, "SCANNED", 1,
                Instant.now(), Instant.now());
    }

    private static DataColumn dataColumn(String objectKey, String columnName) {
        return new DataColumn(objectKey + "." + columnName, objectKey, columnName, "text", true,
                null, false, false, false, false, false, null, null,
                DataColumn.ROLE_INFERRED, Instant.now(), Instant.now());
    }

    private static ColumnMetadataRequestHandler handlerWithCatalog(List<List<String>> seenConnectionKeys) {
        EnterpriseMapRepository repo = new EnterpriseMapRepository(null) {
            @Override public List<DataObject> findDataObjectsByConnectionKeys(List<String> connectionKeys) {
                if (seenConnectionKeys != null) seenConnectionKeys.add(connectionKeys);
                return List.of(dataObject("obj-header", "Order", "orders"));
            }
            @Override public List<DataColumn> findColumnsByObject(String objectKey) {
                return List.of(dataColumn(objectKey, "id"), dataColumn(objectKey, "status"), dataColumn(objectKey, "order_number"));
            }
        };
        return new ColumnMetadataRequestHandler(new PromptContextBuilder(), new PromptAssembler(),
                new EnterpriseSemanticAssembler(repo), new ExecutionContractBuilder(EXTRACTOR));
    }

    // ── resolve(): where was the object found? ───────────────────────────────────────────────

    @Test
    void anObjectAlreadyInScopeIsReportedAsInScopeWithNothingToAdd() {
        Optional<ColumnMetadataRequestHandler.Resolution> r =
                handlerWithCatalog(null).resolve(orderLinesOnly(), "order_lines");

        assertTrue(r.isPresent());
        assertFalse(r.get().outsideCurrentScope());
        assertNull(r.get().additions());
        assertTrue(r.get().columns().contains("ordered_qty"));
    }

    @Test
    void anObjectOnlyInTheCatalogIsReportedAsOutsideScopeWithExactlyThatObjectToAdd() {
        Optional<ColumnMetadataRequestHandler.Resolution> r =
                handlerWithCatalog(null).resolve(orderLinesOnly(), "orders");

        assertTrue(r.isPresent());
        assertTrue(r.get().outsideCurrentScope());
        assertEquals(1, r.get().additions().objects().size(), "exactly the requested object, nothing else");
        assertEquals("orders", r.get().additions().objectTargets().get("obj-header").table());
        assertTrue(r.get().columns().contains("order_number"));
    }

    @Test
    void anUnknownObjectStillResolvesToNothing() {
        assertTrue(handlerWithCatalog(null).resolve(orderLinesOnly(), "no_such_table").isEmpty());
    }

    @Test
    void resolveAndResolveColumnsAgreeOnTheRenderedColumns() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(null);
        assertEquals(h.resolveColumns(orderLinesOnly(), "orders").orElseThrow(),
                h.resolve(orderLinesOnly(), "orders").orElseThrow().columns());
    }

    // ── extend(): the derived contract ───────────────────────────────────────────────────────

    @Test
    void extendingMakesTheRequestedObjectExecutableAndLeavesTheBaseUntouched() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(null);
        ExecutionContract base = orderLinesOnly();
        ColumnMetadataRequestHandler.Resolution r = h.resolve(base, "orders").orElseThrow();

        ExecutionContract extended = h.extend(base, r);

        assertTrue(extended.executionBindings().isApproved(CONN, EXTRACTOR.canonical("public.orders")),
                "the schema-qualified identity is approved");
        assertTrue(extended.executionBindings().isApproved(CONN, EXTRACTOR.canonical("public.order_lines")),
                "everything that was approved still is");
        assertFalse(base.executionBindings().isApproved(CONN, EXTRACTOR.canonical("public.orders")),
                "the base contract is immutable — never mutated");
        assertEquals(2, extended.semanticView().businessObjects().size());
        assertEquals(1, base.semanticView().businessObjects().size());
    }

    @Test
    void theColumnExistenceGateKnowsTheAddedObjectsColumns() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(null);
        ExecutionContract base = orderLinesOnly();

        ExecutionContract extended = h.extend(base, h.resolve(base, "orders").orElseThrow());

        Set<String> cols = extended.columnsByTable(CONN).get("orders");
        assertNotNull(cols, "without attribute bindings the runtime would reject every column as invalid");
        assertTrue(cols.contains("order_number"));
        assertTrue(cols.contains("status"));
        assertFalse(base.columnsByTable(CONN).containsKey("orders"));
    }

    @Test
    void extendingWithAnInScopeResolutionReturnsTheSameContract() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(null);
        ExecutionContract base = orderLinesOnly();

        assertSame(base, h.extend(base, h.resolve(base, "order_lines").orElseThrow()));
    }

    @Test
    void extendingIsIdempotent() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(null);
        ExecutionContract base = orderLinesOnly();
        ColumnMetadataRequestHandler.Resolution r = h.resolve(base, "orders").orElseThrow();

        ExecutionContract once = h.extend(base, r);
        ExecutionContract twice = h.extend(once, r);

        assertSame(once, twice, "re-adding an object already present is a no-op");
        assertEquals(once.executionBindings().approvedAssets(), twice.executionBindings().approvedAssets());
    }

    @Test
    void anObjectAddedByARequestIsThenReportedAsInScope() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(null);
        ExecutionContract extended = h.extend(orderLinesOnly(), h.resolve(orderLinesOnly(), "orders").orElseThrow());

        assertFalse(h.resolve(extended, "orders").orElseThrow().outsideCurrentScope());
    }

    @Test
    void nullInputsAreHandledWithoutThrowing() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(null);
        assertNull(h.extend(null, null));
        ExecutionContract base = orderLinesOnly();
        assertSame(base, h.extend(base, null));
        assertTrue(h.resolve(null, "orders").isEmpty());
    }

    // ── the bounds ───────────────────────────────────────────────────────────────────────────

    @Test
    void theCatalogIsQueriedOnlyForTheContractsOwnApprovedConnections() {
        List<List<String>> seen = new ArrayList<>();

        handlerWithCatalog(seen).resolve(orderLinesOnly(), "orders");

        assertEquals(List.of(List.of(CONN)), seen,
                "extension can never reach a connection the contract did not already approve");
    }

    @Test
    void extensionAddsNothingElseFromTheCatalog() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(null);
        ExecutionContract base = orderLinesOnly();

        ExecutionContract extended = h.extend(base, h.resolve(base, "orders").orElseThrow());

        assertEquals(base.executionBindings().approvedAssets().size() + 2,   // qualified + bare default-schema form
                extended.executionBindings().approvedAssets().size(),
                "exactly the one requested object's identities are added");
    }
}
