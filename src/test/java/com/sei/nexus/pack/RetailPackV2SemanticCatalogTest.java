package com.sei.nexus.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Retail Industry Pack V2 semantic taxonomy — content-level assertions against the actual
 * shipped {@code retail-v1.json} classpath resource (not a synthetic fixture), since these are
 * facts about the Pack's own authored content, not about {@link com.sei.nexus.prompt.
 * BusinessObjectBatchAnalyzer}'s mechanism (that mechanism is covered separately by {@code
 * BusinessObjectBatchAnalyzerConceptResolutionTest}, using hand-rolled fixtures).
 *
 * <p>Covers the review findings from the Retail Pack V2 taxonomy review: the Goods Receipt /
 * Shipment semantic-overlap correction, the Promotion/Shopping Cart alias corrections, the new
 * Inventory Lot / Batch concept, and the requirement that every canonical concept now carries an
 * explicit, pack-authored {@code concept_key} rather than relying on the runtime slugify
 * fallback.
 */
class RetailPackV2SemanticCatalogTest {

    private static IndustryPack pack;

    @BeforeAll
    static void loadRealPack() throws Exception {
        // Mirrors the real, Spring-managed ObjectMapper bean's naming strategy (WebConfig) — the
        // pack JSON files use snake_case keys (concept_key, table_patterns, ...) which only bind
        // onto PackEntity's camelCase fields with this configured; a bare `new ObjectMapper()`
        // silently leaves every snake_case field null instead of failing loudly.
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try (InputStream is = RetailPackV2SemanticCatalogTest.class
                .getResourceAsStream("/industry-packs/retail-v1.json")) {
            assertNotNull(is, "retail-v1.json must exist on the classpath");
            pack = mapper.readValue(is, IndustryPack.class);
        }
    }

