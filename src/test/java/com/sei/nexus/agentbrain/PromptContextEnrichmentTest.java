package com.sei.nexus.agentbrain;

import com.sei.nexus.semanticmodel.AttributeRole;
import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified Answer Engine, Phase 3 — Step 3. The shared {@link PromptContext} now carries the
 * reusable business grounding every SQL-generating policy needs — connection key, schema, and
 * physical column data type — and {@link PromptAssembler} renders it according to a policy
 * ({@link PromptAssembler.RenderOptions}), with a rendering budget that bounds only the render
 * and never the approved execution surface.
 *
 * <p>These pin two things: the enrichment flows end to end through the real spine
 * (assembler-shaped model → builder → context builder → assembler), and the historical compact
 * rendering is byte-for-byte unchanged.
 */
class PromptContextEnrichmentTest {

    private final ExecutionContractBuilder builder =
            new ExecutionContractBuilder(new SqlTableReferenceExtractor());
    private final PromptContextBuilder pcb = new PromptContextBuilder();
    private final PromptAssembler pa = new PromptAssembler();

    /** One object with two typed columns, as the assembler would produce it. */
    private static ResolvedBusinessModel model() {
        BusinessObject inv = new BusinessObject("obj-inv", "Inventory Balance", "stock levels",
                List.of(new BusinessAttribute("c-onhand", "On Hand Quantity", AttributeRole.MEASURE),
                        new BusinessAttribute("c-status", "Status", AttributeRole.DIMENSION)),
                List.of());
        return new ResolvedBusinessModel("agent-1", List.of("conn-1"), "q",
                List.of(inv),
                Map.of("obj-inv", new PhysicalTable("conn-1", "retail_core", "inventory_balances")),
                Map.of("c-onhand", new PhysicalColumn("conn-1", "retail_core", "inventory_balances",
                                "on_hand_qty", "integer"),
                       "c-status", new PhysicalColumn("conn-1", "retail_core", "inventory_balances",
                                "status", "character varying")));
    }

    // ── enrichment flows through the shared PromptContext ──────────────────────

    @Test
    void promptContextCarriesConnectionSchemaAndDataType() {
        PromptContext ctx = pcb.build(builder.compile(model()));

        PromptContext.PromptObject o = ctx.objects().get(0);
        assertEquals("conn-1", o.connectionKey(), "connection identity is carried for routing");
        assertEquals("retail_core", o.schema(), "schema is carried for qualified SQL");
        assertEquals("inventory_balances", o.physicalTable());

        PromptContext.PromptAttribute onHand = o.attributes().stream()
                .filter(a -> a.physicalColumn().equals("on_hand_qty")).findFirst().orElseThrow();
        assertEquals("integer", onHand.dataType(), "physical data type is carried for type-correct SQL");
        assertEquals("measure", onHand.role());
    }

    // ── the full policy renders the new grounding ─────────────────────────────

    @Test
    void fullRenderSurfacesConnectionSchemaAndType() {
        String out = pa.assemble(pcb.build(builder.compile(model())),
                new PromptAssembler.RenderOptions(true, true, true, true, 0));

        assertTrue(out.contains("TABLE `retail_core.inventory_balances`"), out);
        assertTrue(out.contains("connection_key: conn-1 (use this exact value)"), out);
        assertTrue(out.contains("`on_hand_qty` (measure, integer)"), out);
        assertTrue(out.contains("`status` (dimension, character varying)"), out);
    }

    /** When the carried attribute has a value domain, the full policy renders it (capped); compact does not. */
    @Test
    void fullRenderSurfacesValueDomainWhenPresent() {
        BusinessObject stores = new BusinessObject("obj-stores", "Stores", "",
                List.of(new BusinessAttribute("c-status", "Status", AttributeRole.DIMENSION)), List.of());
        ResolvedBusinessModel m = new ResolvedBusinessModel("a", List.of("conn-1"), "q",
                List.of(stores),
                Map.of("obj-stores", new PhysicalTable("conn-1", "retail_core", "stores")),
                Map.of("c-status", new PhysicalColumn("conn-1", "retail_core", "stores", "status",
                        "USER-DEFINED",
                        new com.sei.nexus.semanticmodel.ColumnValueDomain("stores", "status", true,
                                List.of("open", "closed")))));
        PromptContext ctx = pcb.build(builder.compile(m));

        String full = pa.assemble(ctx, new PromptAssembler.RenderOptions(true, true, true, true, 0));
        assertTrue(full.contains("[legal values: open | closed]"), full);

        String compact = pa.assemble(ctx);   // agent policy
        assertFalse(compact.contains("legal values"), "compact never renders value domains");
    }

