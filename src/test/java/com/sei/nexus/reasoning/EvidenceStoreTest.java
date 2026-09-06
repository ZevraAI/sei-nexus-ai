package com.sei.nexus.reasoning;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the evidence-propagation defect: a successful prerequisite
 * step (e.g. resolving "bangle" to a product id) left the Planner's next step with no
 * way to see the value it just found — {@code EvidenceStore.buildRowSummary()} rendered
 * neither the numeric-aggregate nor the distribution branch for a small result's
 * identifier/name columns, so the next step's prompt never contained the real value.
 *
 * <p>{@code buildRowSummary()} is a pure function — no LLM, no database — so this suite
 * verifies it directly and deterministically, unlike the Planner's own prompt compliance.
 */
class EvidenceStoreTest {

    private String summaryFor(List<Map<String, Object>> rows) {
        EvidenceStore store = new EvidenceStore();
        store.add(1, "step", "SELECT ...", "conn-test", rows, null, null, null, 10L);
        return store.getSteps().get(0).rowSummary();
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void singleRowIdAndNameAreBothRenderedExplicitly() {
        // The exact demonstrated defect: id = 50000000-...-7703, name = Women's Gold Bangle Set.
        String summary = summaryFor(List.of(
                row("id", "50000000-0000-0000-0000-000000007703", "name", "Women's Gold Bangle Set")));

        assertTrue(summary.contains("Values:"));
        assertTrue(summary.contains("id = 50000000-0000-0000-0000-000000007703"));
        assertTrue(summary.contains("name = Women's Gold Bangle Set"));
    }

    @Test
    void smallMultiRowResultRendersDistinctValuesPerColumn() {
        String summary = summaryFor(List.of(
                row("id", "101", "name", "Product A"),
                row("id", "102", "name", "Product B"),
                row("id", "103", "name", "Product C")));

        assertTrue(summary.contains("id = 101, 102, 103"));
        assertTrue(summary.contains("name = Product A, Product B, Product C"));
    }

    @Test
    void largeResultSetIsNotEnumerated() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            rows.add(row("id", String.valueOf(i), "name", "Product " + i));
        }
        String summary = summaryFor(rows);

