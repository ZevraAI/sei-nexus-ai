package com.sei.nexus.chat;

import com.sei.nexus.semantic.SemanticService.EntityBinding;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-31 — retrieval expansion into the PRO-19 planner-context assembly.
 *
 * Two contracts from the approved architecture (PRO-30):
 * <ul>
 *   <li><b>Zero-cost fallback (§3.4, executable form):</b> with no resolutions
 *       (empty token set) the assembled context is byte-identical to the
 *       pre-BLR overloads.</li>
 *   <li><b>TX-class blindness closed (§0):</b> a resolved token that never
 *       became a question keyword (two-character "TX") still selects the right
 *       table once its canonical expansion joins the keyword set.</li>
 * </ul>
 */
class RetrievalExpansionTest {

    private static String block(String table, String business, String purpose, String... columns) {
        StringBuilder b = new StringBuilder();
        b.append("Table: retail_core.").append(table).append(" (").append(business).append(")\n");
        b.append("Purpose: ").append(purpose).append("\n");
        b.append("connection_key=conn-1 (use this exact value in your SQL plan)\n");
        b.append("Columns:\n");
        for (String c : columns) b.append("  - ").append(c).append("\n");
        b.append("\n");
        return b.toString();
    }

    /** Renderer order puts the question-relevant table last. */
    private static Map<String, String> blocks() {
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("obj-fiscal-periods", block("fiscal_periods", "Fiscal Periods",
                "Accounting calendar periods",
                "id (bigint) [identifier]", "period_name (varchar)", "fiscal_year (integer)"));
        blocks.put("obj-warehouses", block("warehouses", "Warehouses",
                "Distribution facilities",
                "id (bigint) [identifier]", "facility_name (varchar)", "city (varchar)"));
        blocks.put("obj-stores", block("stores", "Stores",
                "Physical retail sites",
                "id (bigint) [identifier]", "name (varchar)",
                "state_province (varchar; observed: California | Texas) [filterable]"));
        return blocks;
    }

    private static String rendered(Map<String, String> blocks) {
        return String.join("", blocks.values());
    }

    // "TX" is two characters: QuestionKeywords drops it, and no block text
    // contains "tx" — the documented blindness. Keywords alone see nothing.
    private static final String TX_QUESTION = "TX only";

    @Test
    void emptyExpansionIsByteIdenticalToPreBlrAssembly() {
        Map<String, String> blocks = blocks();
        String preBlr = ChatService.assembleEntityContext(TX_QUESTION, rendered(blocks),
                blocks, List.of(), "", Map.of(), 100_000);
        String withEmptyTokens = ChatService.assembleEntityContext(TX_QUESTION, rendered(blocks),
                blocks, List.of(), "", Map.of(), Set.of(), 100_000);

        assertEquals(preBlr, withEmptyTokens);
    }

    @Test
    void withoutResolutionTwoCharTokenLeavesRendererOrder() {
        List<String> order = ChatService.rankEntityBlockKeys(TX_QUESTION, blocks(),
                List.of(new EntityBinding("Store", "obj-stores")), "");

        // "tx" never became a keyword; "only" matches nothing → tier-3 renderer order
        assertEquals(List.of("obj-fiscal-periods", "obj-warehouses", "obj-stores"), order);
    }

    @Test
    void resolvedCanonicalTokensPromoteTheBoundTable() {
        // Expansion tokens implied by "TX" → state_province = 'Texas' (resolver output)
        Set<String> expanded = Set.of("state_province", "texas", "stores");

        List<String> order = ChatService.rankEntityBlockKeys(TX_QUESTION, blocks(),
                List.of(new EntityBinding("Store", "obj-stores")), "", expanded);

        assertEquals("obj-stores", order.get(0));
    }
}
