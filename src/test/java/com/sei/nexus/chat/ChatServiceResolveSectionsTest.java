package com.sei.nexus.chat;

import com.sei.nexus.artifacts.ResponseArtifacts;
import com.sei.nexus.reasoning.InvestigationDataset;
import com.sei.nexus.response.StructuredAnswer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for {@link ChatService#resolveSections} — the ONLY Java decision in the
 * final-answer UI-content-planning path: whether every model-claimed {@code step-N} dataset
 * reference in a section's {@code datasetRefs} exists. Which dataset(s) to reference, how many,
 * why, and whether to display them are entirely the model's decisions (via {@link
 * StructuredAnswer.Section}); this seam performs exact-match lookup only, one reference at a
 * time — no ranking, no fuzzy matching, no substitution, no fallback, and no attempt to
 * determine whether referenced datasets are actually related to each other or to verify that
 * {@code content}'s stated values appear in the resolved rows (that remains the model's
 * responsibility — see class-level notes on individual tests below).
 *
 * <p>Pure static seam, hand-rolled fixtures — this repo's convention (no Mockito, no Spring
 * context, no network).
 */
class ChatServiceResolveSectionsTest {

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @SafeVarargs
    private static InvestigationDataset dataset(int stepNo, String description, Map<String, Object>... rows) {
        return new InvestigationDataset(stepNo, description, List.of(rows));
    }

    private static StructuredAnswer.Section section(String type, String title, List<String> refs,
            List<String> items, String content) {
        return new StructuredAnswer.Section(type, title, "purpose", refs, true, items, content);
    }

    // ── 1. Single-dataset section ────────────────────────────────────────────────────────────

    @Test
    void singleDatasetSectionResolvesToExactlyThatDataset() {
        InvestigationDataset step1 = dataset(1, "Open orders", row("po", "PO-1"), row("po", "PO-2"));

        StructuredAnswer.Section s = section("DATASET", "Open Orders", List.of("step-1"), null, null);

        List<ResponseArtifacts.Section> resolved = ChatService.resolveSections(List.of(s), List.of(step1), "run-1");

        assertEquals(1, resolved.size());
        assertEquals(1, resolved.get(0).datasets().size());
        assertEquals(1, resolved.get(0).datasets().get(0).stepNo());
        assertEquals(2, resolved.get(0).datasets().get(0).rows().size());
    }

    // ── 2. Multi-dataset section — the core new capability ──────────────────────────────────

    @Test
    void multiDatasetSectionResolvesBothReferencedDatasetsIndependently() {
        // The exact scenario this whole change exists for: "Widget A ... with 1,500 units"
        // depends on BOTH step-3 (the aggregation) and step-5 (the descriptive lookup).
        InvestigationDataset step3 = dataset(3, "Aggregation", row("product_id", "X", "total_ordered_qty", 1500));
        InvestigationDataset step5 = dataset(5, "Product details", row("name", "Widget A", "sku", "ABC-123"));

        StructuredAnswer.Section highlight = section("HIGHLIGHT", "Most Ordered Item",
                List.of("step-3", "step-5"), null,
                "Widget A (SKU ABC-123) is the most ordered item with 1,500 units ordered.");

        List<ResponseArtifacts.Section> resolved =
                ChatService.resolveSections(List.of(highlight), List.of(step3, step5), "run-2");

        assertEquals(1, resolved.size());
        ResponseArtifacts.Section out = resolved.get(0);
        assertEquals("Widget A (SKU ABC-123) is the most ordered item with 1,500 units ordered.", out.content());
        assertEquals(2, out.datasets().size(), "both grounding datasets must be present");
        assertEquals(3, out.datasets().get(0).stepNo());
        assertEquals(5, out.datasets().get(1).stepNo());
        assertEquals("X", out.datasets().get(0).rows().get(0).get("product_id"));
        assertEquals("Widget A", out.datasets().get(1).rows().get(0).get("name"));
    }

    // ── 3. Three datasets ─────────────────────────────────────────────────────────────────────