    // ── the compact (agent) policy is byte-for-byte unchanged ─────────────────

    @Test
    void compactPolicyHidesEnrichmentButStillQualifiesNonDefaultSchemas() {
        String out = pa.assemble(pcb.build(builder.compile(model())));   // compact default

        // The compact policy hides the enriched detail (connection line, data types)…
        assertFalse(out.contains("connection_key:"), "compact does not surface the connection key");
        assertTrue(out.contains("`on_hand_qty` (measure)"), out);
        assertFalse(out.contains("(measure, integer)"), "compact does not show the data type");
        // …but it still qualifies a non-default-schema table, because the bare form would not
        // execute — executable identity is never sacrificed to the render policy.
        assertTrue(out.contains("TABLE `retail_core.inventory_balances`"),
                "non-default schema is qualified even under the compact policy");
    }

    /** A default-schema (public) object keeps its bare rendering under the compact policy — unchanged. */
    @Test
    void compactPolicyLeavesDefaultSchemaTablesBare() {
        BusinessObject o = new BusinessObject("obj-p", "Product", "",
                List.of(new BusinessAttribute("c-id", "Id", AttributeRole.IDENTIFIER)), List.of());
        ResolvedBusinessModel m = new ResolvedBusinessModel("a", List.of("conn-1"), "q",
                List.of(o),
                Map.of("obj-p", new PhysicalTable("conn-1", "public", "products")),
                Map.of("c-id", new PhysicalColumn("conn-1", "public", "products", "id")));

        String out = pa.assemble(pcb.build(builder.compile(m)));
        assertTrue(out.contains("TABLE `products`"), "public tables resolve bare — rendering unchanged");
        assertFalse(out.contains("public.products"), "no gratuitous qualification for default schemas");
    }

    // ── the rendering budget bounds the render, not the surface ───────────────

    private static ResolvedBusinessModel manyObjects(int n) {
        java.util.List<BusinessObject> objs = new java.util.ArrayList<>();
        java.util.Map<String, PhysicalTable> tt = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            String k = "obj-" + i;
            objs.add(new BusinessObject(k, "Object " + i, "",
                    List.of(new BusinessAttribute("c-" + i, "Id", AttributeRole.IDENTIFIER)), List.of()));
            tt.put(k, new PhysicalTable("conn-1", "public", "table_" + i));
        }
        return new ResolvedBusinessModel("a", List.of("conn-1"), "q", objs, tt, Map.of());
    }

    /**
     * The rendering invariant: the budget bounds descriptive detail (columns), but NEVER removes an
     * approved object's executable identity — every object's TABLE line is always rendered.
     */
    @Test
    void budgetBoundsDetailButNeverAnObjectsIdentity() {
        ExecutionContract contract = builder.compile(manyObjects(20));
        PromptContext ctx = pcb.build(contract);

        String bounded   = pa.assemble(ctx, new PromptAssembler.RenderOptions(false, false, false, false, 400));
        String unbounded = pa.assemble(ctx, new PromptAssembler.RenderOptions(false, false, false, false, 0));

        assertTrue(bounded.length() < unbounded.length(), "the budget bounds descriptive detail");

        // INVARIANT: all 20 identities render regardless of budget — none is dropped.
        assertEquals(20, bounded.split("TABLE `", -1).length - 1,
                "every approved object's executable identity is rendered, whatever the budget");
        // but lower-relevance objects shed their column detail to fit.
        assertTrue(bounded.contains("(columns omitted to fit the context budget)"),
                "detail is sacrificed for the least-relevant objects, not their identity");

        // the approved execution surface is the full 20 — the budget never narrows it.
        assertEquals(20, contract.semanticView().businessObjects().size());
        assertTrue(contract.executionBindings().isApproved("conn-1", "TABLE_19"));
    }

    @Test
    void everyObjectIdentitySurvivesAnUnrealisticallySmallBudget() {
        PromptContext ctx = pcb.build(builder.compile(manyObjects(5)));
        String out = pa.assemble(ctx, new PromptAssembler.RenderOptions(false, false, false, false, 1));
        assertEquals(5, out.split("TABLE `", -1).length - 1,
                "all five identities render even at budget=1 — only column detail is sacrificed");
    }
}
