package com.sei.nexus.graph;

import java.util.List;

/**
 * Full graph payload returned to the frontend and used for AI context.
 */
public record GraphData(
        List<GraphNode> nodes,
        List<GraphEdge> edges
) {}
