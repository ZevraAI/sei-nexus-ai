package com.sei.nexus.agentmemory;

import com.sei.nexus.graph.GraphEdge;
import com.sei.nexus.graph.KnowledgeGraphRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Relationship exploration reuse (Phase 1) — hand-rolled fake over
 * {@link KnowledgeGraphRepository}, no database, no Mockito. Confirms the adapter
 * delegates to the exact existing {@code findEdgesForNodes} method (the same one Chat's
 * {@code KnowledgeGraphService} already calls) with correct single-entity scoping, and
 * performs no ranking/filtering/modification of its own.
 */
class RelationshipExplorationAdapterTest {

    static class FakeKnowledgeGraphRepository extends KnowledgeGraphRepository {
        record Call(List<String> entityKeys) {}
        final List<Call> calls = new ArrayList<>();
        List<GraphEdge> toReturn = List.of();

        FakeKnowledgeGraphRepository() { super(null); }

        @Override
        public List<GraphEdge> findEdgesForNodes(List<String> entityKeys) {
            calls.add(new Call(entityKeys));
            return toReturn;
        }
    }

    private FakeKnowledgeGraphRepository repository;
    private RelationshipExplorationAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = new FakeKnowledgeGraphRepository();
        adapter = new RelationshipExplorationAdapter(repository);
    }

    @Test
    void delegatesToTheExistingFindEdgesForNodesMethod() {
        GraphEdge edge = new GraphEdge("rel-1", "purchase-order", "supplier", "REFERENCES",
                "supplier_id", "id", "JOIN supplier ON purchase_order.supplier_id = supplier.id",
                "MANY_TO_ONE", false, "#333");
        repository.toReturn = List.of(edge);

        List<GraphEdge> result = adapter.exploreRelated("purchase-order");

        assertEquals(1, result.size());
        assertSame(edge, result.get(0), "the adapter returns the repository's rows unmodified");
    }

    @Test
    void scopesTheQueryToExactlyTheRequestedSingleEntity() {
        adapter.exploreRelated("supplier");

        assertEquals(1, repository.calls.size());
        assertEquals(List.of("supplier"), repository.calls.get(0).entityKeys(),
                "single-element list — the same call shape KnowledgeGraphService already uses");
    }

    @Test
    void returnsAllFieldsRequiredByExploreRelated() {
        GraphEdge edge = new GraphEdge("rel-1", "purchase-order", "supplier", "REFERENCES",
                "supplier_id", "id", "JOIN guidance text", "MANY_TO_ONE", true, "#abc");
        repository.toReturn = List.of(edge);

        GraphEdge result = adapter.exploreRelated("purchase-order").get(0);

        assertEquals("purchase-order", result.source());
        assertEquals("supplier", result.target());
        assertEquals("REFERENCES", result.relationshipType());
        assertEquals("supplier_id", result.sourceColumn());
        assertEquals("id", result.targetColumn());
        assertEquals("JOIN guidance text", result.joinGuidance());
        assertEquals("MANY_TO_ONE", result.cardinality());
    }

    @Test
    void emptyEntityKeyReturnsEmptyWithoutCallingTheRepository() {
        List<GraphEdge> result = adapter.exploreRelated("");

        assertTrue(result.isEmpty());
        assertTrue(repository.calls.isEmpty());
    }

    @Test
    void unknownEntityWithNoRelationshipsReturnsEmptyNotAnError() {
        repository.toReturn = List.of();

        List<GraphEdge> result = adapter.exploreRelated("isolated-entity");

        assertTrue(result.isEmpty());
    }
}
