package com.sei.nexus.semantic;

import com.sei.nexus.semantic.SemanticService.EntityBinding;
import com.sei.nexus.semantic.SemanticService.EntityRow;
import com.sei.nexus.semantic.SemanticService.SemanticContext;
import com.sei.nexus.semantic.SemanticService.VocabRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-19 — SemanticService.assemble: the rendered semantic context must stay
 * byte-identical to the pre-PRO-19 format, while the same rows now also yield
 * entity/vocabulary → table bindings (term → primary_object_key).
 */
class SemanticContextBindingsTest {

    @Test
    void renderingFormatUnchanged() {
        SemanticContext ctx = SemanticService.assemble(
                List.of(new EntityRow("store", "Store", "core", "A retail location",
                        "Where sales happen", "Check status first", "obj-stores")),
                List.of(new VocabRow("vendor", "A supplier company", "suppliers.approval_status = 'approved'", "supplier")));

        String expected = """
                === Business Entities ===
                - Store (core): A retail location | Where sales happen | Hint: Check status first

                === Operational Vocabulary ===
                - vendor: A supplier company [SQL: suppliers.approval_status = 'approved']
                """;
        assertEquals(expected, ctx.contextText());
    }

    @Test
    void entityNameBindsToItsPrimaryObjectKey() {
        SemanticContext ctx = SemanticService.assemble(
                List.of(new EntityRow("store", "Store", null, null, null, null, "obj-stores")),
                List.of());
        assertEquals(List.of(new EntityBinding("Store", "obj-stores")), ctx.bindings());
    }

    @Test
    void vocabularyTermResolvesTransitivelyThroughItsEntity() {
        SemanticContext ctx = SemanticService.assemble(
                List.of(new EntityRow("supplier", "Supplier", null, null, null, null, "obj-suppliers")),
                List.of(new VocabRow("vendor", "synonym", null, "supplier")));
        assertTrue(ctx.bindings().contains(new EntityBinding("vendor", "obj-suppliers")),
                "term → entity_key → primary_object_key chain must produce a binding");
    }

    @Test
    void unlinkedEntitiesContributeContextButNoBinding() {
        // Pack-created entities have NULL primary_object_key by construction
        // (documented integrity gap) — they must not produce bindings.
        SemanticContext ctx = SemanticService.assemble(
                List.of(new EntityRow("shipment", "Shipment", null, "pack entity", null, null, null)),
                List.of(new VocabRow("consignment", "synonym", null, "shipment")));
        assertTrue(ctx.contextText().contains("Shipment"), "still rendered for the planner");
        assertTrue(ctx.contextText().contains("consignment"), "still rendered for the planner");
        assertEquals(List.of(), ctx.bindings(), "no table link → no binding (keyword tier takes over)");
    }

    @Test
    void vocabularyWithUnknownEntityKeyYieldsNoBinding() {
        SemanticContext ctx = SemanticService.assemble(
                List.of(),
                List.of(new VocabRow("orphan term", "def", null, "entity-that-does-not-exist")));
        assertEquals(List.of(), ctx.bindings());
    }

    @Test
    void emptyInputsYieldEmptyContext() {
        SemanticContext ctx = SemanticService.assemble(List.of(), List.of());
        assertEquals("", ctx.contextText());
        assertEquals(List.of(), ctx.bindings());
        assertEquals(java.util.Map.of(), ctx.termLinesByObjectKey());
    }

    // ── Business-terms companion lines (PRO-24) ──────────────────────────────

    @Test
    void termLinesDerivedPerBoundTableCappedAndSqlEquippedOnly() {
        SemanticContext ctx = SemanticService.assemble(
                List.of(new EntityRow("supplier", "Supplier", null, null, null, null, "obj-suppliers")),
                List.of(new VocabRow("vendor",  "syn", "approval_status = 'approved'", "supplier"),
                        new VocabRow("seller",  "syn", "rating >= 3",                  "supplier"),
                        new VocabRow("source",  "syn", "onboarded_at IS NOT NULL",     "supplier"),
                        new VocabRow("partner", "syn", "payment_terms = 'net30'",      "supplier"),
                        new VocabRow("no-sql-term", "definition only", null,           "supplier")));

        var lines = ctx.termLinesByObjectKey().get("obj-suppliers");
        assertEquals(3, lines.size(), "capped per bound table");
        assertEquals("\"vendor\" = approval_status = 'approved'", lines.get(0),
                "exact companion-line format");
        assertFalse(lines.stream().anyMatch(l -> l.contains("no-sql-term")),
                "terms without sql_equivalent earn no prompt tokens");
    }

    @Test
    void unlinkedTermsProduceNoTermLines() {
        SemanticContext ctx = SemanticService.assemble(
                List.of(new EntityRow("shipment", "Shipment", null, null, null, null, null)),
                List.of(new VocabRow("consignment", "syn", "status = 'x'", "shipment"),
                        new VocabRow("orphan", "syn", "a = 1", null)));
        assertEquals(java.util.Map.of(), ctx.termLinesByObjectKey(),
                "no table binding → nothing to attach to");
    }
}
