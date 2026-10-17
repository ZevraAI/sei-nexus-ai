package com.sei.nexus.agentbrain;

import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;
import com.sei.nexus.semanticmodel.SemanticModel;
import com.sei.nexus.semanticmodel.AttributeRole;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0003 semantic model, Phase 1B — the deterministic {@link ExecutionContractBuilder}
 * compiles a {@link ResolvedBusinessModel} into an immutable {@link ExecutionContract}: the
 * SemanticView carries object-owned attributes; ExecutionBindings carry object→table and
 * attribute→column targets plus precomputed approvedAssets. No repository/assembler access.
 */
class ExecutionContractBuilderTest {

    private final ExecutionContractBuilder builder =
            new ExecutionContractBuilder(new SqlTableReferenceExtractor());

    private static BusinessObject inventory() {
        return new BusinessObject("obj-inv", "Inventory Balance", "stock levels",
                List.of(new BusinessAttribute("col-onhand", "On Hand Quantity", AttributeRole.MEASURE),
                        new BusinessAttribute("col-id", "Id", AttributeRole.IDENTIFIER)),
                List.of());
    }

    private static ResolvedBusinessModel model(String question) {
        return new ResolvedBusinessModel("agent-1", List.of("conn-1"), question,
                List.of(inventory()),
                Map.of("obj-inv", new PhysicalTable("conn-1", "retail_core", "inventory_balances")),
                Map.of("col-onhand", new PhysicalColumn("conn-1", "retail_core", "inventory_balances", "on_hand_qty"),
                       "col-id",     new PhysicalColumn("conn-1", "retail_core", "inventory_balances", "id")));
    }

    @Test
    void compilesSemanticViewExecutionBindingsAndApprovedAssets() {
        ExecutionContract c = builder.compile(model("how many inventory balances"));

        assertFalse(c.isEmpty());
        // SemanticView carries object-owned attributes (no physical detail)
        assertEquals(1, c.semanticView().businessObjects().size());
        BusinessObject inv = c.semanticView().businessObjects().get(0);
        assertEquals("Inventory Balance", inv.businessName());
        assertEquals(2, inv.attributes().size());

        // ExecutionBindings: object → table, attribute → column
        assertEquals("inventory_balances", c.executionBindings().objectBindings().get("obj-inv").table());
        assertNull(c.executionBindings().objectBindings().get("obj-inv").column(), "object target has no column");
        assertEquals("on_hand_qty", c.executionBindings().attributeBindings().get("col-onhand").column());
        assertEquals("id",          c.executionBindings().attributeBindings().get("col-id").column());

        // approvedAssets = only executable identifiers. retail_core is a non-default schema, so the
        // schema-qualified form is the sole executable identity; the bare form is NOT approved
        // (a bare reference to retail_core does not resolve).
        assertTrue(c.executionBindings().isApproved("conn-1", "RETAIL_CORE.INVENTORY_BALANCES"));
        assertFalse(c.executionBindings().isApproved("conn-1", "INVENTORY_BALANCES"),
                "bare form of a non-default-schema table is not an executable identity");
        assertFalse(c.executionBindings().isApproved("conn-1", "INVOICES"));

        // identity
        assertNotNull(c.contractId());
        assertEquals("agent-1", c.agentId());
    }

    @Test
    void emptyModelYieldsEmptyContract() {
        ExecutionContract c = builder.compile(
                new ResolvedBusinessModel("agent-1", List.of("conn-1"), "q", List.of(), Map.of(), Map.of()));
        assertTrue(c.isEmpty());
        assertTrue(c.executionBindings().approvedAssets().isEmpty());
    }

    @Test
    void semanticHashIsDeterministicOverAgentAndApprovedSurface() {
        assertEquals(
                builder.compile(model("q one")).semanticHash(),
                builder.compile(model("a different question")).semanticHash(),
                "hash is over the agent and its approved surface, not the question or contractId");
    }

    // ── Investigation-scope invariant (Phase 10B correction): Agent Brain owns the investigation
    //     scope; executionScope (the full domain/connection catalog) must NEVER be unioned into
    //     approvedAssets/objectBindings. The two tests below replace
    //     executionScopeExtendsApprovedAssetsBeyondTheNarrowedPromptSelection and
    //     emptyNarrowedSelectionWithNonEmptyExecutionScopeStillApprovesTheExecutionScopeObjects,
    //     which asserted the OLD (violating) union behavior — that behavior is intentionally
    //     eliminated, not preserved. ─────────────────────────────────────────────────────────

