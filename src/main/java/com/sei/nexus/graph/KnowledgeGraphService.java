package com.sei.nexus.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Business logic for the knowledge graph: assembles GraphData, builds
 * AI-optimised context strings, and performs path resolution.
 */
@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    private final KnowledgeGraphRepository repository;

    public KnowledgeGraphService(KnowledgeGraphRepository repository) {
        this.repository = repository;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Full graph for a domain (all nodes + edges). */
    public GraphData getGraphByDomain(String domainKey) {
        List<GraphNode> nodes = repository.findNodesByDomain(domainKey);
        List<GraphEdge> edges = repository.findEdgesByDomain(domainKey);
        return new GraphData(nodes, edges);
    }

    /** Full graph across all domains. */
    public GraphData getFullGraph() {
        return new GraphData(repository.findAllNodes(), repository.findAllEdges());
    }

    /** Subgraph centred on {@code entityKey} up to {@code depth} hops. */
    public GraphData getNeighborGraph(String entityKey, int depth) {
        List<GraphNode> nodes = repository.findNeighbors(entityKey, depth);
        List<String> keys = nodes.stream().map(GraphNode::id).collect(Collectors.toList());
        List<GraphEdge> edges = repository.findEdgesForNodes(keys);
        return new GraphData(nodes, edges);
    }

    /**
     * Shortest-path subgraph between two entities.
     * Returns nodes and edges along the path only.
     */
    public GraphData getShortestPath(String fromKey, String toKey) {
        List<String> pathKeys = repository.findShortestPath(fromKey, toKey);
        if (pathKeys.isEmpty()) return new GraphData(List.of(), List.of());
        // Parse the Postgres array literal "[{a,b,c}]"
        String raw = pathKeys.get(0).replaceAll("[{}]", "");
        List<String> keys = List.of(raw.split(","));
        List<GraphNode> nodes = repository.findAllNodes().stream()
                .filter(n -> keys.contains(n.id()))
                .collect(Collectors.toList());
        List<GraphEdge> edges = repository.findEdgesForNodes(keys);
        return new GraphData(nodes, edges);
    }

    /**
     * Builds a compact, AI-optimised context string describing the graph.
     *
     * <p>This string is injected into every LLM prompt so the model understands
     * which entities exist, how they relate, and exactly which SQL JOINs to use.
     * Format is intentionally terse to minimise token cost.
     */
    public String buildGraphContext(List<String> domainKeys) {
        if (domainKeys == null || domainKeys.isEmpty()) return "";

        try {
            // Collect all nodes/edges for the given domains
            List<GraphNode> allNodes = domainKeys.stream()
                    .flatMap(dk -> repository.findNodesByDomain(dk).stream())
                    .distinct()
                    .collect(Collectors.toList());

            List<GraphEdge> allEdges = repository.findEdgesForNodes(
                    allNodes.stream().map(GraphNode::id).collect(Collectors.toList()));

            if (allNodes.isEmpty()) return "";

            // Index nodes by id for quick lookup
            Map<String, GraphNode> nodeIndex = allNodes.stream()
                    .collect(Collectors.toMap(GraphNode::id, n -> n));

            StringBuilder sb = new StringBuilder();
            sb.append("=== KNOWLEDGE GRAPH ===\n");
            sb.append("Business entities available for investigation.\n");
            sb.append("IMPORTANT: these are entity categories, NOT connection keys.\n");
            sb.append("Always use the connection_key value from the TABLE SCHEMA section below.\n\n");

            // Group nodes by their group_label
            Map<String, List<GraphNode>> byGroup = allNodes.stream()
                    .collect(Collectors.groupingBy(
                            n -> n.groupLabel() != null ? n.groupLabel() : "General"));

            for (Map.Entry<String, List<GraphNode>> entry : byGroup.entrySet()) {
                sb.append("[Group: ").append(entry.getKey()).append("]\n");
                for (GraphNode n : entry.getValue()) {
                    sb.append("• ").append(n.label())
                      .append(" [table: ").append(tableName(n.primaryObjectKey())).append("]");
                    if (n.operationalMeaning() != null && !n.operationalMeaning().isBlank()) {
                        sb.append("\n  Meaning: ").append(n.operationalMeaning());
                    }
                    if (n.investigationHints() != null && !n.investigationHints().isBlank()) {
                        sb.append("\n  Hint: ").append(n.investigationHints());
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }

            sb.append("--- Relationships (use these exact JOINs) ---\n");
            for (GraphEdge e : allEdges) {
                GraphNode src = nodeIndex.get(e.source());
                GraphNode tgt = nodeIndex.get(e.target());
                if (src == null || tgt == null) continue;
                sb.append("• ").append(src.label())
                  .append(" -[").append(e.relationshipType())
                  .append(" ").append(e.cardinality() != null ? e.cardinality() : "")
                  .append("]→ ").append(tgt.label());
                if (e.joinGuidance() != null && !e.joinGuidance().isBlank()) {
                    sb.append("\n  JOIN: ").append(e.joinGuidance());
                }
                sb.append("\n");
            }
            sb.append("=== END KNOWLEDGE GRAPH ===\n");
            return sb.toString();

        } catch (Exception ex) {
            log.warn("Failed to build graph context: {}", ex.getMessage());
            return "";
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Extracts the physical table name from an object_key like "platform-local-postgres-lgs-supplier". */
    private String tableName(String primaryObjectKey) {
        if (primaryObjectKey == null) return "unknown";
        // object keys are formatted as: domainKey-connectionKey-tableName (hyphens)
        // The table name is the last segment after the connection key
        String[] parts = primaryObjectKey.split("-");
        if (parts.length >= 3) {
            // Rejoin from part 3 onward to get the table name (may itself contain hyphens)
            StringBuilder sb = new StringBuilder();
            for (int i = 3; i < parts.length; i++) {
                if (sb.length() > 0) sb.append('_');
                sb.append(parts[i]);
            }
            return sb.toString();
        }
        return primaryObjectKey;
    }
}
