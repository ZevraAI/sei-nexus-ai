package com.sei.nexus.semantic;

import com.sei.nexus.common.Keys;
import com.sei.nexus.sql.DynamicSqlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Automatically discovers entity relationships from the connected database schema.
 *
 * <p>Two-phase discovery:
 * <ol>
 *   <li><b>Foreign key constraints</b> — reads {@code information_schema.table_constraints}
 *       on the user's database via the registered connection. Definitive — no guessing.</li>
 *   <li><b>Column-name heuristics</b> — for databases without explicit FK constraints
 *       (common in analytics warehouses and legacy systems). Looks for {@code _id} columns
 *       in one table whose name exactly matches a primary-key-like column in another table,
 *       then scores confidence based on column cardinality and naming patterns.</li>
 * </ol>
 *
 * <p>Only creates relationships that do not already exist.
 * Safe to call repeatedly — idempotent via ON CONFLICT DO NOTHING.
 */
@Service
public class RelationshipDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(RelationshipDiscoveryService.class);

    private final DynamicSqlService   dynamicSqlService;
    private final SemanticRepository  semanticRepository;
    private final JdbcTemplate        jdbc;

    public RelationshipDiscoveryService(DynamicSqlService dynamicSqlService,
                                         SemanticRepository semanticRepository,
                                         JdbcTemplate jdbc) {
        this.dynamicSqlService  = dynamicSqlService;
        this.semanticRepository = semanticRepository;
        this.jdbc               = jdbc;
    }

    /**
     * Discovers and persists relationships for all entities in a domain.
     *
     * @param connectionKey  the user's database connection
     * @param schemaName     schema to inspect (e.g. "public")
     * @param domainKey      Zevra domain the entities belong to
     * @return number of new relationships created
     */
    public int discoverAndPersist(String connectionKey, String schemaName, String domainKey) {
        // 1. Build table → entity mapping from nexus_data_object
        Map<String, String> tableToEntityKey = buildTableToEntityIndex(domainKey);
        if (tableToEntityKey.size() < 2) {
            log.info("Not enough entities in domain '{}' to discover relationships (need ≥ 2)", domainKey);
            return 0;
        }

        log.info("Discovering relationships for domain '{}' across {} entities using connection '{}'",
                domainKey, tableToEntityKey.size(), connectionKey);

        Set<String> discovered = new LinkedHashSet<>();

        // 2a. Foreign key constraints (definitive)
        discovered.addAll(discoverFromForeignKeys(connectionKey, schemaName, tableToEntityKey, domainKey));

        // 2b. Column-name heuristics (for databases without FK constraints)
        if (discovered.isEmpty()) {
            log.info("No FK constraints found — falling back to column-name heuristics");
            discovered.addAll(discoverFromColumnHeuristics(connectionKey, schemaName, tableToEntityKey, domainKey));
        }

        log.info("Relationship discovery complete for domain '{}': {} new relationships", domainKey, discovered.size());
        return discovered.size();
    }

    // ── Phase 1: Foreign key constraints ─────────────────────────────────────

    private List<String> discoverFromForeignKeys(String connectionKey, String schemaName,
                                                   Map<String, String> tableToEntityKey,
                                                   String domainKey) {
        String sql = """
                SELECT
                    kcu.table_name  AS fk_table,
                    kcu.column_name AS fk_column,
                    ccu.table_name  AS pk_table,
                    ccu.column_name AS pk_column
                  FROM information_schema.table_constraints   tc
                  JOIN information_schema.key_column_usage    kcu
                    ON tc.constraint_name = kcu.constraint_name
                   AND tc.table_schema    = kcu.table_schema
                  JOIN information_schema.constraint_column_usage ccu
                    ON ccu.constraint_name = tc.constraint_name
                   AND ccu.table_schema    = tc.table_schema
                 WHERE tc.constraint_type = 'FOREIGN KEY'
                   AND tc.table_schema    = '%s'
                """.formatted(schemaName);

        List<String> created = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = dynamicSqlService.executeQuery(connectionKey, sql, 1000);
            for (Map<String, Object> row : rows) {
                String fkTable  = str(row, "fk_table");
                String fkCol    = str(row, "fk_column");
                String pkTable  = str(row, "pk_table");
                String pkCol    = str(row, "pk_column");

                String sourceEntityKey = tableToEntityKey.get(fkTable);
                String targetEntityKey = tableToEntityKey.get(pkTable);
                if (sourceEntityKey == null || targetEntityKey == null
                        || sourceEntityKey.equals(targetEntityKey)) continue;

                // FK table REFERENCES pk table → REFERENCES (N:1)
                String relKey = createRelationship(
                        sourceEntityKey, targetEntityKey,
                        "REFERENCES", fkCol, pkCol,
                        buildJoinGuidance(fkTable, fkCol, pkTable, pkCol),
                        "N:1", false, domainKey);
                if (relKey != null) created.add(relKey);

                // Inverse: pk table HAS_MANY fk table (1:N)
                String invKey = createRelationship(
                        targetEntityKey, sourceEntityKey,
                        "HAS_MANY", pkCol, fkCol,
                        buildJoinGuidance(pkTable, pkCol, fkTable, fkCol),
                        "1:N", false, domainKey);
                if (invKey != null) created.add(invKey);
            }
            log.info("FK discovery found {} relationships in schema '{}'", created.size(), schemaName);
        } catch (Exception e) {
            log.warn("FK constraint discovery failed for connection '{}': {}", connectionKey, e.getMessage());
        }
        return created;
    }

    // ── Phase 2: Column-name heuristics ──────────────────────────────────────

    private List<String> discoverFromColumnHeuristics(String connectionKey, String schemaName,
                                                        Map<String, String> tableToEntityKey,
                                                        String domainKey) {
        // For each table, find columns ending in _id and see if there's another
        // table whose likely primary key column matches the same name.
        String sql = """
                SELECT
                    c.table_name,
                    c.column_name,
                    c.data_type
                  FROM information_schema.columns c
                 WHERE c.table_schema = '%s'
                   AND c.table_name   IN (%s)
                   AND (LOWER(c.column_name) LIKE '%%_id'
                        OR LOWER(c.column_name) IN ('id'))
                 ORDER BY c.table_name, c.column_name
                """.formatted(schemaName, quotedList(tableToEntityKey.keySet()));

        List<String> created = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = dynamicSqlService.executeQuery(connectionKey, sql, 5000);

            // Build map: table → {column_name → data_type}
            Map<String, Map<String, String>> tableColumns = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String tbl = str(row, "table_name");
                String col = str(row, "column_name");
                String typ = str(row, "data_type");
                tableColumns.computeIfAbsent(tbl, k -> new LinkedHashMap<>()).put(col, typ);
            }

            // Heuristic: if table A has column "X_id" or "X", and table B has
            // a column named the same that looks like a PK (named id or X_id,
            // appears in exactly one table's name prefix) → candidate relationship
            for (Map.Entry<String, Map<String, String>> entry : tableColumns.entrySet()) {
                String sourceTable     = entry.getKey();
                String sourceEntityKey = tableToEntityKey.get(sourceTable);
                if (sourceEntityKey == null) continue;

                for (String col : entry.getValue().keySet()) {
                    if (col.equalsIgnoreCase("id")) continue; // own PK, not a FK

                    // Find target table: the column name without trailing _id should
                    // partially match another table name
                    String prefix = col.endsWith("_id")
                            ? col.substring(0, col.length() - 3)
                            : col;

                    for (Map.Entry<String, Map<String, String>> target : tableColumns.entrySet()) {
                        String targetTable     = target.getKey();
                        String targetEntityKey = tableToEntityKey.get(targetTable);
                        if (targetEntityKey == null) continue;
                        if (targetTable.equals(sourceTable)) continue;
                        if (targetEntityKey.equals(sourceEntityKey)) continue;

                        // Target table contains the same column name (likely the PK side)
                        Map<String, String> targetCols = target.getValue();
                        if (!targetCols.containsKey(col)) continue;

                        // Confidence: target table name should include the prefix
                        String cleanTarget = targetTable.replaceAll("^(lgs_|stg_|dim_|fact_|tbl_)", "");
                        if (!cleanTarget.toLowerCase().startsWith(prefix.toLowerCase())
                                && !cleanTarget.toLowerCase().contains(prefix.toLowerCase())) continue;

                        // Good candidate — create REFERENCES relationship
                        String relKey = createRelationship(
                                sourceEntityKey, targetEntityKey,
                                "REFERENCES", col, col,
                                buildJoinGuidance(sourceTable, col, targetTable, col),
                                "N:1", false, domainKey);
                        if (relKey != null) {
                            created.add(relKey);
                            log.debug("Heuristic: {} → {} via {} (column match)", sourceTable, targetTable, col);
                        }

                        // Inverse HAS_MANY
                        String invKey = createRelationship(
                                targetEntityKey, sourceEntityKey,
                                "HAS_MANY", col, col,
                                buildJoinGuidance(targetTable, col, sourceTable, col),
                                "1:N", false, domainKey);
                        if (invKey != null) created.add(invKey);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Heuristic relationship discovery failed for connection '{}': {}", connectionKey, e.getMessage());
        }
        return created;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the table → entity_key index by joining nexus_data_object with
     * nexus_business_entity on primary_object_key, scoped to the given domain.
     */
    /**
     * Builds the table_name → entity_key index used to map FK relationships to
     * semantic entity relationships.
     *
     * <p>Strategy (in order):
     * <ol>
     *   <li>Exact link via {@code primary_object_key} — entities created after this
     *       fix will always have it set.</li>
     *   <li>Name-pattern fallback — for entities created before the fix whose
     *       {@code primary_object_key} is NULL, derive the match by comparing the
     *       slugified table name (strip common prefixes, replace {@code _} with {@code -})
     *       against the entity key. Covers both {@code lgs_purchase_order → purchase-order}
     *       and plain {@code orders → orders}.</li>
     * </ol>
     */
    private Map<String, String> buildTableToEntityIndex(String domainKey) {
        Map<String, String> index = new LinkedHashMap<>();

        // 1. Primary: join via primary_object_key
        try {
            jdbc.query("""
                    SELECT obj.table_name, be.entity_key
                      FROM nexus_data_object obj
                      JOIN nexus_business_entity be
                        ON be.primary_object_key = obj.object_key
                     WHERE obj.domain_key = ?
                       AND be.domain_key  = ?
                       AND be.status != 'ARCHIVED'
                     GROUP BY obj.table_name, be.entity_key
                    """,
                    (rs, i) -> Map.entry(rs.getString("table_name"), rs.getString("entity_key")),
                    domainKey, domainKey)
                    .forEach(e -> index.put(e.getKey(), e.getValue()));
        } catch (Exception e) {
            log.warn("Primary table→entity index failed: {}", e.getMessage());
        }

        // 2. Fallback: match by slugified table name for entities without primary_object_key
        if (index.size() < 2) {
            try {
                // Get all data objects (tables) for this domain
                List<String> tables = jdbc.queryForList(
                        "SELECT DISTINCT table_name FROM nexus_data_object WHERE domain_key = ?",
                        String.class, domainKey);

                // Get all entities whose primary_object_key is not yet set
                List<Map.Entry<String, String>> entities = jdbc.query("""
                        SELECT entity_key, entity_name
                          FROM nexus_business_entity
                         WHERE domain_key = ?
                           AND status != 'ARCHIVED'
                           AND (primary_object_key IS NULL OR primary_object_key = '')
                        """,
                        (rs, i) -> Map.entry(rs.getString("entity_key"), rs.getString("entity_name")),
                        domainKey);

                for (String table : tables) {
                    if (index.containsKey(table)) continue; // already resolved
                    // Normalise: strip common prefixes, convert _ to -
                    String norm = table
                            .replaceAll("^(lgs_|stg_|dim_|fact_|tbl_|vw_)", "")
                            .replace("_", "-");
                    for (Map.Entry<String, String> ent : entities) {
                        String eKey = ent.getKey(); // e.g. "purchase-order"
                        if (eKey.equalsIgnoreCase(norm)
                                || norm.startsWith(eKey.replace("-", ""))
                                || eKey.replace("-", "").equalsIgnoreCase(
                                        norm.replace("-", ""))) {
                            index.put(table, eKey);
                            break;
                        }
                    }
                }
                log.info("After fallback matching: {} table→entity mappings in domain '{}'",
                        index.size(), domainKey);
            } catch (Exception e) {
                log.warn("Fallback table→entity matching failed: {}", e.getMessage());
            }
        }

        return index;
    }

    /**
     * Persists one relationship. Returns the relationship key if created,
     * null if it already existed.
     */
    private String createRelationship(String sourceEntityKey, String targetEntityKey,
                                       String type, String sourceCol, String targetCol,
                                       String joinGuidance, String cardinality,
                                       boolean bidirectional, String domainKey) {
        // Check if already exists (either direction)
        List<Integer> existing = jdbc.query("""
                SELECT 1 FROM nexus_entity_relationship
                 WHERE (source_entity_key = ? AND target_entity_key = ?)
                    OR (source_entity_key = ? AND target_entity_key = ?
                        AND relationship_type = ?)
                 LIMIT 1
                """,
                (rs, i) -> 1,
                sourceEntityKey, targetEntityKey,
                targetEntityKey, sourceEntityKey, invertType(type));

        if (!existing.isEmpty()) return null;

        String relKey = Keys.uniqueKey("rel");
        try {
            jdbc.update("""
                    INSERT INTO nexus_entity_relationship
                        (relationship_key, source_entity_key, target_entity_key,
                         relationship_type, source_column, target_column,
                         join_guidance, cross_system, cardinality, bidirectional,
                         edge_color, created_at)
                    VALUES (?,?,?,?,?,?,?,FALSE,?,?,?,NOW())
                    ON CONFLICT (relationship_key) DO NOTHING
                    """,
                    relKey, sourceEntityKey, targetEntityKey,
                    type, sourceCol, targetCol,
                    joinGuidance, cardinality, bidirectional,
                    colorForType(type));
            return relKey;
        } catch (Exception e) {
            log.warn("Failed to create relationship {} → {}: {}", sourceEntityKey, targetEntityKey, e.getMessage());
            return null;
        }
    }

    private String buildJoinGuidance(String srcTable, String srcCol, String tgtTable, String tgtCol) {
        return String.format("JOIN %s ON %s.%s = %s.%s", tgtTable, tgtTable, tgtCol, srcTable, srcCol);
    }

    private String invertType(String type) {
        return switch (type) {
            case "HAS_MANY"   -> "REFERENCES";
            case "REFERENCES" -> "HAS_MANY";
            default           -> type;
        };
    }

    private String colorForType(String type) {
        return switch (type) {
            case "HAS_MANY"   -> "#10B981";
            case "REFERENCES" -> "#3B82F6";
            case "BELONGS_TO" -> "#8B5CF6";
            default           -> "#6B7280";
        };
    }

    private String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString() : "";
    }

    private String quotedList(Set<String> items) {
        return items.stream()
                .map(s -> "'" + s.replace("'", "''") + "'")
                .collect(Collectors.joining(", "));
    }
}
