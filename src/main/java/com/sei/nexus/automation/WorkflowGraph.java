package com.sei.nexus.automation;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Deserialized form of the workflow graph JSONB.
 * Nodes and edges mirror the React Flow data model so the frontend
 * can round-trip the graph without transformation.
 */
public record WorkflowGraph(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {

    public record WorkflowNode(
            String id,
            String type,                    // TRIGGER|DB_QUERY|AI_REASON|IMAGE_ANALYSE|CONDITION|TRANSFORM|RESPONSE
            Map<String, Object> data,       // node label + config (all node-type-specific fields live here)
            Position position
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkflowEdge(
            String id,
            String source,
            String target,
            @JsonAlias("sourceHandle") String sourceHandle,   // nullable — "true" / "false" for CONDITION
            @JsonAlias("targetHandle") String targetHandle    // nullable
    ) {}

    public record Position(double x, double y) {}
}