    private static BusinessObject purchaseOrders() {
        return new BusinessObject("obj-po", "Purchase Orders", "orders",
                List.of(new BusinessAttribute("col-po-id", "Id", AttributeRole.IDENTIFIER)), List.of());
    }

    private static BusinessObject salesOrders() {
        return new BusinessObject("obj-so", "Sales Orders", "orders",
                List.of(new BusinessAttribute("col-so-id", "Id", AttributeRole.IDENTIFIER)), List.of());
    }

    /** The tenant's full domain/connection catalog: purchase_orders, sales_orders, inventory,
     *  and customers — used across the tests below as "everything the tenant is authorized for,"
     *  most of which Agent Brain did NOT select for a given investigation. */
    private static SemanticModel fullTenantCatalog() {
        return new SemanticModel(
                List.of(purchaseOrders(), salesOrders(), inventoryBalances(), customers()),
                Map.of("obj-po",   new PhysicalTable("conn-1", "retail_core", "purchase_orders"),
                       "obj-so",   new PhysicalTable("conn-1", "retail_core", "sales_orders"),
                       "obj-inv2", new PhysicalTable("conn-1", "retail_core", "inventory"),
                       "obj-cust", new PhysicalTable("conn-1", "retail_core", "customers")),
                Map.of("col-po-id", new PhysicalColumn("conn-1", "retail_core", "purchase_orders", "id"),
                       "col-so-id", new PhysicalColumn("conn-1", "retail_core", "sales_orders", "id"),
                       "col-inv2-id", new PhysicalColumn("conn-1", "retail_core", "inventory", "id"),
                       "col-cust-id", new PhysicalColumn("conn-1", "retail_core", "customers", "id")));
    }

    private static BusinessObject inventoryBalances() {
        return new BusinessObject("obj-inv2", "Inventory", "stock",
                List.of(new BusinessAttribute("col-inv2-id", "Id", AttributeRole.IDENTIFIER)), List.of());
    }

    private static BusinessObject customers() {
        return new BusinessObject("obj-cust", "Customers", "customers",
                List.of(new BusinessAttribute("col-cust-id", "Id", AttributeRole.IDENTIFIER)), List.of());
    }

    /** Test 1 (single concept) + Test 3 (no semantic expansion) from the Phase 10 implementation
     *  spec — this is the single most important test in this class: it proves Java does NOT
     *  expand a single Agent-Brain-selected concept into the tenant's full catalog. */
    @Test
    void singleConceptInvestigationScopeIsNotExpandedByExecutionScope() {
        ResolvedBusinessModel singleConceptModel = new ResolvedBusinessModel(
                "agent-1", List.of("conn-1"), "show me all open purchase orders",
                List.of(purchaseOrders()),
                Map.of("obj-po", new PhysicalTable("conn-1", "retail_core", "purchase_orders")),
                Map.of("col-po-id", new PhysicalColumn("conn-1", "retail_core", "purchase_orders", "id")),
                null, Map.of(), true, java.util.Optional.empty(),
                java.util.Optional.of(fullTenantCatalog()));

        ExecutionContract c = builder.compile(singleConceptModel);

        assertTrue(c.executionBindings().isApproved("conn-1", "RETAIL_CORE.PURCHASE_ORDERS"),
                "the Agent-Brain-selected object must be approved");
        assertFalse(c.executionBindings().isApproved("conn-1", "RETAIL_CORE.SALES_ORDERS"),
                "sales_orders must NOT be approved merely because it exists in the tenant catalog "
                        + "— Agent Brain never selected it for this investigation");
        assertFalse(c.executionBindings().isApproved("conn-1", "RETAIL_CORE.INVENTORY"),
                "inventory must NOT be approved either");
        assertFalse(c.executionBindings().isApproved("conn-1", "RETAIL_CORE.CUSTOMERS"),
                "customers must NOT be approved either");
        assertNull(c.executionBindings().objectBindings().get("obj-so"),
                "objectBindings (and therefore describe_schema's own listing) must not carry "
                        + "sales_orders either — not just approvedAssets");
        assertEquals(1, c.executionBindings().approvedAssets().size(),
                "approvedAssets must contain exactly the one Agent-Brain-selected object");
    }

