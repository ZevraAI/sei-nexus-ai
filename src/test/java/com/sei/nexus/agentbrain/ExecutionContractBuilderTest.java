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

    // ── Execution-authorization scope, kept separate from prompt content (the regression fix:
    //     Concept-Scoped Metadata Narrowing must control prompt rendering only, never execution
    //     authorization) ─────────────────────────────────────────────────────────────────────

    private static BusinessObject purchaseOrders() {
        return new BusinessObject("obj-po", "Purchase Orders", "orders",
                List.of(new BusinessAttribute("col-po-id", "Id", AttributeRole.IDENTIFIER)), List.of());
    }

    @Test
    void executionScopeExtendsApprovedAssetsBeyondTheNarrowedPromptSelection() {
        // "objects" (prompt content) contains ONLY inventory — e.g. what Concept-Scoped
        // Narrowing selected for this question's rendering. "executionScope" is the full,
        // connection-scoped catalog, which ALSO includes purchase_orders — an object the
        // narrowed prompt selection never mentioned.
        SemanticModel fullConnectionCatalog = new SemanticModel(
                List.of(inventory(), purchaseOrders()),
                Map.of("obj-inv", new PhysicalTable("conn-1", "retail_core", "inventory_balances"),
                       "obj-po",  new PhysicalTable("conn-1", "retail_core", "purchase_orders")),
                Map.of("col-onhand", new PhysicalColumn("conn-1", "retail_core", "inventory_balances", "on_hand_qty"),
                       "col-id",     new PhysicalColumn("conn-1", "retail_core", "inventory_balances", "id"),
                       "col-po-id",  new PhysicalColumn("conn-1", "retail_core", "purchase_orders", "id")));

        ResolvedBusinessModel narrowedWithFullExecutionScope = new ResolvedBusinessModel(
                "agent-1", List.of("conn-1"), "how much stock do we have?",
                List.of(inventory()),
                Map.of("obj-inv", new PhysicalTable("conn-1", "retail_core", "inventory_balances")),
                Map.of("col-onhand", new PhysicalColumn("conn-1", "retail_core", "inventory_balances", "on_hand_qty"),
                       "col-id",     new PhysicalColumn("conn-1", "retail_core", "inventory_balances", "id")),
                null, Map.of(), true, java.util.Optional.empty(),
                java.util.Optional.of(fullConnectionCatalog));

        ExecutionContract c = builder.compile(narrowedWithFullExecutionScope);

        // Prompt content (SemanticView) stays exactly the narrowed selection — unchanged.
        assertEquals(1, c.semanticView().businessObjects().size(),
                "prompt rendering must still reflect only the narrowed (concept-scoped) selection");
        assertEquals("Inventory Balance", c.semanticView().businessObjects().get(0).businessName());

        // Execution authorization is NOT narrowed — purchase_orders, present only in
        // executionScope (never in the narrowed prompt selection), is still approved.
        assertTrue(c.executionBindings().isApproved("conn-1", "RETAIL_CORE.PURCHASE_ORDERS"),
                "an object outside the narrowed prompt selection, but present in the full "
                        + "connection-scoped executionScope, must remain executable");
        assertTrue(c.executionBindings().isApproved("conn-1", "RETAIL_CORE.INVENTORY_BALANCES"),
                "the narrowed object itself remains approved too");
        assertEquals("purchase_orders", c.executionBindings().objectBindings().get("obj-po").table(),
                "objectBindings carries the union — GovernedSqlRuntime's 'Approved tables' listing "
                        + "must be able to name this object too, not just the narrowed set");
    }

    @Test
    void emptyNarrowedSelectionWithNonEmptyExecutionScopeStillApprovesTheExecutionScopeObjects() {
        // The exact regression scenario: Concept-Scoped Narrowing legitimately selected ZERO
        // objects for this question's prompt (a real, honest "nothing relevant to render"
        // outcome), but the connection has real, approved business data. Execution authorization
        // must reflect that real data, not the empty narrowed selection.
        SemanticModel fullConnectionCatalog = new SemanticModel(
                List.of(purchaseOrders()),
                Map.of("obj-po", new PhysicalTable("conn-1", "retail_core", "purchase_orders")),
                Map.of("col-po-id", new PhysicalColumn("conn-1", "retail_core", "purchase_orders", "id")));

        ResolvedBusinessModel emptyNarrowedModel = new ResolvedBusinessModel(
                "agent-1", List.of("conn-1"), "show me all open purchase orders",
                List.of(), Map.of(), Map.of(),
                null, Map.of(), true, java.util.Optional.empty(),
                java.util.Optional.of(fullConnectionCatalog));

        ExecutionContract c = builder.compile(emptyNarrowedModel);

        assertTrue(c.semanticView().businessObjects().isEmpty(),
                "prompt content is legitimately empty — narrowing found nothing to render");
        assertTrue(c.executionBindings().isApproved("conn-1", "RETAIL_CORE.PURCHASE_ORDERS"),
                "THE FIX: execution authorization must not collapse to empty merely because "
                        + "narrowing rendered nothing for this question's initial prompt");
        assertFalse(c.executionBindings().approvedAssets().isEmpty(),
                "approvedAssets must not be [] when the connection has real, approved business data");
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
