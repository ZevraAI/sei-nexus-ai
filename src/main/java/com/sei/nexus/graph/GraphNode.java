package com.sei.nexus.graph;

/**
 * A node in the SEI Nexus knowledge graph, backed by nexus_business_entity.
 */
public record GraphNode(
        String id,
        String label,
        String nodeType,
        String color,
        String groupLabel,
        String domainKey,
        String description,
        String primaryObjectKey,
        String operationalMeaning,
        String investigationHints,
        String status
) {}
