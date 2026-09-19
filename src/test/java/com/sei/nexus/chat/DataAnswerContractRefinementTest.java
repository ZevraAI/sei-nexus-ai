package com.sei.nexus.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sei.nexus.artifacts.ResponseArtifacts;
import com.sei.nexus.reasoning.InvestigationDataset;
import com.sei.nexus.response.StructuredAnswer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for the refined investigation-response prompt/contract (2026-09-13 rewrite of
 * {@code ChatService#DATA_ANSWER_JSON_SYSTEM_PROMPT}). The prompt text itself asks the model to
 * stop treating every section type as a UI slot to fill — sections, metrics, and next-steps are
 * all now explicitly optional, earning their place only when they add something "answer" doesn't
 * already say. Because prompt TEXT cannot be unit-tested directly (there is no seam that returns
 * it — see {@code ChatServiceHardeningTest} for the existing convention of testing behavior, not
 * prose), these tests instead prove that every response SHAPE the refined prompt is now expected
 * to produce (sparse/minimal responses included) flows correctly through the two seams that
 * actually process the model's output: {@link ChatService#parseStructuredAnswer} and {@link
 * ChatService#resolveSections}. Real prompt-adherence (does the live model actually follow this
 * guidance) is verified separately, live, as this task's final step — see the session report.
 *
 * <p>Hand-rolled fixtures only — this repo's convention (no Mockito, no Spring context, no
 * network). Numbered per the task's own "TEST CASES" list where a case maps onto one of these two
 * seams; cases that are purely prompt-adherence judgment calls (e.g. "no fabricated values") are
 * covered by the DO-NOT-FABRICATE/grounding tests below, which is as far as a static test can
 * verify — the live round covers the rest.
 */
class DataAnswerContractRefinementTest {

    private static ObjectMapper snakeCaseMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return mapper;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @SafeVarargs
    private static InvestigationDataset dataset(int stepNo, String description, Map<String, Object>... rows) {
        return new InvestigationDataset(stepNo, description, List.of(rows), null, null, List.of());
    }

    // ── 1. Simple dataset query — answer + a single DATASET section, nothing forced ─────────

    @Test
    void simpleDatasetQuery_answerPlusOnlyTheNecessaryDataset() {
        String json = """
                {"answer": "There are **5 open purchase orders**.",
                 "sections": [
                   {"type": "DATASET", "title": "Open Purchase Orders", "purpose": null,
                    "dataset_refs": ["step-1"], "display": true}
                 ],
                 "metrics": []}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals(1, result.sections().size(), "no HIGHLIGHT/FINDINGS/FOLLOW_UP_QUESTIONS forced onto a simple lookup");
        assertEquals("DATASET", result.sections().get(0).type());
        assertNull(result.sections().get(0).purpose(), "purpose null is valid — no boilerplate required");
        assertTrue(result.metrics().isEmpty());
    }

    // ── 2. Dataset + genuine additional finding ──────────────────────────────────────────────

    @Test
    void datasetPlusGenuineAdditionalFinding_survivesParsingDistinctFromAnswer() {
        String json = """
                {"answer": "Open purchase orders total **5**, with Marcus Webb accounting for **3 of 5**.",
                 "sections": [
                   {"type": "DATASET", "dataset_refs": ["step-1"], "display": true, "title": "Open Purchase Orders"},
                   {"type": "FINDINGS", "dataset_refs": ["step-1"],
                    "items": ["Three of the five open orders are partially received."]}
                 ],
                 "metrics": []}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals(1, result.keyFindings().size());
        assertEquals("Three of the five open orders are partially received.", result.keyFindings().get(0));
        assertFalse(result.keyFindings().get(0).contains("Marcus Webb"), "the finding is genuinely distinct, not a Marcus Webb restatement");
    }

    // ── 3. Answer where no additional finding exists — empty sections beyond DATASET is valid ──

