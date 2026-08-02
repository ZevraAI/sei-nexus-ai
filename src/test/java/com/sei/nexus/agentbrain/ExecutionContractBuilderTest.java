package com.sei.nexus.agentbrain;

import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;
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
}
