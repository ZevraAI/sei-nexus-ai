package com.sei.nexus.graph;

import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the SEI Nexus knowledge graph.
 * Base path: /api/v1/knowledge-graph
 */
@RestController
@RequestMapping("/knowledge-graph")
public class KnowledgeGraphController {

    private final KnowledgeGraphService service;

    public KnowledgeGraphController(KnowledgeGraphService service) {
        this.service = service;
    }

    /**
     * GET /knowledge-graph?domainKey=PLATFORM
     * Returns the complete graph (all nodes + edges) for a domain.
     * If domainKey is omitted, returns the full cross-domain graph.
     */
    @GetMapping
    public ResponseEntity<GraphData> getGraph(
            @RequestParam(required = false) String domainKey) {
        GraphData data = (domainKey != null && !domainKey.isBlank())
                ? service.getGraphByDomain(domainKey)
                : service.getFullGraph();
        return ResponseEntity.ok(data);
    }

    /**
     * GET /knowledge-graph/neighbors/{entityKey}?depth=2
     * Returns the subgraph centred on the given entity up to {@code depth} hops.
     * Default depth = 2, max = 5.
     */
    @GetMapping("/neighbors/{entityKey}")
    public ResponseEntity<GraphData> getNeighbors(
            @PathVariable String entityKey,
            @RequestParam(defaultValue = "2") int depth) {
        if (depth < 1 || depth > 5) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "depth must be between 1 and 5");
        }
        return ResponseEntity.ok(service.getNeighborGraph(entityKey, depth));
    }

    /**
     * GET /knowledge-graph/paths?from={a}&to={b}
     * Returns the shortest-path subgraph between two entity keys.
     */
    @GetMapping("/paths")
    public ResponseEntity<GraphData> getPath(
            @RequestParam String from,
            @RequestParam String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "'from' and 'to' are required");
        }
        return ResponseEntity.ok(service.getShortestPath(from, to));
    }

    /**
     * GET /knowledge-graph/context?domainKeys=PLATFORM,FINANCE
     * Returns the AI-optimised graph context string used in LLM prompts.
     * Intended for debugging and admin use.
     */
    @GetMapping("/context")
    public ResponseEntity<Map<String, String>> getContext(
            @RequestParam String domainKeys) {
        List<String> keys = List.of(domainKeys.split(",\\s*"));
        String context = service.buildGraphContext(keys);
        return ResponseEntity.ok(Map.of("context", context));
    }
}
