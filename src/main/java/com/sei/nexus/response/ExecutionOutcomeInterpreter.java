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
     * A compact statistical summary of query rows: distributions and totals, never individual
     * row values. Byte-for-byte the interpretation the conversational path has always produced.
     */
    public String summarizeRows(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "Query returned 0 rows.\n";
        StringBuilder sb = new StringBuilder();
        sb.append("Total rows: ").append(rows.size()).append("\n");

        Set<String> cols = rows.get(0).keySet();
        sb.append("Columns: ").append(String.join(", ", cols)).append("\n");

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
            }
        }
        return sb.toString();
    }
}