    private PackEntity entity(String name) {
        return pack.entities().stream()
                .filter(e -> name.equalsIgnoreCase(e.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Concept not found in retail-v1.json: " + name));
    }

    // ── Item 7 — Inventory Lot / Batch is present ────────────────────────────────

    @Test
    void inventoryLotBatchConceptIsPresent() {
        PackEntity lotBatch = entity("Inventory Lot / Batch");
        assertEquals("inventory-lot-batch", lotBatch.conceptKey());
        assertTrue(lotBatch.description().toLowerCase().contains("lot")
                || lotBatch.description().toLowerCase().contains("batch"));
        // Distinguished from Product, Inventory Balance, Inventory Movement, Inventory Adjustment
        // per the task's explicit boundary requirement.
        String desc = lotBatch.description();
        assertTrue(desc.contains("Inventory Balance"), "must distinguish itself from Inventory Balance");
        assertTrue(desc.contains("Inventory Movement"), "must distinguish itself from Inventory Movement");
        assertTrue(desc.contains("Inventory Adjustment"), "must distinguish itself from Inventory Adjustment");
        assertTrue(desc.contains("Product"), "must distinguish itself from Product");
    }

    // ── Items 2/3/4 — Goods Receipt vs Shipment correction ───────────────────────

    @Test
    void goodsReceiptSemanticContextDescribesArrivedGoodsOnly() {
        PackEntity goodsReceipt = entity("Goods Receipt");
        String desc = goodsReceipt.description().toUpperCase();
        assertTrue(desc.contains("ARRIVED") || desc.contains("ARRIVAL"),
                "Goods Receipt's description must state its defining property is that goods have ARRIVED");
        // The pre-arrival ASN/advance-ship-notice terminology must no longer live here.
        String aliasesLower = String.join(",", goodsReceipt.aliases()).toLowerCase();
        assertFalse(aliasesLower.contains("asn"), "ASN must not be an alias of Goods Receipt");
        assertFalse(aliasesLower.contains("advance ship notice"), "ASN terminology must not alias Goods Receipt");
        assertFalse(aliasesLower.contains("inbound delivery"), "Inbound Delivery must not alias Goods Receipt");
        String patternsLower = String.join(",", goodsReceipt.tablePatterns()).toLowerCase();
        assertFalse(patternsLower.contains("asn"), "ASN must not be a table_pattern of Goods Receipt");
        assertFalse(patternsLower.contains("inbound_delivery"));
    }

    @Test
    void shipmentSemanticContextDescribesInTransitGoods() {
        PackEntity shipment = entity("Shipment");
        String desc = shipment.description().toUpperCase();
        assertTrue(desc.contains("TRANSIT") || desc.contains("IN MOTION") || desc.contains("NOT YET ARRIVED")
                        || desc.contains("HAVE NOT YET"),
                "Shipment's description must state its defining property is TRANSIT / not-yet-arrived");
        // ASN/advance-ship-notice/inbound-delivery terminology now correctly lives here instead.
        String aliasesLower = String.join(",", shipment.aliases()).toLowerCase();
        assertTrue(aliasesLower.contains("asn"), "ASN must be an alias of Shipment");
        assertTrue(aliasesLower.contains("advance ship notice") || aliasesLower.contains("inbound delivery"),
                "ASN/advance-ship-notice terminology must alias Shipment");
    }

    @Test
    void goodsReceiptAndShipmentDescriptionsExplicitlyCrossReferenceEachOther() {
        // The review's core requirement: descriptions must contrast the two concepts so the LLM
        // can distinguish them without relying on physical table names.
        PackEntity goodsReceipt = entity("Goods Receipt");
        PackEntity shipment = entity("Shipment");
        assertTrue(goodsReceipt.description().contains("Shipment"),
                "Goods Receipt's description must explicitly reference Shipment as the adjacent concept");
        assertTrue(shipment.description().contains("Goods Receipt"),
                "Shipment's description must explicitly reference Goods Receipt as the adjacent concept");
    }

    // ── Item 5 — Promotion alias correction ──────────────────────────────────────

    @Test
    void promotionNoLongerHasOverlyBroadDiscountAliases() {
        PackEntity promotion = entity("Promotion");
        Set<String> aliases = new HashSet<>(promotion.aliases());
        assertFalse(aliases.contains("discount"), "bare 'discount' must no longer alias Promotion");
        assertFalse(aliases.contains("discount code"), "bare 'discount code' must no longer alias Promotion");
        Set<String> patterns = new HashSet<>(promotion.tablePatterns());
        assertFalse(patterns.contains("discount"));
        assertFalse(patterns.contains("discounts"));
    }

    // ── Item 6 — Shopping Cart alias correction ──────────────────────────────────

    @Test
    void shoppingCartNoLongerHasWishListAlias() {
        PackEntity cart = entity("Shopping Cart");
        Set<String> aliases = new HashSet<>(cart.aliases());
        assertFalse(aliases.contains("wish list"), "wish list must no longer alias Shopping Cart");
        Set<String> patterns = new HashSet<>(cart.tablePatterns());
        assertFalse(patterns.contains("wish_list"));
    }

    // ── Item 8 — every canonical concept exposes a stable, explicit concept_key ──

    @Test
    void everyCanonicalConceptHasAnExplicitStableConceptKey() {
        assertEquals(54, pack.entities().size(), "Retail Pack V2 must define exactly 54 canonical concepts");
        Set<String> seen = new HashSet<>();
        for (PackEntity e : pack.entities()) {
            assertNotNull(e.conceptKey(), "concept_key must be explicitly set (not left for slugify fallback) for: " + e.name());
            assertFalse(e.conceptKey().isBlank(), "concept_key must not be blank for: " + e.name());
            assertTrue(seen.add(e.conceptKey()), "concept_key must be unique — duplicate: " + e.conceptKey());
        }
    }

    @Test
    void inventoryLotBatchHasItsExpectedStableConceptKey() {
        assertEquals("inventory-lot-batch", entity("Inventory Lot / Batch").conceptKey());
    }

    @Test
    void keyBoundaryConceptsHaveTheirExpectedStableConceptKeys() {
        assertEquals("purchase-order", entity("Purchase Order").conceptKey());
        assertEquals("sales-transaction", entity("Sales Transaction").conceptKey());
        assertEquals("goods-receipt", entity("Goods Receipt").conceptKey());
        assertEquals("shipment", entity("Shipment").conceptKey());
        assertEquals("warehouse", entity("Warehouse").conceptKey());
        assertEquals("store", entity("Store").conceptKey());
    }

    // ── No tenant-specific content leaked into the canonical Pack ────────────────

    @Test
    void packContainsNoTenantSpecificTerminology() {
        String wholePack = pack.toString().toLowerCase();
        assertFalse(wholePack.contains("meridian"), "Pack must not reference the validation tenant's fictional company name");
        assertFalse(wholePack.contains("retailcore"), "Pack must not reference the validation tenant's application name");
    }
}
