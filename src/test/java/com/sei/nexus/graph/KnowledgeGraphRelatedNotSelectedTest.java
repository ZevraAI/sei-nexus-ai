package com.sei.nexus.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Related, not selected" section of {@link KnowledgeGraphService#buildGraphContext(List, Set)}.
 *
 * <p>Concept selection is an LLM retrieval step and can miss an object a question needs — most
 * often the object that OWNS an identifier the user quoted, when only the object holding the
 * details was selected. The graph builder keeps unselected entities out of the prompt EXCEPT the
 * declared ONE-HOP neighbours of selected objects, which are named (never described, no columns)
 * and marked not selected, so the planner can decide to request one. Fixtures are domain-neutral.
 */
class KnowledgeGraphRelatedNotSelectedTest {

    private static GraphNode node(String id, String label, String objectKey) {
        return new GraphNode(id, label, "ENTITY", null, "Group", "PLATFORM",
                "description of " + label, objectKey, "meaning of " + label, null, "ACTIVE");
    }

    private static GraphEdge edge(String source, String target, String join) {
        return new GraphEdge("rel-" + source + "-" + target, source, target, "REFERENCES",
                null, null, join, "N:1", false, null);
    }

    private static class FakeRepository extends KnowledgeGraphRepository {
        final List<GraphNode> nodes;
        final List<GraphEdge> edges;
        FakeRepository(List<GraphNode> nodes, List<GraphEdge> edges) { super(null); this.nodes = nodes; this.edges = edges; }
        @Override public List<GraphNode> findNodesByDomain(String domainKey) { return nodes; }
        @Override public List<GraphEdge> findEdgesForNodes(List<String> keys) {
            return edges.stream().filter(e -> keys.contains(e.source()) || keys.contains(e.target())).toList();
        }
    }

    private static final GraphNode DETAIL  = node("ent-detail", "Order Detail", "conn-x-order-details");
    private static final GraphNode HEADER  = node("ent-header", "Order Header", "conn-x-order-headers");
    private static final GraphNode UNRELATED = node("ent-other", "Unrelated Thing", "conn-x-unrelated");
    private static final GraphEdge DETAIL_TO_HEADER = edge("ent-detail", "ent-header",
            "JOIN order_headers ON order_headers.id = order_details.header_id");

    private static String ctx(List<GraphNode> nodes, List<GraphEdge> edges, Set<String> scope) {
        return new KnowledgeGraphService(new FakeRepository(nodes, edges))
                .buildGraphContext(List.of("PLATFORM"), scope);
    }

    @Test
    void aDeclaredOneHopNeighbourOfASelectedObjectIsNamedAndMarkedNotSelected() {
        String c = ctx(List.of(DETAIL, HEADER, UNRELATED), List.of(DETAIL_TO_HEADER),
                Set.of("conn-x-order-details"));

        assertTrue(c.contains("Related objects NOT selected for this question"));
        assertTrue(c.contains("Order Detail -[REFERENCES N:1]→ Order Header [not selected; table: "));
        assertTrue(c.contains("JOIN order_headers ON order_headers.id = order_details.header_id"),
                "the declared JOIN is relayed verbatim so the planner can use it");
        assertTrue(c.contains("request its metadata by its table name"));
    }

    @Test
    void theNeighbourIsNeverDescribedAndNoColumnsAreShown() {
        String c = ctx(List.of(DETAIL, HEADER), List.of(DETAIL_TO_HEADER), Set.of("conn-x-order-details"));

        assertFalse(c.contains("meaning of Order Header"), "no meaning/description for an unselected object");
        assertFalse(c.contains("description of Order Header"));
        assertFalse(c.contains("• Order Header [table:"), "an unselected object never gets its own entity entry");
    }

    @Test
    void anObjectWithNoRelationshipToASelectedOneStillCannotLeakIn() {
        String c = ctx(List.of(DETAIL, HEADER, UNRELATED), List.of(DETAIL_TO_HEADER),
                Set.of("conn-x-order-details"));

        assertFalse(c.contains("Unrelated Thing"), "only declared one-hop neighbours are named");
    }

    @Test
    void theNeighboursOwnNeighboursAreNotFollowed() {
        GraphNode far = node("ent-far", "Far Away", "conn-x-far");
        String c = ctx(List.of(DETAIL, HEADER, far),
                List.of(DETAIL_TO_HEADER, edge("ent-header", "ent-far", "JOIN far ON ...")),
                Set.of("conn-x-order-details"));

        assertFalse(c.contains("Far Away"), "strictly one hop — never a transitive expansion");
    }

    @Test
    void whenBothEndsAreSelectedThereIsNoRelatedSectionAndTheEdgeIsRenderedOnceAsBefore() {
        String c = ctx(List.of(DETAIL, HEADER), List.of(DETAIL_TO_HEADER),
                Set.of("conn-x-order-details", "conn-x-order-headers"));

        assertFalse(c.contains("Related objects NOT selected"));
        assertTrue(c.contains("Order Detail -[REFERENCES N:1]→ Order Header"));
        assertEquals(1, c.split("Order Detail -\\[REFERENCES", -1).length - 1);
    }

    @Test
    void anIncomingEdgeFromAnUnselectedObjectIsAlsoNamed() {
        // A selected object that is REFERENCED by an unselected one (e.g. a receipt pointing at a header).
        String c = ctx(List.of(DETAIL, HEADER), List.of(DETAIL_TO_HEADER), Set.of("conn-x-order-headers"));

        assertTrue(c.contains("Order Detail [not selected; table: "),
                "the marker sits on the unselected end — here the SOURCE of the edge");
        assertTrue(c.contains("]→ Order Header\n") || c.contains("]→ Order Header\r\n"),
                "the selected end carries no marker");
    }

    @Test
    void anUnscopedRequestIsByteIdenticalToTheLegacyDomainWideOutput() {
        KnowledgeGraphService s = new KnowledgeGraphService(
                new FakeRepository(List.of(DETAIL, HEADER), List.of(DETAIL_TO_HEADER)));

        assertEquals(s.buildGraphContext(List.of("PLATFORM")), s.buildGraphContext(List.of("PLATFORM"), null));
        assertFalse(s.buildGraphContext(List.of("PLATFORM")).contains("Related objects NOT selected"));
    }

    @Test
    void anEmptyScopeStillYieldsAnEmptyContext() {
        assertEquals("", ctx(List.of(DETAIL, HEADER), List.of(DETAIL_TO_HEADER), Set.of()));
    }

    @Test
    void aMissingNeighbourNodeIsSkippedWithoutThrowing() {
        // The edge points at an entity that is not in the domain's node list at all.
        String c = ctx(List.of(DETAIL), List.of(DETAIL_TO_HEADER), Set.of("conn-x-order-details"));

        assertTrue(c.contains("Order Detail"));
        assertFalse(c.contains("Related objects NOT selected"));
    }
}