    @Test
    void threeDatasetsCanBeReferencedByOneSection() {
        InvestigationDataset step1 = dataset(1, "Open orders", row("po", "PO-1"), row("po", "PO-2"));
        InvestigationDataset step3 = dataset(3, "Aggregation", row("product_id", "X", "qty", 1500));
        InvestigationDataset step5 = dataset(5, "Product details", row("name", "Widget A"));

        StructuredAnswer.Section s = section("TEXT", "Summary",
                List.of("step-1", "step-3", "step-5"), null, "Combined narrative.");

        List<ResponseArtifacts.Section> resolved =
                ChatService.resolveSections(List.of(s), List.of(step1, step3, step5), "run-3");

        assertEquals(1, resolved.size());
        assertEquals(3, resolved.get(0).datasets().size());
    }

    // ── 4/5. Dataset boundaries and step identity are preserved (never merged) ─────────────────

    @Test
    void datasetBoundariesAndStepIdentityRemainSeparateNeverMerged() {
        InvestigationDataset step1 = dataset(1, "Open orders",
                row("po_number", "PO-1"), row("po_number", "PO-2"));
        InvestigationDataset step5 = dataset(5, "Product details", row("name", "Widget A"));

        StructuredAnswer.Section s = section("HIGHLIGHT", "Combined",
                List.of("step-1", "step-5"), null, "...");

        List<ResponseArtifacts.Section> resolved =
                ChatService.resolveSections(List.of(s), List.of(step1, step5), "run-4");

        var datasets = resolved.get(0).datasets();
        assertEquals(1, datasets.get(0).stepNo());
        assertEquals(2, datasets.get(0).rows().size());
        assertEquals(5, datasets.get(1).stepNo());
        assertEquals(1, datasets.get(1).rows().size());
        assertFalse(datasets.get(0).rows().get(0).containsKey("name"),
                "step-1's rows must never contain step-5's columns — no merge occurred");
        assertFalse(datasets.get(1).rows().get(0).containsKey("po_number"),
                "step-5's rows must never contain step-1's columns — no merge occurred");
    }

    // ── 6. Exact step identity resolution only ───────────────────────────────────────────────

    @Test
    void resolutionUsesExactStepIdentityOnly() {
        InvestigationDataset step2 = dataset(2, "Target", row("a", 1));
        InvestigationDataset step12 = dataset(12, "Decoy — shares a prefix with step-1", row("a", 2));

        StructuredAnswer.Section s = section("DATASET", "Target", List.of("step-2"), null, null);

        List<ResponseArtifacts.Section> resolved =
                ChatService.resolveSections(List.of(s), List.of(step2, step12), "run-6");

        assertEquals(1, resolved.get(0).datasets().size());
        assertEquals(2, resolved.get(0).datasets().get(0).stepNo(),
                "\"step-2\" must resolve to step 2 exactly, never a lexically-similar step-12");
    }

    // ── 7. Invalid ref causes the entire section to be rejected ─────────────────────────────

    @Test
    void invalidDatasetRefCausesTheEntireSectionToBeDropped() {
        InvestigationDataset step1 = dataset(1, "Open orders", row("po", "PO-1"));

        StructuredAnswer.Section s = section("DATASET", "Nonexistent", List.of("step-99"), null, null);

        List<ResponseArtifacts.Section> resolved = ChatService.resolveSections(List.of(s), List.of(step1), "run-7");

        assertTrue(resolved.isEmpty());
    }

    // ── 8. One valid + one invalid ref → NOT partially accepted ─────────────────────────────

    @Test
    void oneValidAndOneInvalidRefIsNotPartiallyAccepted() {
        InvestigationDataset step3 = dataset(3, "Aggregation", row("product_id", "X", "qty", 1500));

        StructuredAnswer.Section s = section("HIGHLIGHT", "Most Ordered Item",
                List.of("step-3", "step-5"), null,
                "Widget A (SKU ABC-123) is the most ordered item with 1,500 units ordered.");

        // step-5 was never actually produced by this investigation.
        List<ResponseArtifacts.Section> resolved = ChatService.resolveSections(List.of(s), List.of(step3), "run-8");

        assertTrue(resolved.isEmpty(),
                "a section citing one real and one fake dataset must be dropped entirely — "
                        + "never resolved with just the valid reference");
    }

