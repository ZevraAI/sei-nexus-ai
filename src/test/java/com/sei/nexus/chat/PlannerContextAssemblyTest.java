package com.sei.nexus.chat;

import com.sei.nexus.semantic.SemanticService.EntityBinding;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-19 — business-driven planner context assembly.
 *
 * Reconstructs the documented failure fixture (a 10-table tenant whose entity
 * context is truncated at 1,500 chars while `stores` starts past char 2,500)
 * and verifies that the assembler now orders blocks by relevance BEFORE the
 * unchanged truncation budget is applied:
 *   Tier 1  Business Entity / Operational Vocabulary bindings (+ join neighbors)
 *   Tier 2  keyword-vs-block-text fallback
 *   Tier 3  renderer order (regression: exact pre-PRO-19 content)
 */
class PlannerContextAssemblyTest {

    private static final int BUDGET = 1500;
    private static final int NO_TRUNCATION = 100_000;

    // ── fixture: rendered blocks exactly as EnterpriseMapService emits them ──

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

    /** Renderer order is alphabetical by entity name — question-irrelevant tables first. */
    private static Map<String, String> retailBlocks() {
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("obj-fiscal-periods", block("fiscal_periods", "Fiscal Periods",
                "Accounting calendar periods used for financial reporting cycles",
                "id (bigint) [identifier]", "period_name (varchar)", "fiscal_year (integer)",
                "quarter (integer)", "start_date (date)", "end_date (date)",
                "is_closed (boolean) [status]", "closed_by (varchar)", "created_at (timestamp)"));
        blocks.put("obj-inventory", block("inventory", "Inventory",
                "Current on-hand quantities per product and location",
                "id (bigint) [identifier]", "product_id (bigint)", "warehouse_id (bigint)",
                "quantity_on_hand (integer)", "quantity_reserved (integer)",
                "reorder_point (integer)", "last_counted_at (timestamp)", "updated_at (timestamp)"));
        blocks.put("obj-invoices", block("invoices", "Invoices",
                "Payable invoices received for purchase orders",
                "id (bigint) [identifier]", "invoice_number (varchar) [identifier]",
                "supplier_id (bigint)", "amount_total (numeric)", "currency_code (varchar)",
                "issued_date (date)", "due_date (date)", "paid_date (date)",
                "payment_status (varchar) [status]"));
        blocks.put("obj-product-categories", block("product_categories", "Product Categories",
                "Merchandising hierarchy grouping products for reporting",
                "id (bigint) [identifier]", "category_name (varchar)", "parent_category_id (bigint)",
                "hierarchy_level (integer)", "is_active (boolean) [status]",
                "margin_target (numeric)", "created_at (timestamp)"));
        blocks.put("obj-products", block("products", "Products",
                "Sellable product catalog with pricing and lifecycle status",
                "id (bigint) [identifier]", "sku (varchar) [identifier]", "product_name (varchar)",
                "category_id (bigint)", "unit_price (numeric)", "unit_cost (numeric)",
                "lifecycle_status (varchar) [status]", "launched_at (date)",
                "discontinued_at (date)", "updated_at (timestamp)"));
        blocks.put("obj-regions", block("regions", "Regions",
                "Sales regions used for territory rollups",
                "id (bigint) [identifier]", "name (varchar)", "country_code (varchar)",
                "manager_email (varchar)", "created_at (timestamp)"));
        blocks.put("obj-store-targets", block("store_targets", "Store Targets",
                "Monthly revenue targets assigned to each retail location",
                "id (bigint) [identifier]", "target_month (date)", "revenue_target (numeric)",
                "footfall_target (integer)", "set_by (varchar)", "created_at (timestamp)"));
        blocks.put("obj-stores", block("stores", "Stores",
                "Physical retail locations and their operating status",
                "id (bigint) [identifier]", "name (varchar)", "address_line_1 (varchar)",
                "address_line_2 (varchar)", "city (varchar)", "state_province (varchar) [filterable]",
                "postal_code (varchar)", "region_id (bigint)",
                "status (store_status: open | temporarily_closed | seasonal | closed) [status]",
                "opened_at (date)", "square_footage (integer)"));
        blocks.put("obj-suppliers", block("suppliers", "Suppliers",
                "External companies that supply merchandise",
                "id (bigint) [identifier]", "company_name (varchar)", "contact_email (varchar)",
                "payment_terms (varchar)", "rating (integer)", "onboarded_at (date)",
                "approval_status (varchar) [status]"));
        blocks.put("obj-warehouses", block("warehouses", "Warehouses",
                "Distribution facilities holding inventory",
                "id (bigint) [identifier]", "facility_name (varchar)", "city (varchar)",
                "capacity_pallets (integer)", "is_automated (boolean)", "created_at (timestamp)"));
        return blocks;
    }

