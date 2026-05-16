package com.sei.nexus.graph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class KnowledgeGraphRepository {

    private final JdbcTemplate jdbc;

    public KnowledgeGraphRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Node queries ──────────────────────────────────────────────────────────

    public List<GraphNode> findNodesByDomain(String domainKey) {
        return jdbc.query("""
                SELECT entity_key, entity_name, node_type, color, group_label,
                       domain_key, description, primary_object_key,
                       operational_meaning, investigation_hints, status
                  FROM nexus_business_entity
                 WHERE domain_key = ? AND status != 'ARCHIVED'
                 ORDER BY node_type, entity_name
                """, nodeMapper(), domainKey);
    }

    public List<GraphNode> findAllNodes() {
        return jdbc.query("""
                SELECT entity_key, entity_name, node_type, color, group_label,
                       domain_key, description, primary_object_key,
                       operational_meaning, investigation_hints, status
                  FROM nexus_business_entity
                 WHERE status != 'ARCHIVED'
                 ORDER BY node_type, entity_name
                """, nodeMapper());
    }

    /** Recursive CTE — finds all nodes within {@code depth} hops of the given entity. */
    public List<GraphNode> findNeighbors(String entityKey, int depth) {
        return jdbc.query("""
                WITH RECURSIVE neighbors(entity_key, depth) AS (
                    SELECT ?, 0
                    UNION ALL
                    SELECT r.target_entity_key, n.depth + 1
                      FROM nexus_entity_relationship r
                      JOIN neighbors n ON n.entity_key = r.source_entity_key
                     WHERE n.depth < ?
                    UNION ALL
                    SELECT r.source_entity_key, n.depth + 1
                      FROM nexus_entity_relationship r
                      JOIN neighbors n ON n.entity_key = r.target_entity_key
                     WHERE n.depth < ? AND r.bidirectional = TRUE
                )
                SELECT DISTINCT e.entity_key, e.entity_name, e.node_type, e.color,
                       e.group_label, e.domain_key, e.description, e.primary_object_key,
                       e.operational_meaning, e.investigation_hints, e.status
                  FROM nexus_business_entity e
                  JOIN neighbors nb ON nb.entity_key = e.entity_key
                 WHERE e.status != 'ARCHIVED'
                """, nodeMapper(), entityKey, depth, depth);
    }

    // ── Edge queries ──────────────────────────────────────────────────────────

    public List<GraphEdge> findEdgesByDomain(String domainKey) {
        return jdbc.query("""
                SELECT r.relationship_key, r.source_entity_key, r.target_entity_key,
                       r.relationship_type, r.source_column, r.target_column,
                       r.join_guidance, r.cardinality, r.bidirectional, r.edge_color
                  FROM nexus_entity_relationship r
                  JOIN nexus_business_entity src ON src.entity_key = r.source_entity_key
                  JOIN nexus_business_entity tgt ON tgt.entity_key = r.target_entity_key
                 WHERE src.domain_key = ? OR tgt.domain_key = ?
                """, edgeMapper(), domainKey, domainKey);
    }

    public List<GraphEdge> findAllEdges() {
        return jdbc.query("""
                SELECT relationship_key, source_entity_key, target_entity_key,
                       relationship_type, source_column, target_column,
                       join_guidance, cardinality, bidirectional, edge_color
                  FROM nexus_entity_relationship
                """, edgeMapper());
    }

    /** Edges that touch any node in the neighbor set. */
    public List<GraphEdge> findEdgesForNodes(List<String> entityKeys) {
        if (entityKeys == null || entityKeys.isEmpty()) return List.of();
        String[] arr = entityKeys.toArray(new String[0]);
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT relationship_key, source_entity_key, target_entity_key,
                           relationship_type, source_column, target_column,
                           join_guidance, cardinality, bidirectional, edge_color
                      FROM nexus_entity_relationship
                     WHERE source_entity_key = ANY(?) OR target_entity_key = ANY(?)
                    """);
            var pgArr = con.createArrayOf("text", arr);
            ps.setArray(1, pgArr);
            ps.setArray(2, pgArr);
            return ps;
        }, edgeMapper());
    }

    /**
     * Shortest path between two entities using a bidirectional BFS implemented
     * as a recursive CTE. Returns the sequence of entity keys on the path.
     */
    public List<String> findShortestPath(String fromKey, String toKey) {
        return jdbc.query("""
                WITH RECURSIVE path(entity_key, trail, depth, found) AS (
                    SELECT ?, ARRAY[?]::text[], 0, (? = ?)
                    UNION ALL
                    SELECT r.target_entity_key,
                           p.trail || r.target_entity_key,
                           p.depth + 1,
                           r.target_entity_key = ?
                      FROM nexus_entity_relationship r
                      JOIN path p ON p.entity_key = r.source_entity_key
                     WHERE p.depth < 8
                       AND NOT (r.target_entity_key = ANY(p.trail))
                )
                SELECT trail
                  FROM path
                 WHERE found = TRUE
                 ORDER BY depth
                 LIMIT 1
                """,
                (rs, i) -> rs.getString(1),
                fromKey, fromKey, fromKey, toKey, toKey);
    }

    // ── Row mappers ───────────────────────────────────────────────────────────

    private RowMapper<GraphNode> nodeMapper() {
        return (rs, i) -> new GraphNode(
                rs.getString("entity_key"),
                rs.getString("entity_name"),
                rs.getString("node_type"),
                rs.getString("color"),
                rs.getString("group_label"),
                rs.getString("domain_key"),
                rs.getString("description"),
                rs.getString("primary_object_key"),
                rs.getString("operational_meaning"),
                rs.getString("investigation_hints"),
                rs.getString("status"));
    }

    private RowMapper<GraphEdge> edgeMapper() {
        return (rs, i) -> new GraphEdge(
                rs.getString("relationship_key"),
                rs.getString("source_entity_key"),
                rs.getString("target_entity_key"),
                rs.getString("relationship_type"),
                rs.getString("source_column"),
                rs.getString("target_column"),
                rs.getString("join_guidance"),
                rs.getString("cardinality"),
                rs.getBoolean("bidirectional"),
                rs.getString("edge_color"));
    }
}