    // ── 9. No fallback to queryData ───────────────────────────────────────────────────────────

    @Test
    void resolveSectionsHasNoQueryDataParameterAtAllStructuralProof() throws NoSuchMethodException {
        // resolveSections's signature carries only (sections, investigationDatasets, runKey) —
        // there is no queryData parameter for it to fall back to, structurally.
        var method = ChatService.class.getDeclaredMethod("resolveSections",
                List.class, List.class, String.class);
        assertEquals(3, method.getParameterCount());
    }

    // ── 10/11/12. No row-count / evaluatorDecision / step-order selection ───────────────────

    @Test
    void javaNeverSelectsByRowCountEvaluatorDecisionOrStepOrder() {
        // A deliberately "backwards" ordering and lopsided row counts — resolution must follow
        // only the model's own datasetRefs, never row count, evaluator status (InvestigationDataset
        // structurally carries none), or step order.
        InvestigationDataset bigStep = dataset(9, "Big dataset", row("a", 1), row("a", 2), row("a", 3));
        InvestigationDataset smallStep = dataset(2, "Small dataset", row("a", 1));

        StructuredAnswer.Section s = section("DATASET", "Small", List.of("step-2"), null, null);

        List<ResponseArtifacts.Section> resolved =
                ChatService.resolveSections(List.of(s), List.of(bigStep, smallStep), "run-10");

        assertEquals(1, resolved.get(0).datasets().get(0).rows().size(),
                "the model chose the small dataset (step-2); Java must not substitute the larger one");
    }

    // ── 13. No column-name matching ───────────────────────────────────────────────────────────

    @Test
    void javaNeverInspectsColumnNamesToChooseADataset() {
        InvestigationDataset suspicious = dataset(7, "Contains a 'primary'-sounding column",
                row("is_primary", true, "relevance_score", 99));
        InvestigationDataset target = dataset(2, "An unrelated dataset", row("x", 1));

        StructuredAnswer.Section s = section("DATASET", "Target", List.of("step-2"), null, null);

        List<ResponseArtifacts.Section> resolved =
                ChatService.resolveSections(List.of(s), List.of(suspicious, target), "run-13");

        assertEquals(1, resolved.get(0).datasets().get(0).rows().get(0).get("x"),
                "resolution followed the exact step-2 reference only, never the suspicious column names");
    }

    // ── 14. No question-text inspection ───────────────────────────────────────────────────────

    @Test
    void resolveSectionsHasNoQuestionParameterAtAllStructuralProof() throws NoSuchMethodException {
        // Same structural argument as test 9: the method signature simply has no question/title/
        // purpose-driven parameter it could inspect for relevance.
        var method = ChatService.class.getDeclaredMethod("resolveSections",
                List.class, List.class, String.class);
        for (var p : method.getParameterTypes()) {
            assertNotEquals("question", p.getSimpleName().toLowerCase());
        }
    }

    // ── 15. Java never reads/semantically evaluates section.content ─────────────────────────

    @Test
    void javaNeverValidatesThatContentValuesAppearInTheResolvedRows() {
        // The model states "1,500 units" and correctly cites step-3, but step-3's actual value
        // is 999 — a real, incorrect Agent Brain output. Java must resolve step-3 exactly as
        // named and transport it AS-IS; it must not detect, correct, or reject the mismatch.
        InvestigationDataset step3 = dataset(3, "Aggregation", row("product_id", "X", "total_ordered_qty", 999));

        StructuredAnswer.Section s = section("HIGHLIGHT", "Most Ordered Item", List.of("step-3"), null,
                "The most ordered item has 1,500 units ordered.");

        List<ResponseArtifacts.Section> resolved = ChatService.resolveSections(List.of(s), List.of(step3), "run-15");

        assertEquals(1, resolved.size(), "Java resolves the real reference regardless of whether "
                + "the narrative's stated number matches — it never inspects content");
        assertEquals("The most ordered item has 1,500 units ordered.", resolved.get(0).content());
        assertEquals(999, resolved.get(0).datasets().get(0).rows().get(0).get("total_ordered_qty"),
                "the actual evidence value is transported unmodified, whatever the narrative claims");
    }

