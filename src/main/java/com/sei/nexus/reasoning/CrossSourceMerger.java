package com.sei.nexus.reasoning;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * In-memory JOIN utility for combining results from different database connections.
 *
 * <p>Used when step N retrieves data from connection A and the planner determines
 * that step N+1 needs to enrich those results with data from connection B.
 * Because the two connections cannot be joined via SQL, the merge is done in memory
 * after both queries have executed.
 *
 * <p>The merge key is matched case-insensitively and coerced to String for comparison,
 * which handles most practical cases (integer IDs, UUIDs, string codes).
 */
@Component
public class CrossSourceMerger {

    public enum JoinType { INNER, LEFT }

    /**
     * Merge two result sets on a common key column.
     *
     * @param left      Rows from the "left" (primary) query.
     * @param leftKey   Column name in the left rows to join on.
     * @param right     Rows from the "right" (enrichment) query.
     * @param rightKey  Column name in the right rows to join on.
     * @param joinType  INNER keeps only rows with a match; LEFT keeps all left rows.
     * @return Merged rows with all columns from both sides (right columns suffixed with
     *         {@code _r} on collision).
     */
    public List<Map<String, Object>> merge(
            List<Map<String, Object>> left,  String leftKey,
            List<Map<String, Object>> right, String rightKey,
            JoinType joinType) {

        if (left == null || left.isEmpty()) return List.of();

        // Build an index: rightKey value → first matching right row
        Map<String, Map<String, Object>> rightIndex = new HashMap<>();
        if (right != null) {
            for (Map<String, Object> row : right) {
                String keyVal = normalize(row.get(rightKey));
                if (keyVal != null) rightIndex.putIfAbsent(keyVal, row);
            }
        }

        // Determine right-side column names that collide with left-side columns
        Set<String> leftCols  = left.isEmpty()  ? Set.of() : left.get(0).keySet();
        Set<String> rightCols = right == null || right.isEmpty() ? Set.of()
                : right.get(0).keySet();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> leftRow : left) {
            String keyVal = normalize(leftRow.get(leftKey));
            Map<String, Object> rightRow = keyVal != null ? rightIndex.get(keyVal) : null;

            if (rightRow == null && joinType == JoinType.INNER) continue;

            Map<String, Object> merged = new LinkedHashMap<>(leftRow);
            if (rightRow != null) {
                for (Map.Entry<String, Object> entry : rightRow.entrySet()) {
                    String col = entry.getKey();
                    // Avoid overwriting left-side columns; suffix colliding right columns
                    String targetCol = leftCols.contains(col) ? col + "_r" : col;
                    merged.put(targetCol, entry.getValue());
                }
            } else {
                // LEFT JOIN — add nulls for all right columns
                for (String col : rightCols) {
                    String targetCol = leftCols.contains(col) ? col + "_r" : col;
                    merged.putIfAbsent(targetCol, null);
                }
            }
            result.add(merged);
        }
        return result;
    }

    private String normalize(Object val) {
        return val == null ? null : val.toString().trim();
    }
}