    /** Test 2 (multiple concepts) — when Agent Brain selects two concepts, both their resolved
     *  objects are approved, and still nothing else from the tenant catalog. */
    @Test
    void multipleConceptInvestigationScopeIncludesExactlyBothSelectedObjects() {
        ResolvedBusinessModel twoConceptModel = new ResolvedBusinessModel(
                "agent-1", List.of("conn-1"), "compare purchase orders with sales orders",
                List.of(purchaseOrders(), salesOrders()),
                Map.of("obj-po", new PhysicalTable("conn-1", "retail_core", "purchase_orders"),
                       "obj-so", new PhysicalTable("conn-1", "retail_core", "sales_orders")),
                Map.of("col-po-id", new PhysicalColumn("conn-1", "retail_core", "purchase_orders", "id"),
                       "col-so-id", new PhysicalColumn("conn-1", "retail_core", "sales_orders", "id")),
                null, Map.of(), true, java.util.Optional.empty(),
                java.util.Optional.of(fullTenantCatalog()));

        ExecutionContract c = builder.compile(twoConceptModel);

        assertTrue(c.executionBindings().isApproved("conn-1", "RETAIL_CORE.PURCHASE_ORDERS"));
        assertTrue(c.executionBindings().isApproved("conn-1", "RETAIL_CORE.SALES_ORDERS"));
        assertFalse(c.executionBindings().isApproved("conn-1", "RETAIL_CORE.INVENTORY"),
                "inventory was never selected by Agent Brain — must remain unapproved");
        assertFalse(c.executionBindings().isApproved("conn-1", "RETAIL_CORE.CUSTOMERS"),
                "customers was never selected by Agent Brain — must remain unapproved");
        assertEquals(2, c.executionBindings().approvedAssets().size(),
                "approvedAssets must contain exactly the two Agent-Brain-selected objects, nothing more");
    }

    /** Test 4 (ambiguous/empty concept selection) — when Agent Brain's concept resolution
     *  legitimately produces an empty investigation scope, approvedAssets must be empty too,
     *  regardless of what executionScope (the full tenant catalog) contains. This is the
     *  inverse of the old "regression fix" test: an empty Agent-Brain decision must now
     *  produce an empty executable surface, not a silent fallback to the full catalog. */
    @Test
    void emptyInvestigationScopeProducesEmptyApprovedAssetsRegardlessOfExecutionScope() {
        ResolvedBusinessModel emptyModel = new ResolvedBusinessModel(
                "agent-1", List.of("conn-1"), "show me all open orders",
                List.of(), Map.of(), Map.of(),
                null, Map.of(), true, java.util.Optional.empty(),
                java.util.Optional.of(fullTenantCatalog()));

        ExecutionContract c = builder.compile(emptyModel);

        assertTrue(c.semanticView().businessObjects().isEmpty(),
                "prompt content is legitimately empty — Agent Brain resolved nothing");
        assertTrue(c.executionBindings().approvedAssets().isEmpty(),
                "approvedAssets must be empty when Agent Brain's investigation scope is empty — "
                        + "no falling back to the tenant's full catalog");
        assertTrue(c.isEmpty(), "an empty investigation scope must produce an empty (non-executable) contract");
    }

    @Test
    void absentExecutionScopeFallsBackToObjectsExactlyAsBeforeThisFieldExisted() {
        // Backward compatibility: every pre-existing caller (executionScope absent) must compile
        // byte-identically to before this field existed — proven by reusing the pre-existing
        // model(...) helper and its pre-existing assertions in
        // compilesSemanticViewExecutionBindingsAndApprovedAssets above; this test only pins that
        // no *extra* approvedAssets leak in from nowhere when executionScope is genuinely absent.
        ExecutionContract c = builder.compile(model("how many inventory balances"));

        assertEquals(1, c.executionBindings().objectBindings().size(),
                "with no executionScope, objectBindings must contain exactly the narrowed set — "
                        + "nothing extra");
    }
}