    /** Entity/vocabulary → table bindings as SemanticService now produces them. */
    private static List<EntityBinding> retailBindings() {
        return List.of(
                new EntityBinding("Store",    "obj-stores"),
                new EntityBinding("Supplier", "obj-suppliers"),
                new EntityBinding("Invoice",  "obj-invoices"),
                new EntityBinding("Region",   "obj-regions"),
                new EntityBinding("vendor",   "obj-suppliers"));   // vocabulary term
    }

    private static final String GRAPH_WITH_JOIN = """
            === KNOWLEDGE GRAPH ===
            [Group: Retail]
            • Store [table: stores]
              Meaning: A physical retail location
            • Store -[BELONGS_TO 1:N]→ Region
              JOIN: JOIN retail_core.regions ON regions.id = stores.region_id
            === END KNOWLEDGE GRAPH ===
            """;

    private static String rendered(Map<String, String> blocks) {
        return String.join("", blocks.values());
    }

    private static int posOf(String result, String table) {
        return result.indexOf("Table: retail_core." + table + " ");
    }

    // ── Scenario: "Show me Texas stores" ─────────────────────────────────────

    @Test
    void texasStores_storesBlockRanksFirst_regionsRidesAlongAsJoinNeighbor() {
        Map<String, String> blocks = retailBlocks();
        String result = ChatService.assembleEntityContext("Show me all the Texas stores",
                rendered(blocks), blocks, retailBindings(), GRAPH_WITH_JOIN, NO_TRUNCATION);

        int stores  = posOf(result, "stores");
        int regions = posOf(result, "regions");
        assertEquals(0, stores, "stores must be the first block (tier-1 entity binding)");
        assertTrue(regions > stores, "regions present after stores");
        // regions has no keyword match — it must arrive as the JOIN neighbor,
        // directly after tier 1, ahead of every non-matching block.
        assertTrue(regions < posOf(result, "fiscal_periods"),
                "join-neighbor regions must precede unrelated tables");
    }

    @Test
    void texasStores_storesSurvivesTheUnchangedBudget() {
        Map<String, String> blocks = retailBlocks();
        String full = rendered(blocks);
        // Reproduce the documented defect first: in renderer order, stores starts
        // beyond the 1,500-char cutoff and the planner never sees its columns.
        assertTrue(full.indexOf("Table: retail_core.stores ") > BUDGET,
                "fixture must reproduce the truncation defect");

        String result = ChatService.assembleEntityContext("Show me all the Texas stores",
                full, blocks, retailBindings(), GRAPH_WITH_JOIN, BUDGET);

        int col = result.indexOf("state_province");
        assertTrue(col >= 0 && col < BUDGET,
                "the Texas filter column must now be inside the planner's visible window");
        assertTrue(result.indexOf("region_id") < BUDGET, "join column visible");
        assertTrue(result.length() <= BUDGET + 200, "budget preserved (marker is capped)");
    }

    // ── Scenario: "Show suppliers" ───────────────────────────────────────────

    @Test
    void showSuppliers_entityBindingWins() {
        Map<String, String> blocks = retailBlocks();
        String result = ChatService.assembleEntityContext("Show suppliers",
                rendered(blocks), blocks, retailBindings(), "", NO_TRUNCATION);
        assertEquals(0, posOf(result, "suppliers"), "suppliers block first");
    }

    // ── Scenario: "Show me vendors" — synonym resolvable ONLY via vocabulary ─

    @Test
    void showVendors_vocabularyResolvesToSuppliersTable() {
        Map<String, String> blocks = retailBlocks();
        // Sanity: no block text contains "vendor" — keyword matching alone cannot solve this.
        assertFalse(rendered(blocks).toLowerCase().contains("vendor"));

        String result = ChatService.assembleEntityContext("Show me vendors",
                rendered(blocks), blocks, retailBindings(), "", NO_TRUNCATION);
        assertEquals(0, posOf(result, "suppliers"),
                "vocabulary term 'vendor' must resolve to the suppliers table (tier 1)");
    }

