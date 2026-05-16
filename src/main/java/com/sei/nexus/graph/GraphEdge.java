package com.sei.nexus.graph;

/**
 * A directed edge in the SEI Nexus knowledge graph, backed by nexus_entity_relationship.
 */
public record GraphEdge(
        String id,
        String source,
        String target,
        String relationshipType,
        String sourceColumn,
        String targetColumn,
        String joinGuidance,
        String cardinality,
        boolean bidirectional,
        String edgeColor
) {}