        assertTrue(summary.contains("10000 row(s)"));
        assertFalse(summary.contains("Values:"),
                "a 10,000-row result must stay summarized, never enumerated");
        // Bounded regardless of row count — the whole point of the guardrail.
        assertTrue(summary.length() < 500,
                "summary length must not grow with row count: was " + summary.length());
    }

    @Test
    void numericAggregateBranchIsUnchanged() {
        String summary = summaryFor(List.of(
                row("on_hand_qty", "10"), row("on_hand_qty", "20"), row("on_hand_qty", "30")));

        assertTrue(summary.contains("on_hand_qty: sum=60, avg=20"));
        assertFalse(summary.contains("Values:"),
                "a plain numeric measure must still use the aggregate branch, not the new one");
    }

    @Test
    void numericIdColumnIsNowExplicitlyRendered() {
        // A numeric id was previously invisible in both existing branches: excluded from the
        // aggregate branch by isId, and excluded from the distribution branch by looksNumeric.
        String summary = summaryFor(List.of(row("customer_id", "88213")));

        assertTrue(summary.contains("Values:"));
        assertTrue(summary.contains("customer_id = 88213"));
    }

    @Test
    void categoricalDistributionBranchIsUnchanged() {
        String summary = summaryFor(List.of(
                row("status", "active"), row("status", "active"), row("status", "discontinued")));

        assertTrue(summary.contains("status distribution: {active=2, discontinued=1}"));
        assertFalse(summary.contains("Values:\n  status"),
                "a genuine low-cardinality distribution must keep using the existing branch");
    }

    @Test
    void longFreeTextValuesAreTruncated() {
        String longDescription = "x".repeat(200);
        String summary = summaryFor(List.of(row("id", "1", "description", longDescription)));

        assertTrue(summary.contains("description = " + "x".repeat(80) + "…"),
                "values in the new branch must be bounded per-value, not just per-row-count");
    }

    @Test
    void emptyResultIsUnchanged() {
        assertEquals("Query returned 0 rows.", summaryFor(List.of()));
    }

    // ── addMetadataStep: a Missing-Column Metadata Request step is not a query — its evidence
    //     must never claim "Query returned 0 rows." (the exact defect this method fixes) ──────

    @Test
    void metadataStepNeverRendersAsAnEmptyQueryResult() {
        EvidenceStore store = new EvidenceStore();
        store.addMetadataStep(1, "Retrieve columns", "columns were omitted",
                "METADATA_RETRIEVED", "Retrieved 3 column(s) of metadata for 'order_lines'.");

        String summary = store.getSteps().get(0).rowSummary();

        assertFalse(summary.contains("Query returned"),
                "a metadata retrieval must never be described as a query, empty or otherwise");
        assertFalse(summary.contains("0 rows"));
        assertEquals("Retrieved 3 column(s) of metadata for 'order_lines'.", summary);
    }

    @Test
    void metadataStepCarriesNoRowsAndNoSql() {
        EvidenceStore store = new EvidenceStore();
        store.addMetadataStep(1, "Retrieve columns", "r", "METADATA_RETRIEVED", "Retrieved 2 column(s).");

        EvidenceStore.StepEvidence step = store.getSteps().get(0);
        assertTrue(step.rows().isEmpty(), "a metadata step never executed a query — it has no rows");
        assertEquals("", step.sql());
    }

    @Test
    void metadataUnavailableStepIsAlsoRenderedDescriptivelyNotAsAnEmptyQuery() {
        EvidenceStore store = new EvidenceStore();
        store.addMetadataStep(1, "Retrieve columns", "r", "METADATA_UNAVAILABLE",
                "'unknown_table' is not part of the already-resolved/approved object set for this "
                        + "question — metadata request rejected.");

        String summary = store.getSteps().get(0).rowSummary();
        assertFalse(summary.contains("Query returned"));
        assertTrue(summary.contains("not part of the already-resolved/approved object set"));
    }

    @Test
    void metadataStepAppearsCorrectlyInTheLlmFacingEvidenceTranscript() {
        EvidenceStore store = new EvidenceStore();
        store.addMetadataStep(2, "Retrieve columns for order_lines", "columns were omitted",
                "METADATA_RETRIEVED", "Retrieved 3 column(s) of metadata for 'order_lines'.");

        String transcript = store.buildContextForLlm();

        assertTrue(transcript.contains("Retrieved 3 column(s) of metadata for 'order_lines'."));
        assertFalse(transcript.contains("Query returned 0 rows"),
                "the evidence transcript the planner itself reads must not misrepresent a "
                        + "successful metadata retrieval as an empty query");
    }

    // ── Investigation-Step Semantics: `outcome` (this step's own result) is independent of
    //     `evaluatorDecision` (the evaluator's separate verdict on overall sufficiency) ─────────

    @Test
    void aSuccessfulQueryStepStaysSuccessfulEvenWhenTheEvaluatorAsksForMoreEvidence() {
        EvidenceStore store = new EvidenceStore();
        store.add(1, "Retrieve open purchase orders", "SELECT * FROM orders", "conn-1",
                List.of(row("id", "PO-1")), "r", "NEED_MORE_DATA", "need item detail too", 5L);

        EvidenceStore.StepEvidence step = store.getSteps().get(0);

        assertEquals("QUERY_SUCCEEDED", step.outcome(),
                "the query itself succeeded — Agent Brain deciding to investigate further is a "
                        + "SEPARATE, later decision and must never retroactively mark this step failed");
        assertEquals("NEED_MORE_DATA", step.evaluatorDecision(),
                "the reason for the NEXT action is preserved too, just in its own field");
    }

    @Test
    void needsMoreDataDoesNotOverwriteTheStatusOfTheSuccessfulPrecedingQuery() {
        EvidenceStore store = new EvidenceStore();
        store.add(1, "Step 1", "SELECT * FROM orders", "conn-1",
                List.of(row("id", "PO-1")), "r", "NEED_MORE_DATA", "keep going", 5L);
        store.add(2, "Step 2", "SELECT * FROM order_lines", "conn-1",
                List.of(row("product_id", "P-1")), "r", "SUFFICIENT", "answered", 5L);

        List<EvidenceStore.StepEvidence> steps = store.getSteps();
        assertEquals("QUERY_SUCCEEDED", steps.get(0).outcome(), "step 1 succeeded on its own merits");
        assertEquals("QUERY_SUCCEEDED", steps.get(1).outcome(), "step 2 also succeeded on its own merits");
        assertEquals("NEED_MORE_DATA", steps.get(0).evaluatorDecision());
        assertEquals("SUFFICIENT", steps.get(1).evaluatorDecision());
    }

    @Test
    void metadataRetrievalIsRepresentedAsACompletedOutcomeNotAnEvaluatedQuery() {
        EvidenceStore store = new EvidenceStore();
        store.addMetadataStep(1, "Retrieve columns", "r", "METADATA_RETRIEVED", "Retrieved 3 column(s).");

        EvidenceStore.StepEvidence step = store.getSteps().get(0);

        assertEquals("METADATA_RETRIEVED", step.outcome());
        assertNull(step.evaluatorDecision(),
                "metadata retrieval never executes a query for the evaluator to judge — no "
                        + "sufficiency verdict applies, and none must be fabricated");
    }

    @Test
    void aGenuineRejectionIsRepresentedByItsOwnOutcomeNeverAsAnEvaluatorVerdict() {
        EvidenceStore store = new EvidenceStore();
        store.addOutcome(1, "Blocked query", "SELECT * FROM invoices", "conn-1",
                "r", "UNAPPROVED_OBJECTS", "table not approved", 0L);

        EvidenceStore.StepEvidence step = store.getSteps().get(0);
        assertEquals("UNAPPROVED_OBJECTS", step.outcome());
        assertNull(step.evaluatorDecision());
        assertTrue(step.rows().isEmpty());
    }

    @Test
    void multipleSuccessfulQueryStepsAreAllRetainedSimultaneously() {
        EvidenceStore store = new EvidenceStore();
        store.add(1, "POs", "SELECT * FROM orders", "conn-1", List.of(row("po", "PO-1")),
                "r", "NEED_MORE_DATA", "need item too", 5L);
        store.addMetadataStep(2, "Columns", "r", "METADATA_RETRIEVED", "Retrieved 15 column(s).");
        store.add(3, "Top item", "SELECT product_id FROM order_lines", "conn-1",
                List.of(row("product_id", "P-1", "qty", 12)), "r", "NEED_MORE_DATA", "need product details", 5L);
        store.addMetadataStep(4, "Product columns", "r", "METADATA_RETRIEVED", "Retrieved 16 column(s).");
        store.add(5, "Product detail", "SELECT name, sku FROM products", "conn-1",
                List.of(row("name", "Widget", "sku", "W-1")), "r", "SUFFICIENT", "answered", 5L);

        List<EvidenceStore.StepEvidence> steps = store.getSteps();
        assertEquals(5, steps.size());
        // Every genuinely-executed query step's rows survive, independent of the others and of
        // the evaluator's per-step verdict.
        assertEquals("PO-1", steps.get(0).rows().get(0).get("po"));
        assertEquals("P-1", steps.get(2).rows().get(0).get("product_id"));
        assertEquals("Widget", steps.get(4).rows().get(0).get("name"));
        assertEquals("QUERY_SUCCEEDED", steps.get(0).outcome());
        assertEquals("METADATA_RETRIEVED", steps.get(1).outcome());
        assertEquals("QUERY_SUCCEEDED", steps.get(2).outcome());
        assertEquals("METADATA_RETRIEVED", steps.get(3).outcome());
        assertEquals("QUERY_SUCCEEDED", steps.get(4).outcome());
    }

    @Test
    void genuinelyEmptyQueryResultsStillBehaveCorrectly() {
        EvidenceStore store = new EvidenceStore();
        store.add(1, "No matches", "SELECT * FROM orders WHERE 1=0", "conn-1", List.of(),
                "r", "DEAD_END", "no evidence found", 5L);

        EvidenceStore.StepEvidence step = store.getSteps().get(0);
        assertEquals("QUERY_SUCCEEDED", step.outcome(),
                "the query itself executed without error — zero rows is a valid result, not a failure");
        assertEquals("DEAD_END", step.evaluatorDecision());
        assertEquals("Query returned 0 rows.", step.rowSummary());
    }
}
