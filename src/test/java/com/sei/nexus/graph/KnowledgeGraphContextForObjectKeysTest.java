package com.sei.nexus.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Downstream Context Boundary for Concept-Scoped Metadata Narrowing — the second leak channel
 * surfaced by live verification of the "show me all open orders" fix (the first, {@code
 * SemanticService}'s entity block, is covered by {@code SemanticContextForObjectKeysTest}).
 * {@link KnowledgeGraphService#buildGraphContext(List, Set)} must render ONLY graph nodes bound
 * to the given {@code objectKeyScope} — an already Stage-2-resolved physical scope — never the
 * full domain-wide graph the single-arg overload still renders for every fallback caller.
 *
 * <p>Hand-rolled fake repository, no Mockito, no DB — this project's standing convention.
 */
class KnowledgeGraphContextForObjectKeysTest {

    private static GraphNode node(String id, String label, String objectKey, String groupLabel) {
        return new GraphNode(id, label, "ENTITY", null, groupLabel, "PLATFORM",
                "description of " + label, objectKey, null, null, "ACTIVE");
    }

    private static class FakeRepository extends KnowledgeGraphRepository {
        final List<GraphNode> nodes;
        FakeRepository(List<GraphNode> nodes) { super(null); this.nodes = nodes; }
        @Override public List<GraphNode> findNodesByDomain(String domainKey) { return nodes; }
        @Override public List<GraphEdge> findEdgesForNodes(List<String> entityKeys) { return List.of(); }
    }

    @Test
    void objectKeyScopeExcludesNodesOutsideTheResolvedScope() {
        GraphNode salesTx = node("ent-sales-tx", "Sales Transaction",
                "platform-conn-f5cbd930-sales-transactions", "Sales");
        GraphNode purchaseOrder = node("ent-po", "Purchase Order",
                "platform-conn-f5cbd930-purchase-orders", "Procurement");
        FakeRepository repo = new FakeRepository(List.of(salesTx, purchaseOrder));
        KnowledgeGraphService service = new KnowledgeGraphService(repo);

        String ctx = service.buildGraphContext(List.of("PLATFORM"),
                Set.of("platform-conn-f5cbd930-sales-transactions"));

        assertTrue(ctx.contains("Sales Transaction"));
        assertFalse(ctx.contains("Purchase Order"),
                "Purchase Order's object key was never in the resolved Stage-2 scope — it must not leak in");
    }

    @Test
    void nullScopeReproducesTheExactPreExistingDomainWideBehavior() {
        GraphNode salesTx = node("ent-sales-tx", "Sales Transaction",
                "platform-conn-f5cbd930-sales-transactions", "Sales");
        GraphNode purchaseOrder = node("ent-po", "Purchase Order",
                "platform-conn-f5cbd930-purchase-orders", "Procurement");
        FakeRepository repo = new FakeRepository(List.of(salesTx, purchaseOrder));
        KnowledgeGraphService service = new KnowledgeGraphService(repo);

        String scoped = service.buildGraphContext(List.of("PLATFORM"), null);
        String legacy = service.buildGraphContext(List.of("PLATFORM"));

        assertEquals(legacy, scoped, "a null scope must be byte-identical to the pre-existing single-arg overload");
        assertTrue(scoped.contains("Purchase Order"), "unscoped ⇒ full domain-wide graph, unchanged");
    }

    @Test
    void emptyScopeYieldsEmptyContextRatherThanTheFullDomainWideGraph() {
        GraphNode salesTx = node("ent-sales-tx", "Sales Transaction",
                "platform-conn-f5cbd930-sales-transactions", "Sales");
        FakeRepository repo = new FakeRepository(List.of(salesTx));
        KnowledgeGraphService service = new KnowledgeGraphService(repo);

        String ctx = service.buildGraphContext(List.of("PLATFORM"), Set.of());

        assertEquals("", ctx, "an empty resolved scope must never fall back to the full domain-wide graph");
    }
}