    @Test
    void noAdditionalFindingExists_emptySectionsArrayIsAValidCompleteResponse() {
        String json = """
                {"answer": "17 stores are currently open.", "sections": [],
                 "metrics": []}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertTrue(result.sections().isEmpty());
        // An empty "sections" array falls to the legacy-shape parsing branch (see
        // ChatService#parseStructuredAnswer) — key_findings is simply absent from this JSON, so
        // it parses as null, exactly like any other legacy response missing that optional field.
        assertNull(result.keyFindings());
        assertTrue(result.metrics().isEmpty());
    }

    // ── 4/5. Metrics — including metrics that intentionally repeat headline figures ─────────

    @Test
    void metricsRepeatingHeadlineFiguresForVisualEmphasisIsExplicitlyValid() {
        String json = """
                {"answer": "There are **5 open purchase orders**, with Marcus Webb accounting for **3 of 5**.",
                 "sections": [{"type": "DATASET", "dataset_refs": ["step-1"], "display": true}],
                 "metrics": [
                   {"label": "Open Orders", "value": "5"},
                   {"label": "Top Buyer", "value": "Marcus Webb"}
                 ]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals(2, result.metrics().size());
        assertEquals("5", result.metrics().get(0).value(), "a metric may repeat a figure already in answer — visual repetition is allowed");
        assertEquals("Marcus Webb", result.metrics().get(1).value());
    }

    @Test
    void zeroMetricsIsAValidResponse() {
        String json = """
                {"answer": "17 stores are currently open.",
                 "sections": [{"type": "DATASET", "dataset_refs": ["step-1"], "display": true}],
                 "metrics": []}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertTrue(result.metrics().isEmpty());
    }

    // ── 6/7. FOLLOW_UP_QUESTIONS — useful follow-ups vs. a simple lookup with none ────────────────────

    @Test
    void usefulNextStepsSurviveParsingAndAreNotTreatedAsDuplicationOfAnswer() {
        String json = """
                {"answer": "Open purchase orders total **5**.",
                 "sections": [
                   {"type": "DATASET", "dataset_refs": ["step-1"], "display": true},
                   {"type": "FOLLOW_UP_QUESTIONS", "dataset_refs": ["step-1"],
                    "items": ["Which open purchase orders are partially received?",
                              "Which open orders have the earliest expected delivery dates?",
                              "Show open purchase orders by buyer."],
                    "reasoning": "Drilling into status, delivery timing, and buyer distribution."}
                 ],
                 "metrics": []}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals(3, result.followUpQuestions().size());
        assertTrue(result.followUpQuestions().stream().allMatch(s -> s.toLowerCase().contains("order")),
                "next-step questions naturally concerning the same topic as answer is explicitly allowed");

        // The reasoning is structurally attached to the SAME section as the items — not an
        // independent top-level field (see StructuredAnswer.Section's javadoc).
        StructuredAnswer.Section followUps = result.sections().stream()
                .filter(s -> "FOLLOW_UP_QUESTIONS".equals(s.type())).findFirst().orElseThrow();
        assertEquals("Drilling into status, delivery timing, and buyer distribution.", followUps.reasoning());
        assertEquals(3, followUps.items().size(), "reasoning and items live on the same section object");
    }

    @Test
    void simpleLookupWithNoMeaningfulNextSteps_omittingTheFollowUpQuestionsSectionEntirelyIsValid() {
        String json = """
                {"answer": "PO-2025-00234 is currently in Partially Received status.",
                 "sections": [{"type": "DATASET", "dataset_refs": ["step-1"], "display": true}],
                 "metrics": []}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertTrue(result.followUpQuestions().isEmpty());
        assertTrue(result.sections().stream().noneMatch(s -> "FOLLOW_UP_QUESTIONS".equals(s.type())),
                "no FOLLOW_UP_QUESTIONS section at all — never an empty-items section, and no top-level reasoning field either");
    }

    // ── 8/9. Multiple datasets, and a finding spanning more than one ────────────────────────

    @Test
    void multipleDatasetSectionsBothSurvive() {
        String json = """
                {"answer": "...",
                 "sections": [
                   {"type": "DATASET", "dataset_refs": ["step-1"], "display": true},
                   {"type": "DATASET", "dataset_refs": ["step-3"], "display": true}
                 ]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        long datasetSections = result.sections().stream().filter(s -> "DATASET".equals(s.type())).count();
        assertEquals(2, datasetSections);
    }

    @Test
    void multiDatasetFindingResolvesBothReferencedDatasets() {
        InvestigationDataset step3 = dataset(3, "Aggregation", row("product_id", "X", "total_ordered_qty", 1500));
        InvestigationDataset step5 = dataset(5, "Product details", row("name", "Widget A", "sku", "ABC-123"));

        StructuredAnswer.Section highlight = new StructuredAnswer.Section("HIGHLIGHT", "Most Ordered Item",
                null, List.of("step-3", "step-5"), null, null,
                "Widget A (SKU ABC-123) is the most ordered item with 1,500 units ordered.", null);

        List<ResponseArtifacts.Section> resolved =
                ChatService.resolveSections(List.of(highlight), List.of(step3, step5), "run-multi");

        assertEquals(1, resolved.size());
        assertEquals(2, resolved.get(0).datasets().size(), "both grounding datasets must be present");
    }

    // ── 10. Invalid dataset reference — entire section rejected, never repaired ─────────────

    @Test
    void invalidDatasetReference_entireSectionRejectedNeverRepaired() {
        InvestigationDataset step1 = dataset(1, "Open orders", row("po", "PO-1"));

        StructuredAnswer.Section bogus = new StructuredAnswer.Section("DATASET", "Nonexistent",
                null, List.of("step-99"), true, null, null, null);

        List<ResponseArtifacts.Section> resolved =
                ChatService.resolveSections(List.of(bogus), List.of(step1), "run-invalid");

        assertTrue(resolved.isEmpty(), "an invalid dataset_refs entry drops the whole section, never substitutes another dataset");
    }

    // ── 11. Duplicate answer/HIGHLIGHT — Java cannot police this (prompt-level), documented ──

    @Test
    void duplicateHighlightOfAnswer_javaHasNoSemanticDeduplicationLogic_promptOnlyEnforcement() {
        // Java's parsing/resolution layer relays whatever the model returns verbatim — it has no
        // semantic-duplication detector, by design (see StructuredAnswer's javadoc: Java "never
        // re-derives these from prose"). This is a genuine prompt-adherence property, only
        // verifiable live (see this task's live-verification round) — this test documents that
        // boundary rather than asserting a Java-side guarantee that doesn't exist.
        String json = """
                {"answer": "There are five open purchase orders, with Marcus Webb accounting for 3 of 5 orders.",
                 "sections": [
                   {"type": "HIGHLIGHT", "dataset_refs": ["step-1"],
                    "content": "Marcus Webb is the most frequent buyer with 3 open purchase orders."}
                 ]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        // Parsing succeeds regardless — duplication avoidance is the model's job per the prompt,
        // not a Java-side filter. Documented here so the boundary is explicit and intentional.
        assertEquals(1, result.sections().size());
        assertEquals("HIGHLIGHT", result.sections().get(0).type());
    }

    // ── 12. Duplicate answer/FINDINGS — same boundary as above, FINDINGS variant ─────────────

    @Test
    void duplicateFindingsOfAnswer_javaHasNoSemanticDeduplicationLogic_promptOnlyEnforcement() {
        String json = """
                {"answer": "There are five open purchase orders, with Marcus Webb accounting for 3 of 5 orders.",
                 "sections": [
                   {"type": "FINDINGS", "dataset_refs": ["step-1"],
                    "items": ["Marcus Webb has 3 of the 5 open purchase orders."]}
                 ]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals(1, result.keyFindings().size(), "Java relays the item verbatim; avoiding this duplication is the prompt's job, verified live");
    }

    // ── 13. Recommendation with insufficient evidence — omission is valid, absence parses fine ──

    @Test
    void noRecommendationWhenUnwarranted_omittedRecommendationParsesAsNull() {
        String json = """
                {"answer": "There are 5 open purchase orders.",
                 "sections": [{"type": "DATASET", "dataset_refs": ["step-1"], "display": true}]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertNull(result.recommendation(), "omitting RECOMMENDATION is the correct behavior, never a generic filler sentence");
    }

    // ── 14. Multi-part question — answer must address every part (prompt-level; parsing proof) ──

    @Test
    void multiPartQuestionAnswerCarriesBothParts_parsedVerbatim() {
        String json = """
                {"answer": "There are **5 open purchase orders**, and the most ordered item is **Widget ABC (SKU-123)** with **1,500 units**.",
                 "sections": [{"type": "DATASET", "dataset_refs": ["step-1"], "display": true}]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertTrue(result.answer().contains("open purchase orders"));
        assertTrue(result.answer().contains("Widget ABC"), "both substantive parts of the question must survive in answer");
    }

    // ── 15. No fabricated values — grounding enforcement (Java's own guarantee) ─────────────

    @Test
    void noFabricatedDatasetReference_sectionCitingAnUnsuppliedStepIsDropped() {
        InvestigationDataset step1 = dataset(1, "Open orders", row("po", "PO-1"));

        // The model invents "step-2" though only step-1 was ever supplied.
        StructuredAnswer.Section fabricated = new StructuredAnswer.Section("HIGHLIGHT", "Fabricated",
                null, List.of("step-2"), null, null, "A value that was never actually retrieved.", null);

        List<ResponseArtifacts.Section> resolved =
                ChatService.resolveSections(List.of(fabricated), List.of(step1), "run-fabricated");

        assertTrue(resolved.isEmpty(), "a reference to a dataset never supplied is rejected, never fabricated into existence");
    }

    // ── 16. Existing chart-bearing response — unaffected by the prompt-text rewrite ─────────

    @Test
    void existingChartBearingResponseShapeStillParsesUnaffectedByThePromptRewrite() {
        // Chart hints live per-dataset (InvestigationDataset), never inside this JSON contract —
        // the prompt rewrite explicitly does not touch chart behavior. A DATASET section for a
        // chart-eligible dataset parses exactly as any other DATASET section.
        String json = """
                {"answer": "Sales grew **12%** month over month.",
                 "sections": [
                   {"type": "DATASET", "title": "Monthly Sales Trend", "dataset_refs": ["step-1"], "display": true}
                 ]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals(1, result.sections().size());
        assertEquals("Monthly Sales Trend", result.sections().get(0).title());
    }

    // ── 17. Existing frontend rendering compatibility — schema shape is unchanged ───────────

    @Test
    void responseContractShapeIsUnchangedByThePromptRewrite_frontendCompatible() {
        // The frontend (Chat.jsx) reads sections by `type` tag and metrics as a sibling — none of
        // that shape changed by the prompt rewrite. NOTE: this is no longer true of
        // follow_up_questions_reasoning specifically — that top-level field was REMOVED entirely
        // by the later structural fix (see ChatService#dataAnswerJsonSchema's javadoc); its
        // reasoning now lives on the FOLLOW_UP_QUESTIONS section itself. This test asserts the
        // CURRENT top-level contract, not the original prompt-rewrite-only snapshot.
        var schema = ChatService.dataAnswerJsonSchema();
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) schema.get("properties");
        assertEquals(java.util.Set.of("answer", "sections", "metrics"), properties.keySet(),
                "the top-level response contract has exactly these fields — no independent "
                        + "follow_up_questions_reasoning and no separate top-level follow_up_questions field");
    }

    // ── The task's own worked example: "show me all open purchase orders" ───────────────────

    @Test
    void showMeAllOpenPurchaseOrders_goodShapeExample_parsesCleanlyEndToEnd() {
        String json = """
                {
                  "answer": "There are **5 open purchase orders**, with Marcus Webb accounting for **3 of 5** orders.",
                  "sections": [
                    {
                      "type": "DATASET",
                      "title": "Open Purchase Orders",
                      "purpose": null,
                      "dataset_refs": ["step-1"],
                      "display": true,
                      "items": null,
                      "content": null
                    },
                    {
                      "type": "HIGHLIGHT",
                      "title": "Status",
                      "purpose": null,
                      "dataset_refs": ["step-1"],
                      "display": null,
                      "items": null,
                      "content": "Three of the five open orders are partially received."
                    },
                    {
                      "type": "FOLLOW_UP_QUESTIONS",
                      "title": "Continue investigating",
                      "purpose": null,
                      "dataset_refs": ["step-1"],
                      "display": null,
                      "items": [
                        "Which open purchase orders are partially received?",
                        "Which open orders have the earliest expected delivery dates?",
                        "Show open purchase orders by buyer."
                      ],
                      "content": null,
                      "reasoning": "The result supports drilling into order status, delivery timing, and buyer distribution."
                    }
                  ],
                  "metrics": [
                    {"label": "Open Orders", "value": "5"},
                    {"label": "Top Buyer", "value": "Marcus Webb"}
                  ]
                }
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals(3, result.sections().size());
        assertEquals(2, result.metrics().size());
        assertEquals(3, result.followUpQuestions().size());
        StructuredAnswer.Section followUps = result.sections().stream()
                .filter(s -> "FOLLOW_UP_QUESTIONS".equals(s.type())).findFirst().orElseThrow();
        assertEquals("The result supports drilling into order status, delivery timing, and buyer distribution.",
                followUps.reasoning(), "reasoning is structurally attached to the same FOLLOW_UP_QUESTIONS section as items");
        assertEquals(1, result.keyFindings().size(), "the HIGHLIGHT contributes exactly one non-duplicative finding");
        assertFalse(result.keyFindings().get(0).contains("Marcus Webb"),
                "the HIGHLIGHT is the genuinely additional status fact, not a restatement of the answer's Marcus Webb figure");

        InvestigationDataset step1 = dataset(1, "Open orders",
                row("po", "PO-1"), row("po", "PO-2"), row("po", "PO-3"), row("po", "PO-4"), row("po", "PO-5"));
        List<ResponseArtifacts.Section> resolved = ChatService.resolveSections(result.sections(), List.of(step1), "run-good");
        assertEquals(3, resolved.size(), "every section's single step-1 reference resolves cleanly");
    }
}