    @Test
    void showVendors_withoutVocabularyFallsBackToRendererOrder() {
        Map<String, String> blocks = retailBlocks();
        String result = ChatService.assembleEntityContext("Show me vendors",
                rendered(blocks), blocks, List.of(), "", NO_TRUNCATION);
        assertEquals(rendered(blocks), result,
                "no binding + no keyword match → today's renderer order, untouched");
    }

    // ── Scenario: "Show invoices" ────────────────────────────────────────────

    @Test
    void showInvoices_invoicesBlockRanksFirst() {
        Map<String, String> blocks = retailBlocks();
        String result = ChatService.assembleEntityContext("Show invoices",
                rendered(blocks), blocks, retailBindings(), "", NO_TRUNCATION);
        assertEquals(0, posOf(result, "invoices"), "invoices block first");
    }

    // ── Scenario: unknown terminology / no matching entities ────────────────

    @Test
    void unknownTerminology_fallsBackToRendererOrderExactly() {
        Map<String, String> blocks = retailBlocks();
        String result = ChatService.assembleEntityContext("show me flibbertigibbet zorp",
                rendered(blocks), blocks, retailBindings(), GRAPH_WITH_JOIN, NO_TRUNCATION);
        assertEquals(rendered(blocks), result,
                "unknown terms must not reorder anything (tier-3 safety net)");
    }

    @Test
    void staleBindingToMissingBlockIsIgnored() {
        Map<String, String> blocks = retailBlocks();
        List<EntityBinding> stale = List.of(new EntityBinding("Ghost", "obj-does-not-exist"));
        String result = ChatService.assembleEntityContext("show ghost",
                rendered(blocks), blocks, stale, "", NO_TRUNCATION);
        assertEquals(rendered(blocks), result,
                "a binding pointing at a non-rendered object must fall through safely");
    }

    @Test
    void blankQuestionKeepsRendererOrder() {
        Map<String, String> blocks = retailBlocks();
        assertEquals(new java.util.ArrayList<>(blocks.values()),
                ChatService.rankEntityBlocks("", blocks, retailBindings(), ""));
    }

    // ── Tier 2: keyword fallback without any binding ─────────────────────────

    @Test
    void keywordFallback_ranksWarehousesWithoutAnyBinding() {
        Map<String, String> blocks = retailBlocks();
        String result = ChatService.assembleEntityContext("show warehouses",
                rendered(blocks), blocks, List.of(), "", NO_TRUNCATION);
        assertEquals(0, posOf(result, "warehouses"),
                "keyword-vs-block-text remains available as the fallback tier");
    }

    // ── Regression: legacy paths and truncation semantics ───────────────────

    @Test
    void missingBlocksMapDegradesToLegacyBehaviour() {
        Map<String, String> blocks = retailBlocks();
        String full = rendered(blocks);
        String result = ChatService.assembleEntityContext("Show me Texas stores",
                full, Map.of(), retailBindings(), "", BUDGET);
        assertTrue(result.startsWith(full.substring(0, BUDGET)),
                "without blocks the assembler must truncate the rendered string as before");
    }

    @Test
    void noTruncationBelowBudget_contentUntouched() {
        String small = "Table: retail_core.stores (Stores)\nPurpose: p\n\n";
        assertEquals(small, ChatService.truncateEntityContext(small, BUDGET));
    }

    @Test
    void truncationMarkerNamesOmittedTablesAndDropsDescribeSchema() {
        Map<String, String> blocks = retailBlocks();
        String result = ChatService.assembleEntityContext("Show me all the Texas stores",
                rendered(blocks), blocks, retailBindings(), GRAPH_WITH_JOIN, BUDGET);

        assertTrue(result.contains("[schema truncated — omitted tables: "),
                "marker must tell the planner what exists beyond the budget");
        assertFalse(result.contains("describe_schema"),
                "the reasoning planner has no tools — the old marker text was misleading");
        assertTrue(result.contains("fiscal_periods"),
                "the demoted, fully-cut tables are named in the marker");
    }

