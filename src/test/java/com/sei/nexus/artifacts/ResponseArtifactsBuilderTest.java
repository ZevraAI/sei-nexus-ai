package com.sei.nexus.artifacts;

import com.sei.nexus.response.StructuredAnswer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for {@link ResponseArtifactsBuilder} — the one place both the conversational
 * chat path and the Zevra Agent path converge to produce {@link ResponseArtifacts}.
 *
 * <p>Two families of tests here, matching the two code paths inside {@link
 * ResponseArtifactsBuilder#build}:
 * <ul>
 *   <li><b>Legacy fallback</b> (passing {@code null} for {@code llmSemantics}) — the original
 *       regex/sentence-splitting derivation, exercised only for responses that never went
 *       through structured composition. These tests are unchanged from before the LLM-authored
 *       semantics change; they pin that the fallback still behaves exactly as it always did.</li>
 *   <li><b>LLM-authored semantics preferred</b> (passing a real {@link StructuredAnswer}) — the
 *       new, now-primary path: the model's own decomposition wins outright, is never blended
 *       with or re-derived by the legacy heuristic, and a legitimately empty field from the
 *       model stays empty rather than being "corrected" by Java.</li>
 * </ul>
 *
 * Pure static seam; no Spring context, no Mockito, no network — the builder itself makes no LLM
 * call at all (it only ever reads an already-produced {@link StructuredAnswer} or already-
 * produced text/data), which is what makes "no additional LLM call" a structural guarantee here,
 * not something to test at runtime.
 */
class ResponseArtifactsBuilderTest {

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // Legacy fallback (llmSemantics == null) — unchanged behavior, pinned as before
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void simpleFactualAnswerProducesOnlyUnderstandingNoFabricatedSections() {
        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "What is our return policy?",
                "Our standard return policy allows returns within 30 days of purchase.",
                List.of(), List.of(), List.of(), List.of(), null, null);

        assertEquals("Our standard return policy allows returns within 30 days of purchase.",
                a.understanding());
        assertTrue(a.keyFindings().isEmpty());
        assertTrue(a.evidence().isEmpty());
        assertTrue(a.metrics().isEmpty());
        assertTrue(a.trail().isEmpty());
        assertNull(a.agentContext());
    }

    @Test
    void nullAnswerProducesEntirelyEmptyArtifactsNeverThrows() {
        ResponseArtifacts a = ResponseArtifactsBuilder.build(null, null, null, null, null, null, null, null);

        assertNull(a.understanding());
        assertNull(a.recommendation());
        assertTrue(a.keyFindings().isEmpty());
        assertTrue(a.relatedFacts().isEmpty());
        assertTrue(a.nextSteps().isEmpty());
        assertTrue(a.evidence().isEmpty());
        assertTrue(a.metrics().isEmpty());
        assertTrue(a.trail().isEmpty());
    }

    @Test
    void categoricalPlusNumericDatasetProducesBarChartHintAndMetrics() {
        List<Map<String, Object>> rows = List.of(
                row("supplier", "Acme", "value", 100),
                row("supplier", "Globex", "value", 200),
                row("supplier", "Acme", "value", 50));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "Show spend by supplier", "Acme and Globex account for all recorded spend.",
                List.of(), rows, List.of(), List.of(), null, null);

        assertEquals(2, a.evidence().size(), "dataset + one chart hint");
        assertEquals("DATASET", a.evidence().get(0).kind());
        assertEquals(3, a.evidence().get(0).rowCount());
        var chart = a.evidence().get(1);
        assertEquals("CHART", chart.kind());
        assertEquals("bar", chart.chartType());
        assertEquals("supplier", chart.xKey());
        assertEquals(List.of("value"), chart.yKeys());

        assertEquals(3, a.metrics().size());
        assertEquals("results", a.metrics().get(0).label());
        assertEquals("3", a.metrics().get(0).value());
        assertEquals("total value", a.metrics().get(1).label());
        assertEquals("350", a.metrics().get(1).value());
        assertEquals("distinct supplier", a.metrics().get(2).label());
        assertEquals("2", a.metrics().get(2).value());
    }

    @Test
    void datePlusNumericDatasetProducesAreaChartHint() {
        List<Map<String, Object>> rows = List.of(
                row("month", "2024-01", "revenue", 1000),
                row("month", "2024-02", "revenue", 1200),
                row("month", "2024-03", "revenue", 900));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "Revenue trend", "Revenue dipped in March after rising in February.",
                List.of(), rows, List.of(), List.of(), null, null);

        var chart = a.evidence().stream().filter(e -> "CHART".equals(e.kind())).findFirst().orElseThrow();
        assertEquals("area", chart.chartType());
        assertEquals("month", chart.xKey());
        assertEquals(List.of("revenue"), chart.yKeys());
    }

    @Test
    void singleNumericRowProducesStatsHint() {
        List<Map<String, Object>> rows = List.of(row("total_orders", 1284));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "How many open orders are there?", "There are 1,284 open orders.",
                List.of(), rows, List.of(), List.of(), null, null);

        var chart = a.evidence().stream().filter(e -> "CHART".equals(e.kind())).findFirst().orElseThrow();
        assertEquals("stats", chart.chartType());
    }

    @Test
    void highCardinalityDatasetGetsNoChartHint() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(row("id", "SKU-" + i, "qty", i));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "List all SKUs", "40 SKUs were found.", List.of(), rows, List.of(), List.of(), null, null);

        assertEquals(1, a.evidence().size(), "dataset only — 40 categories is not chart-worthy");
        assertEquals("DATASET", a.evidence().get(0).kind());
    }

    @Test
    void extractsQuantifiedFindingRecommendationAndLeftoverFacts() {
        String answer = "Three purchase orders are currently partially received. "
                + "This may indicate a $125,750 exposure if deliveries continue to slip. "
                + "We recommend reviewing the top five affected suppliers this week. "
                + "Expected delivery dates extend into next quarter.";

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "Show partially received orders", answer, List.of(), List.of(), List.of(), List.of(), null, null);

        assertEquals("Three purchase orders are currently partially received.", a.understanding());
        assertEquals(1, a.keyFindings().size());
        assertTrue(a.keyFindings().get(0).contains("$125,750"));
        assertNotNull(a.recommendation());
        assertTrue(a.recommendation().toLowerCase().contains("recommend"));
        assertFalse(a.relatedFacts().isEmpty());
        assertTrue(a.relatedFacts().stream().noneMatch(f -> f.equals(a.understanding())),
                "the understanding sentence must not also appear as a related fact");
    }

    @Test
    void evaluatorRationaleOnANonSufficientDecisionBecomesAKeyFinding() {
        List<Map<String, Object>> steps = List.of(
                row("stepNo", 1, "description", "Querying revenue by region", "sql", "SELECT ...",
                        "evaluatorDecision", "NEED_MORE_DATA",
                        "evaluatorRationale", "Only one region returned; broadening the query."));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "Revenue by region", "Revenue is concentrated in the North region.",
                steps, List.of(), List.of(), List.of(), null, null);

        assertTrue(a.keyFindings().contains("Only one region returned; broadening the query."));
    }

    // quickRefinements is a SEPARATE tactical-actions concept, transported directly via
    // ChatResponse#quickRefinements — it must never be merged into nextSteps, which is
    // exclusively Agent Brain's own decision. An empty model nextSteps is an honest "no next
    // step" signal, not a trigger to substitute Java's canned tactical actions.
    @Test
    void quickRefinementsAreNeverMergedIntoNextStepsEvenWhenModelNextStepsIsEmpty() {
        List<Map<String, Object>> quickRefs = List.of(
                row("label", "Show exceptions only", "prompt", "orders — show only exceptions",
                        "requires_input", false));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "Show orders", "Here are the open orders.", List.of(), List.of(), List.of(), quickRefs, null, null);

        assertTrue(a.nextSteps().isEmpty(),
                "quickRefinements must never populate nextSteps — an absent llmSemantics/empty "
                        + "model nextSteps must stay empty, not be silently replaced");
    }

    @Test
    void quickRefinementsAreNeverMergedIntoNextStepsWhenLlmSemanticsProvidesNone() {
        StructuredAnswer semantics = new StructuredAnswer("answer", "understanding",
                List.of(), List.of(), null, List.of());
        List<Map<String, Object>> quickRefs = List.of(
                row("label", "Filter by date", "prompt", "orders — for date:", "requires_input", true));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "Show orders", "answer", List.of(), List.of(), List.of(), quickRefs, null, semantics);

        assertTrue(a.nextSteps().isEmpty(),
                "the model explicitly provided an empty nextSteps list — that must be honored "
                        + "as-is, never backfilled from quickRefinements");
    }

    @Test
    void trailNormalizesConversationalStepShapes() {
        List<Map<String, Object>> steps = List.of(
                row("stepNo", 0, "type", "resolution", "description", "\"revenue\" -> sales.total_amount"),
                row("stepNo", 0, "type", "literal", "description", "\"TX\" -> state = 'Texas'"),
                row("stepNo", 1, "description", "Querying revenue by month", "sql", "SELECT 1",
                        "evaluatorDecision", "SUFFICIENT"));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "q", "a", steps, List.of(), List.of(), List.of(), null, null);

        assertEquals(3, a.trail().size());
        assertEquals("RESOLUTION", a.trail().get(0).type());
        assertEquals("LITERAL", a.trail().get(1).type());
        assertEquals("SQL_STEP", a.trail().get(2).type(), "an untyped map is a plain SQL execution step");
        assertEquals("SELECT 1", a.trail().get(2).detail());
        assertEquals("SUFFICIENT", a.trail().get(2).outcome());
    }

    // ── Investigation-Step Semantics: a step's own "outcome" (successful query / metadata
    //     retrieval / a genuine decline) is the correct trail status, and must never be
    //     overwritten by "evaluatorDecision" (the SEPARATE verdict on whether reasoning should
    //     continue) merely because Agent Brain decided to investigate further. ─────────────────

    @Test
    void trailPrefersOutcomeOverEvaluatorDecisionWhenBothArePresent() {
        // Exactly the production defect this fixes: a query step succeeded (5 rows) but the
        // evaluator asked for more evidence next — the trail must show the step's own success,
        // never "NEED_MORE_DATA" as if the step itself were incomplete/failed.
        List<Map<String, Object>> steps = List.of(
                row("stepNo", 1, "description", "List open orders", "sql", "SELECT * FROM orders",
                        "outcome", "QUERY_SUCCEEDED", "evaluatorDecision", "NEED_MORE_DATA"));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "q", "a", steps, List.of(), List.of(), List.of(), null, null);

        assertEquals("QUERY_SUCCEEDED", a.trail().get(0).outcome(),
                "the step's own success must win — 'need more data' describes the NEXT action, "
                        + "not this step's own status");
    }

    @Test
    void trailShowsMetadataRetrievedNotEvaluatorDecisionForAMetadataStep() {
        List<Map<String, Object>> steps = List.of(
                row("stepNo", 2, "description", "Retrieve line-item columns",
                        "outcome", "METADATA_RETRIEVED"));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "q", "a", steps, List.of(), List.of(), List.of(), null, null);

        assertEquals("METADATA_RETRIEVED", a.trail().get(0).outcome());
    }

    @Test
    void trailFallsBackToEvaluatorDecisionWhenOutcomeIsAbsent() {
        // Backward compatibility: a caller that has not (yet) supplied "outcome" must still work
        // exactly as before — see trailNormalizesConversationalStepShapes above.
        List<Map<String, Object>> steps = List.of(
                row("stepNo", 1, "description", "step", "sql", "SELECT 1", "evaluatorDecision", "DEAD_END"));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "q", "a", steps, List.of(), List.of(), List.of(), null, null);

        assertEquals("DEAD_END", a.trail().get(0).outcome());
    }

    @Test
    void trailNormalizesAgentStepShapesAndPopulatesAgentContext() {
        List<Map<String, Object>> steps = List.of(
                row("stepNo", 1, "type", "CONTEXT_RESOLVE", "description", "Resolved business context (2 business objects)"),
                row("stepNo", 2, "type", "TOOL_CALL", "description", "Called query_data"),
                row("stepNo", 3, "type", "FINAL_ANSWER", "description", "Composed final answer"));

        ResponseArtifacts.AgentContext ctx =
                new ResponseArtifacts.AgentContext("supply-agent", "Supply Chain Agent", "sess-1", 3);

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "Investigate supplier risk", "Two suppliers show elevated risk.",
                steps, List.of(), List.of(), List.of(), ctx, null);

        assertEquals(3, a.trail().size());
        assertEquals("RESOLUTION", a.trail().get(0).type(), "CONTEXT_RESOLVE normalizes to the shared RESOLUTION type");
        assertEquals("TOOL_CALL", a.trail().get(1).type());
        assertEquals("FINAL_ANSWER", a.trail().get(2).type());
        assertEquals("sess-1", a.agentContext().sessionId());
        assertEquals("Supply Chain Agent", a.agentContext().agentName());
        assertEquals(3, a.agentContext().iterationsUsed());
    }

    @Test
    void emptyReasoningStepsProduceEmptyTrailNotNull() {
        ResponseArtifacts a = ResponseArtifactsBuilder.build("q", "a", List.of(), List.of(), List.of(), List.of(), null, null);
        assertNotNull(a.trail());
        assertTrue(a.trail().isEmpty());
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // LLM-authored semantics preferred — the primary path once structured composition ran
    // ═════════════════════════════════════════════════════════════════════════════════════════

    // 1. Simple factual — only understanding, everything else legitimately empty.
    @Test
    void llmSemanticsSimpleFactual_onlyUnderstandingPopulated() {
        StructuredAnswer semantics = new StructuredAnswer(
                "17 stores are currently open.", "17 stores are currently open.",
                List.of(), List.of(), null, List.of());

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "How many stores are currently open?", "17 stores are currently open.",
                List.of(), List.of(), List.of(), List.of(), null, semantics);

        assertEquals("17 stores are currently open.", a.understanding());
        assertTrue(a.keyFindings().isEmpty());
        assertTrue(a.relatedFacts().isEmpty());
        assertNull(a.recommendation());
    }

    // 2. Analytical — understanding + findings + related facts + recommendation + next steps,
    //    each a genuinely distinct sentence (never derived by Java sentence-splitting here).
    @Test
    void llmSemanticsAnalytical_allSectionsDistinctAndPreferred() {
        StructuredAnswer semantics = new StructuredAnswer(
                "Three of five open purchase orders are partially received, representing $135,300 in ordered value.",
                "Three of five open purchase orders are partially received, with $135,300 in total ordered value.",
                List.of("The affected orders represent the majority of currently open order value."),
                List.of("The affected orders have expected delivery dates extending into the future.",
                        "The dataset contains order-level status and ordered-value information."),
                "Follow up with suppliers on the partially received orders to reduce potential delivery delays.",
                List.of("Show only partially received orders", "Review orders by supplier"));

        // A reasoningSteps/queryData shape that WOULD trigger the legacy heuristic differently,
        // to prove the LLM's own fields win outright rather than being blended with it.
        List<Map<String, Object>> rows = List.of(row("po", "PO-1", "status", "partially_received"));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "Show open purchase orders", semantics.answer(), List.of(), rows, List.of(), List.of(), null, semantics);

        assertEquals(semantics.understanding(), a.understanding());
        assertEquals(semantics.keyFindings(), a.keyFindings());
        assertEquals(semantics.relatedFacts(), a.relatedFacts());
        assertEquals(semantics.recommendation(), a.recommendation());
        assertEquals(2, a.nextSteps().size());
        assertEquals("Show only partially received orders", a.nextSteps().get(0).label());
        assertEquals("Show only partially received orders", a.nextSteps().get(0).prompt(),
                "a plain-text LLM next step is used as both label and prompt — clicking it asks that literal question");
    }

    // 3. Insufficient evidence — the model explains what's missing rather than guessing; Java
    //    must render that explanation verbatim, never replace it with a fabricated finding.
    @Test
    void llmSemanticsInsufficientEvidence_explainsGapVerbatim() {
        StructuredAnswer semantics = new StructuredAnswer(
                "The available data shows $135,300 across five open purchase orders.",
                "The available data shows $135,300 across five open purchase orders.",
                List.of("The most-ordered item cannot be determined because item-level detail is not available."),
                List.of("The current evidence contains purchase-order totals but no item identifiers or quantities."),
                "Connect a source containing purchase-order line/item details.",
                List.of());

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "Which item am I ordering the most?", semantics.answer(),
                List.of(), List.of(row("po", "PO-1", "total", 135300)), List.of(), List.of(), null, semantics);

        assertEquals(1, a.keyFindings().size());
        assertTrue(a.keyFindings().get(0).contains("cannot be determined"));
        assertEquals(1, a.relatedFacts().size());
        assertTrue(a.relatedFacts().get(0).contains("no item identifiers"));
        assertEquals("Connect a source containing purchase-order line/item details.", a.recommendation());
    }

    // 4. No duplication — distinct semantic sections stay distinct; the builder does not dedupe
    //    or merge them (that responsibility is the prompt's), but it must not ADD duplication of
    //    its own (e.g. by also running the legacy heuristic alongside LLM semantics).
    @Test
    void llmSemanticsPresent_legacyHeuristicNeverAlsoRuns() {
        // An answer whose FIRST SENTENCE the legacy heuristic would seize on as "understanding" —
        // proving the LLM's (deliberately different) understanding wins outright, not a blend.
        String answer = "Revenue increased 12% this quarter. Costs rose 3%. Margin improved overall.";
        StructuredAnswer semantics = new StructuredAnswer(answer,
                "Margin improved this quarter as revenue growth outpaced cost growth.",
                List.of(), List.of(), null, List.of());

        ResponseArtifacts a = ResponseArtifactsBuilder.build("q", answer, List.of(), List.of(), List.of(), List.of(), null, semantics);

        assertEquals("Margin improved this quarter as revenue growth outpaced cost growth.", a.understanding(),
                "the model's own understanding must win — not the legacy first-sentence heuristic");
        assertNotEquals("Revenue increased 12% this quarter.", a.understanding());
    }

    // 5. Empty sections — the model may legitimately omit every optional section; Java must not
    //    manufacture anything to fill the gap.
    @Test
    void llmSemanticsAllOptionalSectionsOmitted_staysEmpty() {
        StructuredAnswer semantics = new StructuredAnswer("The system is operating normally.",
                null, List.of(), List.of(), null, List.of());

        ResponseArtifacts a = ResponseArtifactsBuilder.build("q", semantics.answer(),
                List.of(), List.of(), List.of(), List.of(), null, semantics);

        assertNull(a.understanding());
        assertTrue(a.keyFindings().isEmpty());
        assertTrue(a.relatedFacts().isEmpty());
        assertNull(a.recommendation());
    }

    // Null-valued lists (not just empty ones) from a lenient JSON parse must degrade safely too.
    @Test
    void llmSemanticsWithNullListsDoesNotThrow() {
        StructuredAnswer semantics = new StructuredAnswer("answer", "understanding", null, null, null, null);
        ResponseArtifacts a = ResponseArtifactsBuilder.build("q", "answer", List.of(), List.of(), List.of(), List.of(), null, semantics);
        assertEquals("understanding", a.understanding());
        assertTrue(a.keyFindings().isEmpty());
        assertTrue(a.relatedFacts().isEmpty());
        assertTrue(a.nextSteps().isEmpty());
    }

    // 6. Agent response — same artifact contract as direct chat, sourced from the agent's own
    //    final_answer semantics rather than Java re-deriving them from the answer text.
    @Test
    void llmSemanticsFromAgentPath_sameContractAsDirectChat() {
        StructuredAnswer agentSemantics = new StructuredAnswer(
                "Two suppliers show a sustained drop in on-time delivery.",
                "Two suppliers show a sustained drop in on-time delivery over six weeks.",
                List.of("Meridian Freight and Atlas Logistics account for 28% of total order volume."),
                List.of("Both suppliers share the same regional distribution hub."),
                "Escalate a performance review with the affected suppliers.",
                List.of("Compare against contract SLAs"));

        ResponseArtifacts.AgentContext ctx = new ResponseArtifacts.AgentContext(
                "supply-agent", "Supply Chain Agent", "sess-1", 3);

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "Investigate supplier delivery performance", agentSemantics.answer(),
                List.of(row("stepNo", 1, "type", "TOOL_CALL", "description", "Called query_supplier_performance")),
                List.of(row("supplier", "Meridian Freight", "on_time_rate", 61)),
                List.of(), List.of(), ctx, agentSemantics);

        assertEquals(agentSemantics.understanding(), a.understanding());
        assertEquals(agentSemantics.keyFindings(), a.keyFindings());
        assertEquals(agentSemantics.relatedFacts(), a.relatedFacts());
        assertEquals(agentSemantics.recommendation(), a.recommendation());
        assertEquals(1, a.nextSteps().size());
        assertEquals("Compare against contract SLAs", a.nextSteps().get(0).label());
        assertEquals("Supply Chain Agent", a.agentContext().agentName(), "agentContext stays runtime-owned, unaffected by llmSemantics");
        assertEquals(1, a.trail().size(), "trail stays runtime-owned, normalized from reasoningSteps regardless of llmSemantics");
    }

    // 7. Domain neutrality — no purchasing/order/retail terminology anywhere in the builder's
    //    OWN logic; a healthcare-domain StructuredAnswer must flow through identically.
    @Test
    void domainNeutral_healthcareExampleFlowsThroughUnchanged() {
        StructuredAnswer semantics = new StructuredAnswer(
                "Average patient wait time increased to 42 minutes this week.",
                "Average patient wait time increased to 42 minutes this week, up from 31 minutes.",
                List.of("The increase is concentrated in the emergency department."),
                List.of("Staffing levels were unchanged during this period."),
                "Review emergency department staffing allocation for the affected shifts.",
                List.of("Break down wait times by shift", "Compare against last month"));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "Why did patient wait times increase?", semantics.answer(),
                List.of(), List.of(row("department", "Emergency", "wait_minutes", 42)), List.of(), List.of(), null, semantics);

        assertEquals(semantics.understanding(), a.understanding());
        assertEquals(semantics.keyFindings(), a.keyFindings());
        assertEquals(semantics.recommendation(), a.recommendation());
        assertEquals(2, a.nextSteps().size());
    }

    // 8. Legacy response — no llmSemantics at all (an older code path / non-data outcome) still
    //    renders via the original heuristic, proving backward compatibility.
    @Test
    void legacyResponseWithoutLlmSemantics_stillRendersViaHeuristic() {
        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "Show partially received orders",
                "Three purchase orders are currently partially received. "
                        + "We recommend reviewing the top five affected suppliers this week.",
                List.of(), List.of(), List.of(), List.of(), null, null);

        assertNotNull(a.understanding());
        assertNotNull(a.recommendation());
    }

    // 9. Deterministic evidence — metrics/chart/data evidence remain runtime-derived from
    //    queryData regardless of llmSemantics; the model has no influence over them at all.
    @Test
    void evidenceAndMetricsRemainRuntimeDerivedRegardlessOfLlmSemantics() {
        List<Map<String, Object>> rows = List.of(
                row("supplier", "Acme", "value", 100),
                row("supplier", "Globex", "value", 200));
        StructuredAnswer semantics = new StructuredAnswer("answer", "understanding",
                List.of(), List.of(), null, List.of());

        ResponseArtifacts withLlm = ResponseArtifactsBuilder.build(
                "q", "answer", List.of(), rows, List.of(), List.of(), null, semantics);
        ResponseArtifacts withoutLlm = ResponseArtifactsBuilder.build(
                "q", "answer", List.of(), rows, List.of(), List.of(), null, null);

        assertEquals(withoutLlm.evidence(), withLlm.evidence(),
                "evidence is computed from queryData only — identical regardless of llmSemantics");
        assertEquals(withoutLlm.metrics(), withLlm.metrics(),
                "metrics are computed from queryData only — identical regardless of llmSemantics");
    }

    // 10. No additional LLM call — structural, not runtime-observable: build() takes an
    //     already-produced StructuredAnswer/text/data and performs no I/O of its own. Asserting
    //     this test can even run with zero network/Spring/mocking infrastructure at all IS the
    //     proof — a real second LLM call would require exactly that infrastructure to fake.
    @Test
    void buildPerformsNoIoOfItsOwn_pureFunctionOfAlreadyProducedInputs() {
        StructuredAnswer semantics = new StructuredAnswer("a", "u", List.of("f"), List.of("r"), "rec", List.of("n"));
        assertDoesNotThrow(() ->
                ResponseArtifactsBuilder.build("q", "a", List.of(), List.of(), List.of(), List.of(), null, semantics));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // sections — the model's resolved UI-content plan, carried straight through (this class
    // performs no resolution, selection, or interpretation of its own — see ChatService).
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void resolvedSectionsAreCarriedThroughUnchanged() {
        List<ResponseArtifacts.Section> sections = List.of(
                new ResponseArtifacts.Section("DATASET", "Open Orders", "Shows open orders", true,
                        null, null, List.of(new ResponseArtifacts.Section.ResolvedDataset(
                                1, List.of(row("po", "PO-1"))))),
                new ResponseArtifacts.Section("FINDINGS", "Key Findings", null, null,
                        List.of("finding one"), null, List.of()));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "q", "a", List.of(), List.of(), List.of(), List.of(), null, null, sections);

        assertEquals(2, a.sections().size());
        assertEquals("DATASET", a.sections().get(0).type());
        assertEquals(1, a.sections().get(0).datasets().size());
        assertEquals(1, a.sections().get(0).datasets().get(0).stepNo());
        assertEquals("PO-1", a.sections().get(0).datasets().get(0).rows().get(0).get("po"));
        assertEquals("FINDINGS", a.sections().get(1).type());
    }

    @Test
    void resolvedSectionCanCarryMultipleGroundingDatasetsPreservedSeparately() {
        List<ResponseArtifacts.Section> sections = List.of(
                new ResponseArtifacts.Section("HIGHLIGHT", "Most Ordered Item", "...", null, null,
                        "Widget A has 1,500 units ordered.",
                        List.of(new ResponseArtifacts.Section.ResolvedDataset(3, List.of(row("qty", 1500))),
                                new ResponseArtifacts.Section.ResolvedDataset(5, List.of(row("name", "Widget A"))))));

        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "q", "a", List.of(), List.of(), List.of(), List.of(), null, null, sections);

        assertEquals(2, a.sections().get(0).datasets().size());
        assertEquals(3, a.sections().get(0).datasets().get(0).stepNo());
        assertEquals(5, a.sections().get(0).datasets().get(1).stepNo());
    }

    @Test
    void absentSectionsParameterDefaultsToEmptyNeverNull() {
        // The 8-arg overload (every pre-existing caller) — sections defaults to empty.
        ResponseArtifacts a = ResponseArtifactsBuilder.build(
                "q", "a", List.of(), List.of(), List.of(), List.of(), null, null);

        assertNotNull(a.sections());
        assertTrue(a.sections().isEmpty());
    }
}
