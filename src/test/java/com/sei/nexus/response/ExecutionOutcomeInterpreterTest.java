package com.sei.nexus.response;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified Answer Engine, Phase 4 — Step 1. Golden-master coverage for the shared
 * {@link ExecutionOutcomeInterpreter}. Every assertion pins the exact string the conversational
 * path's former {@code buildRowSummary} produced, so the extraction is proven byte-identical and
 * the deterministic interpretation is now owned in one place.
 */
class ExecutionOutcomeInterpreterTest {

    private final ExecutionOutcomeInterpreter interp = new ExecutionOutcomeInterpreter();

    /** Ordered row so column iteration order is deterministic in the assertions. */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void emptyRowsProduceTheZeroRowSummary() {
        assertEquals("Query returned 0 rows.\n", interp.summarizeRows(List.of()));
    }

    @Test
    void lowCardinalityStringColumnRendersADistribution() {
        List<Map<String, Object>> rows = List.of(
                row("status", "open"), row("status", "open"),
                row("status", "closed"), row("status", "pending"));

        // 4 rows, 3 distinct values (2 ≤ 3 ≤ 8 and 3 < 4) → distribution. The counts come from a
        // HashMap, so we assert content, not map ordering (matching the original behaviour).
        String out = interp.summarizeRows(rows);
        assertTrue(out.startsWith("Total rows: 4\nColumns: status\n  status distribution: {"), out);
        assertTrue(out.contains("open=2"), out);
        assertTrue(out.contains("closed=1"), out);
        assertTrue(out.contains("pending=1"), out);
        assertTrue(out.endsWith("}\n"), out);
    }

    @Test
    void numericNonIdColumnRendersSumAndAverage() {
        List<Map<String, Object>> rows = List.of(
                row("qty", 10), row("qty", 20), row("qty", 30));

        // all-numeric, not an id → sum/avg, formatted with two decimals
        assertEquals(
                "Total rows: 3\n"
                        + "Columns: qty\n"
                        + "  qty: sum=60.00, avg=20.00\n",
                interp.summarizeRows(rows));
    }

    @Test
    void idColumnsAreExcludedFromNumericSummaryButStillRenderedAsValuesForASmallResult() {
        List<Map<String, Object>> rows = List.of(
                row("id", 1, "product_id", 100), row("id", 2, "product_id", 200));

        // `id`/`*_id` are excluded from the numeric sum/avg branch, and not low-cardinality
        // (2 distinct out of 2 rows) — but a small result (<= SMALL_RESULT_ROW_LIMIT) must still
        // surface their actual values via the Values: block, not drop them silently.
        assertEquals(
                "Total rows: 2\nColumns: id, product_id\nValues:\n  id = 1, 2\n  product_id = 100, 200\n",
                interp.summarizeRows(rows));
    }

    @Test
    void blankAndNullValuesAreFilteredFromTheSummary() {
        List<Map<String, Object>> rows = List.of(
                row("region", "north"), row("region", "north"),
                row("region", "south"), row("region", ""), row("region", "null"));

        // "" and "null" are filtered; remaining distinct values {north, south} over 5 rows → distribution
        String out = interp.summarizeRows(rows);
        assertTrue(out.startsWith("Total rows: 5\nColumns: region\n  region distribution: {"), out);
        assertTrue(out.contains("north=2"), out);
        assertTrue(out.contains("south=1"), out);
        assertFalse(out.contains("null="), "the literal string \"null\" is filtered, not counted");
        assertFalse(out.contains("=0"), "blank values are filtered, not counted");
    }

    @Test
    void allSameValueIsNeitherDistributionNorNumericSummaryButStillRenderedAsValues() {
        List<Map<String, Object>> rows = List.of(
                row("kind", "A"), row("kind", "A"), row("kind", "A"));

        // one distinct value → not low-cardinality (needs >= 2), not numeric — but still a small
        // result, so the actual value(s) are rendered rather than dropped.
        assertEquals("Total rows: 3\nColumns: kind\nValues:\n  kind = A, A, A\n", interp.summarizeRows(rows));
    }

    // ── The fix: single-row descriptive/identifying values must reach the composition LLM ──────
    // (previously silently dropped — neither the distribution branch, which requires >= 2
    // distinct values, nor the numeric branch, which excludes id-suffixed columns, could ever
    // fire for a 1-row result's string/id columns).

    @Test
    void singleRowDescriptiveStringValuesReachTheSummaryNotJustColumnNames() {
        List<Map<String, Object>> rows = List.of(row("name", "Widget ABC", "sku", "SKU-123"));

        String out = interp.summarizeRows(rows);
        assertTrue(out.contains("name = Widget ABC"), out);
        assertTrue(out.contains("sku = SKU-123"), out);
    }

    @Test
    void singleRowIdentifierColumnValueReachesTheSummary() {
        // product_id is `_id`-suffixed (excluded from numeric sum/avg) and, with 1 row, can never
        // be "low cardinality" (that branch requires >= 2 distinct values) — previously dropped
        // entirely.
        List<Map<String, Object>> rows = List.of(row("product_id", "50000000-0001", "total_ordered_qty", 1500));

        String out = interp.summarizeRows(rows);
        assertTrue(out.contains("product_id = 50000000-0001"), out);
        assertTrue(out.contains("total_ordered_qty: sum=1500.00, avg=1500.00"), out);
    }

    @Test
    void largeResultDoesNotRenderAValuesBlockForAnUncoveredColumn() {
        // Conciseness for large results must be unaffected by this fix — the Values: block is
        // only for small results (<= SMALL_RESULT_ROW_LIMIT rows).
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(row("sku", "SKU-" + i));

        String out = interp.summarizeRows(rows);
        assertFalse(out.contains("Values:"), out);
    }

    // Domain neutrality — the fix is driven purely by row count and column shape, never by
    // column name or business domain; a healthcare-shaped single row behaves identically.
    @Test
    void domainNeutral_singleRowDescriptiveValueInHealthcareContext() {
        List<Map<String, Object>> rows = List.of(row("patient_name", "J. Rivera", "diagnosis_code", "R51"));

        String out = interp.summarizeRows(rows);
        assertTrue(out.contains("patient_name = J. Rivera"), out);
        assertTrue(out.contains("diagnosis_code = R51"), out);
    }
}