    @Test
    void partiallyVisibleTableIsNotListedAsOmitted() {
        // Two blocks; cut lands inside the second → only fully-cut tables are "omitted".
        String a = "Table: s.alpha (A)\nPurpose: xxxxxxxxxxxxxxxxxxxx\n\n";
        String b = "Table: s.beta (B)\nPurpose: yyyy\n\n";
        String cut = ChatService.truncateEntityContext(a + b, a.length() + 5);
        assertTrue(cut.contains("[schema truncated]"),
                "no fully-omitted table → bare marker, got: " + cut);
    }

    // ── Business-terms attachment (PRO-24, structural accompaniment) ─────────

    private static final Map<String, List<String>> STORES_TERMS = Map.of(
            "obj-stores", List.of("\"TX\" = state_province = 'Texas'"));

    @Test
    void businessTermsAttachDirectlyUnderTheirBlock() {
        Map<String, String> blocks = retailBlocks();
        String result = ChatService.assembleEntityContext("Show me all the Texas stores",
                rendered(blocks), blocks, retailBindings(), GRAPH_WITH_JOIN,
                STORES_TERMS, NO_TRUNCATION);

        assertTrue(result.contains(
                        "  - square_footage (integer)\nBusiness terms: \"TX\" = state_province = 'Texas'\n"),
                "terms line sits immediately under its block's last column, got:\n"
                        + result.substring(0, Math.min(900, result.length())));
        int termsAt = result.indexOf("Business terms:");
        assertTrue(termsAt < posOf(result, "regions"),
                "companion attaches before the next block begins");
    }

    @Test
    void zeroTermsIsByteIdenticalToPreExistingBehavior() {
        Map<String, String> blocks = retailBlocks();
        String withEmptyTerms = ChatService.assembleEntityContext("Show me all the Texas stores",
                rendered(blocks), blocks, retailBindings(), GRAPH_WITH_JOIN, Map.of(), BUDGET);
        String legacy = ChatService.assembleEntityContext("Show me all the Texas stores",
                rendered(blocks), blocks, retailBindings(), GRAPH_WITH_JOIN, BUDGET);
        assertEquals(legacy, withEmptyTerms,
                "the zero-cost guarantee: no terms → byte-identical output");
    }

    @Test
    void fallbackKeepsRendererOrderWithCompanionsAttached() {
        Map<String, String> blocks = retailBlocks();
        String result = ChatService.assembleEntityContext("show me flibbertigibbet zorp",
                rendered(blocks), blocks, retailBindings(), GRAPH_WITH_JOIN,
                STORES_TERMS, NO_TRUNCATION);

        // Renderer order preserved (tier-3), companions ride along structurally
        assertEquals(0, posOf(result, "fiscal_periods"), "fallback keeps renderer order");
        assertTrue(posOf(result, "stores") < posOf(result, "suppliers"));
        assertTrue(result.contains("Business terms: \"TX\" = state_province = 'Texas'"),
                "companions are structural, not relevance-gated — they attach in every path");
    }

    @Test
    void budgetStillHoldsWithTermsAttached() {
        Map<String, String> blocks = retailBlocks();
        String result = ChatService.assembleEntityContext("Show me all the Texas stores",
                rendered(blocks), blocks, retailBindings(), GRAPH_WITH_JOIN,
                STORES_TERMS, BUDGET);
        assertTrue(result.length() <= BUDGET + 200, "budget + capped marker, unchanged");
        assertTrue(result.indexOf("Business terms:") < BUDGET,
                "terms of the top-ranked block land inside the visible window");
    }

    @Test
    void allBlocksStillPresentWhenBudgetIsLargeEnough() {
        Map<String, String> blocks = retailBlocks();
        String result = ChatService.assembleEntityContext("Show me Texas stores",
                rendered(blocks), blocks, retailBindings(), GRAPH_WITH_JOIN, NO_TRUNCATION);
        for (String key : blocks.keySet()) {
            String table = key.substring("obj-".length()).replace('-', '_');
            assertTrue(result.contains("Table: retail_core." + table + " "),
                    "reordering must never drop a table below the budget: " + table);
        }
        assertEquals(rendered(blocks).length(), result.length(),
                "reordering is a permutation — total content unchanged");
    }
}
