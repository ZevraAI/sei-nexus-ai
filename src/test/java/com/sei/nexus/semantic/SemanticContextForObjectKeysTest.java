package com.sei.nexus.semantic;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Downstream Context Boundary for Concept-Scoped Metadata Narrowing — regression coverage for
 * the "show me all open orders" leak: {@link SemanticService#semanticContextForObjectKeys}
 * must render ONLY the entities bound to the object keys handed to it (an already
 * Stage-2-resolved scope), never a broader, domain-wide set — unlike {@link
 * SemanticService#semanticContextWithBindings}, which is untouched and still domain-scoped for
 * every caller that does not have a concept-scoped resolution to reuse.
 *
 * <p>Hand-rolled fake repository, no Mockito, no DB — this project's standing convention (see
 * {@code BusinessLanguageResolverTest.FakeSemanticRepository} for the identical pattern).
 */
class SemanticContextForObjectKeysTest {

    private static BusinessEntity entity(String key, String name, String objectKey, String description) {
        Instant now = Instant.now();
        return new BusinessEntity(key, "PLATFORM", name, description, objectKey,
                null, null, "ACTIVE", "steward@tenant.com", now, now);
    }

    private static class FakeRepository extends SemanticRepository {
        private final Map<String, List<BusinessEntity>> byObjectKeySet;
        FakeRepository(Map<String, List<BusinessEntity>> byObjectKeySet) {
            super(null);
            this.byObjectKeySet = byObjectKeySet;
        }
        @Override public List<BusinessEntity> findEntitiesByObjectKeys(List<String> objectKeys) {
            if (objectKeys == null || objectKeys.isEmpty()) return List.of();
            // Mirrors the real SQL's IN(...) semantics: union of every entity bound to any of
            // the requested keys — never a key this test didn't ask for.
            return objectKeys.stream()
                    .flatMap(k -> byObjectKeySet.getOrDefault(k, List.of()).stream())
                    .distinct()
                    .toList();
        }
    }

    // ── Test 1 / 8: the exact regression scenario — Stage 1 selected sales-transaction only ──

    @Test
    void salesTransactionScopeContainsSalesTransactionNotPurchaseOrder() {
        BusinessEntity salesTx = entity("ent-sales-tx", "Sales Transaction",
                "platform-conn-f5cbd930-sales-transactions",
                "A completed or in-progress CUSTOMER-FACING commercial sale.");
        BusinessEntity purchaseOrder = entity("ent-po", "Purchase Order",
                "platform-conn-f5cbd930-purchase-orders",
                "A procurement commitment from the retailer to a supplier.");
        FakeRepository repo = new FakeRepository(Map.of(
                "platform-conn-f5cbd930-sales-transactions", List.of(salesTx),
                "platform-conn-f5cbd930-purchase-orders", List.of(purchaseOrder)));
        SemanticService service = new SemanticService(null, null, repo);

        SemanticService.SemanticContext ctx = service.semanticContextForObjectKeys(
                List.of("platform-conn-f5cbd930-sales-transactions"));

        assertTrue(ctx.contextText().contains("Sales Transaction"));
        assertFalse(ctx.contextText().contains("Purchase Order"),
                "the object key for Purchase Order was never in scope — it must not leak into the context text");
        assertFalse(ctx.contextText().contains("purchase-order") || ctx.contextText().contains("purchase_orders"));
    }

    // ── Test 2 / 9: the inverse scenario — Stage 1 selected purchase-order only ──────────────

    @Test
    void purchaseOrderScopeContainsPurchaseOrderNotSalesTransaction() {
        BusinessEntity salesTx = entity("ent-sales-tx", "Sales Transaction",
                "platform-conn-f5cbd930-sales-transactions", "A customer-facing sale.");
        BusinessEntity purchaseOrder = entity("ent-po", "Purchase Order",
                "platform-conn-f5cbd930-purchase-orders", "A procurement commitment to a supplier.");
        FakeRepository repo = new FakeRepository(Map.of(
                "platform-conn-f5cbd930-sales-transactions", List.of(salesTx),
                "platform-conn-f5cbd930-purchase-orders", List.of(purchaseOrder)));
        SemanticService service = new SemanticService(null, null, repo);

        SemanticService.SemanticContext ctx = service.semanticContextForObjectKeys(
                List.of("platform-conn-f5cbd930-purchase-orders"));

        assertTrue(ctx.contextText().contains("Purchase Order"));
        assertFalse(ctx.contextText().contains("Sales Transaction"),
                "the object key for Sales Transaction was never in scope — it must not leak into the context text");
    }

    // ── Test 3: a concept bound to multiple physical objects — ALL are retrieved ─────────────

    @Test
    void multipleObjectKeysRetrieveAllBoundEntitiesNotJustOne() {
        BusinessEntity txUs = entity("ent-tx-us", "Sales Transaction (US)",
                "platform-conn-1-sales-us", "US POS sales.");
        BusinessEntity txEu = entity("ent-tx-eu", "Sales Transaction (EU)",
                "platform-conn-1-sales-eu", "EU POS sales.");
        FakeRepository repo = new FakeRepository(Map.of(
                "platform-conn-1-sales-us", List.of(txUs),
                "platform-conn-1-sales-eu", List.of(txEu)));
        SemanticService service = new SemanticService(null, null, repo);

        SemanticService.SemanticContext ctx = service.semanticContextForObjectKeys(
                List.of("platform-conn-1-sales-us", "platform-conn-1-sales-eu"));

        assertTrue(ctx.contextText().contains("Sales Transaction (US)"));
        assertTrue(ctx.contextText().contains("Sales Transaction (EU)"));
    }

    // ── Test 5: no scope in ⇒ no domain-wide fallback out ────────────────────────────────────

    @Test
    void emptyObjectKeysYieldsEmptyContextNeverABroaderRetrieval() {
        FakeRepository repo = new FakeRepository(Map.of());
        SemanticService service = new SemanticService(null, null, repo);

        assertEquals(SemanticService.SemanticContext.EMPTY, service.semanticContextForObjectKeys(List.of()));
        assertEquals(SemanticService.SemanticContext.EMPTY, service.semanticContextForObjectKeys(null));
    }

    // ── Test 11: an object key with a legitimately empty binding set leaks nothing ───────────

    @Test
    void unboundObjectKeyContributesNothing() {
        FakeRepository repo = new FakeRepository(Map.of());
        SemanticService service = new SemanticService(null, null, repo);

        SemanticService.SemanticContext ctx = service.semanticContextForObjectKeys(
                List.of("platform-conn-1-nonexistent"));

        assertEquals("", ctx.contextText());
    }
}
