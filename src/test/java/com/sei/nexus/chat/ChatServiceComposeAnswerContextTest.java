package com.sei.nexus.chat;

import com.sei.nexus.reasoning.InvestigationDataset;
import com.sei.nexus.response.ExecutionOutcomeInterpreter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for {@link ChatService#buildInvestigationDatasetsBlock} — the exact text the
 * final answer-composition LLM receives as evidence. Proves the "single undifferentiated blob"
 * defect is fixed: every row-bearing investigation step reaches the prompt with an explicit
 * {@code step-N} identifier and description, in order, none merged, none dropped.
 *
 * <p>Pure static seam — no Spring context, no Mockito, no network (this repo's convention).
 */
class ChatServiceComposeAnswerContextTest {

    private final ExecutionOutcomeInterpreter interp = new ExecutionOutcomeInterpreter();

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @Test
    void everyRowBearingDatasetReachesTheContextWithExplicitBoundaries() {
        // The exact reported failing case: step-1 (5 open orders), step-3 (most-ordered item),
        // step-5 (product name/SKU).
        List<InvestigationDataset> datasets = List.of(
                new InvestigationDataset(1, "List all open purchase orders",
                        List.of(row("po_number", "PO-1"), row("po_number", "PO-2"),
                                row("po_number", "PO-3"), row("po_number", "PO-4"), row("po_number", "PO-5"))),
                new InvestigationDataset(3, "Determine the most ordered item",
                        List.of(row("product_id", "p1", "total_ordered_qty", 1500))),
                new InvestigationDataset(5, "Retrieve descriptive information for the identified item",
                        List.of(row("name", "Widget ABC", "sku", "SKU-123"))));

        String ctx = ChatService.buildInvestigationDatasetsBlock(datasets, interp);

        assertTrue(ctx.startsWith("INVESTIGATION DATASETS\n\n"), ctx);
        assertTrue(ctx.contains("Dataset: step-1"), ctx);
        assertTrue(ctx.contains("Description: List all open purchase orders"), ctx);
        assertTrue(ctx.contains("Dataset: step-3"), ctx);
        assertTrue(ctx.contains("Description: Determine the most ordered item"), ctx);
        assertTrue(ctx.contains("Dataset: step-5"), ctx);
        assertTrue(ctx.contains("Description: Retrieve descriptive information for the identified item"), ctx);

        // The critical regression this whole change exists to fix: the name/SKU values
        // themselves — not just the column names — must reach the composer.
        assertTrue(ctx.contains("name = Widget ABC"), ctx);
        assertTrue(ctx.contains("sku = SKU-123"), ctx);
        assertTrue(ctx.contains("product_id = p1"), ctx);
    }

    @Test
    void datasetOrderInTheContextMatchesStepOrderNeverReordered() {
        List<InvestigationDataset> datasets = List.of(
                new InvestigationDataset(1, "First", List.of(row("a", 1))),
                new InvestigationDataset(2, "Second", List.of(row("a", 2))),
                new InvestigationDataset(3, "Third", List.of(row("a", 3))));

        String ctx = ChatService.buildInvestigationDatasetsBlock(datasets, interp);

        int i1 = ctx.indexOf("Dataset: step-1");
        int i2 = ctx.indexOf("Dataset: step-2");
        int i3 = ctx.indexOf("Dataset: step-3");
        assertTrue(i1 < i2 && i2 < i3, ctx);
    }

    @Test
    void datasetsAreNeverFlattenedOrMergedEachBlockIsSeparatelyDelimited() {
        List<InvestigationDataset> datasets = List.of(
                new InvestigationDataset(1, "Orders", List.of(row("po_number", "PO-1"))),
                new InvestigationDataset(5, "Product", List.of(row("name", "Widget ABC"))));

        String ctx = ChatService.buildInvestigationDatasetsBlock(datasets, interp);

        // Each dataset gets its own "Dataset: step-N" / "Description:" header — proof they were
        // never concatenated into one undifferentiated table.
        assertEquals(2, ctx.split("Dataset: step-").length - 1);
    }

    @Test
    void emptyOrNullInvestigationDatasetsProduceEmptyContextNeverThrows() {
        assertEquals("", ChatService.buildInvestigationDatasetsBlock(List.of(), interp));
        assertEquals("", ChatService.buildInvestigationDatasetsBlock(null, interp));
    }
}
