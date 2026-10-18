package com.sei.nexus.response;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic interpretation of execution results (Unified Answer Engine, Phase 4).
 *
 * <p>The single owner of turning query rows into a compact statistical summary — totals,
 * low-cardinality distributions, and numeric sums/averages — for downstream natural-language
 * composition. It emits <b>no presentation</b> (no markdown, HTML, or JSON shape): row-level
 * display belongs to each experience's frontend. Deterministic and stateless, so identical rows
 * always produce an identical summary and every experience can share it.
 */
@Component
public class ExecutionOutcomeInterpreter {

    /**
     * Small-result ceiling for explicit value rendering (below) — the same magnitude {@code
     * EvidenceStore.buildRowSummary} already uses for the planner/evaluator loop's identical
     * "don't lose a small result's actual values" case. Reused here, not reinvented.
     */
    private static final int SMALL_RESULT_ROW_LIMIT = 10;

    /**
     * A compact statistical summary of query rows: distributions and totals for columns that
     * have them, plus (for small results only) the actual values of any column neither branch
     * covers — e.g. a single-row result's identifying/descriptive columns (a name, a SKU, an id),
     * which are neither "low cardinality" (impossible to have >=2 distinct values in 1 row) nor
     * excluded-as-numeric-id. Previously such columns were silently omitted entirely — this
     * closes that gap using the same technique {@code EvidenceStore.buildRowSummary} already
     * proves out, driven purely by row count and column shape (never by column name or domain
     * assumptions), so descriptive values the investigation already resolved reach the answer-
     * composition LLM instead of being invisibly dropped.
     */
    public String summarizeRows(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "Query returned 0 rows.\n";
        StringBuilder sb = new StringBuilder();
        sb.append("Total rows: ").append(rows.size()).append("\n");

        Set<String> cols = rows.get(0).keySet();
        sb.append("Columns: ").append(String.join(", ", cols)).append("\n");

        boolean smallResult = rows.size() <= SMALL_RESULT_ROW_LIMIT;
        StringBuilder valuesBlock = new StringBuilder();

        for (String col : cols) {
            // Distribution for low-cardinality string columns (likely categorical)
            List<String> strVals = rows.stream()
                    .map(r -> String.valueOf(r.getOrDefault(col, "")))
                    .filter(v -> !v.isBlank() && !v.equals("null"))
                    .collect(Collectors.toList());
            Map<String, Long> dist = strVals.stream()
                    .collect(Collectors.groupingBy(v -> v, Collectors.counting()));
            boolean isLowCardinality = dist.size() >= 2 && dist.size() <= 8 && dist.size() < rows.size();
            boolean looksNumeric = strVals.stream().allMatch(v -> { try { Double.parseDouble(v); return true; } catch (Exception e) { return false; } });
            boolean isId = col.toLowerCase().endsWith("_id") || col.equalsIgnoreCase("id");

            if (isLowCardinality && !looksNumeric) {
                sb.append("  ").append(col).append(" distribution: ").append(dist).append("\n");
            } else if (looksNumeric && !isId) {
                // Sum and average for numeric non-ID columns
                try {
                    double sum = strVals.stream().mapToDouble(Double::parseDouble).sum();
                    double avg = sum / strVals.size();
                    sb.append("  ").append(col).append(": sum=").append(String.format("%.2f", sum))
                      .append(", avg=").append(String.format("%.2f", avg)).append("\n");
                } catch (Exception ignored) {}
            } else if (smallResult && !strVals.isEmpty()) {
                // Neither branch above covers this column (e.g. a single-row string/id column,
                // where >=2 distinct values is impossible) — render its actual value(s) verbatim
                // rather than silently dropping them. Only for small results, so a large result's
                // conciseness is unaffected (identical output to before this change).
                String joined = strVals.stream().map(v -> truncate(v, 80)).collect(Collectors.joining(", "));
                valuesBlock.append("  ").append(col).append(" = ").append(joined).append("\n");
            } else if (!strVals.isEmpty()) {
                // Larger result, column not described above (not a measure, not low-cardinality):
                // show what its values look like so the answer is not written blind to whether they
                // are readable labels or generated keys. A plain observation about the data.
                sb.append("  ").append(col).append(": ").append(dist.size()).append(" distinct, e.g. ")
                  .append(strVals.stream().distinct().limit(3).map(v -> truncate(v, 40))
                          .collect(Collectors.joining(", "))).append("\n");
            }
        }
        if (!valuesBlock.isEmpty()) {
            sb.append("Values:\n").append(valuesBlock);
        }
        if (smallResult && rows.size() >= 2 && cols.size() >= 2) {
            sb.append(renderAlignedRows(rows, cols));
        }
        return sb.toString();
    }

    /** Upper bound (characters) on the aligned-rows block, so a wide small result cannot bloat the
     *  composer prompt; rows beyond it are counted, not silently dropped. */
    private static final int MAX_ALIGNED_ROWS_CHARS = 6_000;

    /**
     * The rows themselves, one per line, each value next to its own column name. The per-column
     * statistics and "Values:" lists above are deliberately column-wise, which is exactly what makes
     * them unsafe for a multi-row, multi-column result: a numeric column is reduced to sum/avg and a
     * string column to an unordered value list, so a reader cannot tell WHICH row a value belongs
     * to — and an answer-writing model then states the wrong record's figure (a live example: a
     * supplier's rating and on-time rate quoted from a different supplier's row). With 2+ rows and
     * 2+ columns the alignment is the information, so it is rendered explicitly. Pure shape-driven
     * mechanics: nothing here looks at a column's name, meaning or domain. A single row, or a single
     * column, has nothing to misalign and is left exactly as before.
     */
    private static String renderAlignedRows(List<Map<String, Object>> rows, Set<String> cols) {
        StringBuilder out = new StringBuilder(
                "Rows (each line is ONE row; every value belongs to the column named beside it):\n");
        int shown = 0;
        for (Map<String, Object> r : rows) {
            String line = cols.stream()
                    .map(c -> {
                        Object v = r.get(c);
                        String s = v == null ? "" : String.valueOf(v);
                        return (s.isBlank() || s.equals("null")) ? null : c + " = " + truncate(s, 80);
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.joining("; "));
            String entry = "  " + (shown + 1) + ". " + line + "\n";
            if (out.length() + entry.length() > MAX_ALIGNED_ROWS_CHARS) break;
            out.append(entry);
            shown++;
        }
        if (shown < rows.size()) {
            out.append("  … ").append(rows.size() - shown).append(" more row(s) not shown here\n");
        }
        return out.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
