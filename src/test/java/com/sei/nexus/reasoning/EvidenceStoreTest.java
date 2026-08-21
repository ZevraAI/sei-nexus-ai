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
}
