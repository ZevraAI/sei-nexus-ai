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
    void idColumnsAreExcludedFromNumericSummary() {
        List<Map<String, Object>> rows = List.of(
                row("id", 1, "product_id", 100), row("id", 2, "product_id", 200));

        // `id` and `*_id` are excluded; no distribution (all distinct) → identity columns only, no lines
        assertEquals("Total rows: 2\nColumns: id, product_id\n", interp.summarizeRows(rows));
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
    void allSameValueIsNeitherDistributionNorNumericSummary() {
        List<Map<String, Object>> rows = List.of(
                row("kind", "A"), row("kind", "A"), row("kind", "A"));

        // one distinct value → not low-cardinality (needs ≥ 2), not numeric → header only
        assertEquals("Total rows: 3\nColumns: kind\n", interp.summarizeRows(rows));
    }
}