    // ── 18. One dataset or multiple — the model decides the cardinality ─────────────────────

    @Test
    void aSectionMayLegitimatelyReferenceOneOrMultipleDatasetsModelsChoice() {
        InvestigationDataset step1 = dataset(1, "Open orders", row("po", "PO-1"));
        InvestigationDataset step3 = dataset(3, "Aggregation", row("qty", 1500));
        InvestigationDataset step5 = dataset(5, "Product", row("name", "Widget A"));

        StructuredAnswer.Section single = section("DATASET", "Open Orders", List.of("step-1"), null, null);
        StructuredAnswer.Section multi = section("HIGHLIGHT", "Most Ordered", List.of("step-3", "step-5"),
                null, "Widget A has 1,500 units ordered.");

        List<ResponseArtifacts.Section> resolved =
                ChatService.resolveSections(List.of(single, multi), List.of(step1, step3, step5), "run-18");

        assertEquals(1, resolved.get(0).datasets().size(), "single-dataset cardinality is valid");
        assertEquals(2, resolved.get(1).datasets().size(), "multi-dataset cardinality is equally valid");
    }

    // ── 19. Legacy queryData cannot influence section resolution ─────────────────────────────

    @Test
    void legacyQueryDataHasNoInfluenceOnSectionResolution() {
        InvestigationDataset step1 = dataset(1, "First (would NOT be queryData's pick — fewer rows)", row("a", 1));
        InvestigationDataset step2 = dataset(2, "Second (would BE queryData's pick — most rows)",
                row("a", 1), row("a", 2), row("a", 3));

        StructuredAnswer.Section s = section("DATASET", "First", List.of("step-1"), null, null);

        List<ResponseArtifacts.Section> resolved =
                ChatService.resolveSections(List.of(s), List.of(step1, step2), "run-19");

        assertEquals(1, resolved.get(0).datasets().get(0).rows().size(),
                "the model's own choice (step-1) resolves correctly regardless of what a legacy "
                        + "row-count-based heuristic would have preferred");
    }

    // ── Non-dataset / empty-ref sections pass through unresolved ─────────────────────────────

    @Test
    void nonDatasetSectionsWithNoRefsPassThroughWithoutResolutionAttempt() {
        StructuredAnswer.Section findings = section("FINDINGS", "Key Findings", null,
                List.of("finding one"), null);
        StructuredAnswer.Section recommendation = section("RECOMMENDATION", null, List.of(), null, "do X");

        List<ResponseArtifacts.Section> resolved =
                ChatService.resolveSections(List.of(findings, recommendation), List.of(), "run-20");

        assertEquals(2, resolved.size());
        assertTrue(resolved.get(0).datasets().isEmpty());
        assertTrue(resolved.get(1).datasets().isEmpty());
    }

    @Test
    void emptyOrNullSectionsProduceAnEmptyResolvedListNeverThrows() {
        assertTrue(ChatService.resolveSections(null, List.of(), "run-21").isEmpty());
        assertTrue(ChatService.resolveSections(List.of(), List.of(), "run-21").isEmpty());
    }

    @Test
    void anInvalidReferenceInAMultiRefSectionDoesNotAffectOtherIndependentSections() {
        InvestigationDataset step1 = dataset(1, "Open orders", row("po", "PO-1"));

        List<StructuredAnswer.Section> sections = List.of(
                section("DATASET", "Open Orders", List.of("step-1"), null, null),
                section("HIGHLIGHT", "Bogus", List.of("step-1", "step-77"), null, "invalid claim"));

        List<ResponseArtifacts.Section> resolved =
                ChatService.resolveSections(sections, List.of(step1), "run-22");

        assertEquals(1, resolved.size(), "the bogus multi-ref section is dropped entirely; "
                + "the valid single-ref section survives independently");
        assertEquals("Open Orders", resolved.get(0).title());
    }
}
