package com.sei.nexus.agentmemory;

import com.sei.nexus.graph.GraphEdge;
import com.sei.nexus.graph.KnowledgeGraphRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin, read-only adapter over the existing authoritative relationship data
 * ({@code nexus_entity_relationship}), reused via the exact same repository method Chat's
 * {@code KnowledgeGraphService.buildGraphContext()} already calls —
 * {@link KnowledgeGraphRepository#findEdgesForNodes(List)}. No new SQL is written against
 * {@code nexus_entity_relationship}, no new relationship repository is created, and neither
 * {@code KnowledgeGraphRepository} nor {@code KnowledgeGraphService} is modified. One
 * authoritative relationship data-access implementation, two callers (Chat, and this
 * adapter) — not two independently maintained queries.
 *
 * <p>Not yet registered as an Agent tool (Phase 1). This is the future backing for
 * {@code explore_related(entity_key)}.
 *
 * <p>This adapter never ranks relationships, never filters based on the user's question,
 * never chooses "the" relevant relationship, and never infers or modifies relationship
 * data — it returns every relationship row touching the requested entity (as source or
 * target), unfiltered, exactly as {@code findEdgesForNodes} already does. The semantic
 * decision of which relationship matters belongs to the LLM in a later phase.
 */
@Service
public class RelationshipExplorationAdapter {

    private final KnowledgeGraphRepository knowledgeGraphRepository;

    public RelationshipExplorationAdapter(KnowledgeGraphRepository knowledgeGraphRepository) {
        this.knowledgeGraphRepository = knowledgeGraphRepository;
    }

    /**
     * Every relationship row where {@code entityKey} appears as source or target. Unfiltered,
     * unranked — delegates entirely to the existing {@link KnowledgeGraphRepository}.
     */
    public List<GraphEdge> exploreRelated(String entityKey) {
        if (entityKey == null || entityKey.isBlank()) return List.of();
        return knowledgeGraphRepository.findEdgesForNodes(List.of(entityKey));
    }
}
