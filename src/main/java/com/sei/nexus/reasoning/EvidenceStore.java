package com.sei.nexus.reasoning;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * In-memory accumulator for a single reasoning session.
 *
 * <p>Stores each step's SQL, result rows, and a compact statistical summary.
 * The summary is what is fed back to the LLM planner and evaluator — it avoids
 * token overflow by describing the data (counts, distributions, totals) rather
 * than dumping raw row values.
 */
public final class EvidenceStore {

    public record StepEvidence(
            int                          stepNo,
            String                       description,
            String                       sql,
            String                       connectionKey,
            List<Map<String, Object>>    rows,
            String                       rowSummary,    // compact statistical description
            String                       evaluatorDecision,
            String                       evaluatorRationale,
            String                       plannerRationale,
            Instant                      executedAt,
            long                         executionMs
    ) {}

    private final List<StepEvidence> steps = new ArrayList<>();

    public void add(int stepNo, String description, String sql, String connectionKey,
                    List<Map<String, Object>> rows, String plannerRationale,
                    String evaluatorDecision, String evaluatorRationale, long executionMs) {
        steps.add(new StepEvidence(
                stepNo, description, sql, connectionKey,
                rows, buildRowSummary(rows),
                evaluatorDecision, evaluatorRationale, plannerRationale,
                Instant.now(), executionMs));
    }

    public List<StepEvidence> getSteps() { return Collections.unmodifiableList(steps); }

    public int stepCount() { return steps.size(); }

    public boolean isEmpty() { return steps.isEmpty(); }

    public Set<String> connectionKeys() {
        return steps.stream().map(StepEvidence::connectionKey)
                .filter(Objects::nonNull).collect(Collectors.toSet());
    }

    public int totalRows() {
        return steps.stream().mapToInt(s -> s.rows().size()).sum();
    }

    /** Returns the rows from the most recent successful step, or empty list. */
    public List<Map<String, Object>> latestRows() {
        for (int i = steps.size() - 1; i >= 0; i--) {
            List<Map<String, Object>> rows = steps.get(i).rows();
            if (!rows.isEmpty()) return rows;
        }
        return List.of();
    }

    /**
     * Builds a compact multi-line context string for the LLM planner/evaluator.
     * Uses statistical summaries rather than raw row data to stay within token limits.
     */
    public String buildContextForLlm() {
        if (steps.isEmpty()) return "No queries have been executed yet.";
        StringBuilder sb = new StringBuilder();
        for (StepEvidence s : steps) {
            sb.append("--- Step ").append(s.stepNo()).append(": ").append(s.description()).append('\n');
            sb.append("SQL: ").append(truncate(s.sql(), 300)).append('\n');
            sb.append("Result: ").append(s.rowSummary()).append('\n');
            if (s.evaluatorDecision() != null) {
                sb.append("Evaluation: ").append(s.evaluatorDecision());
                if (s.evaluatorRationale() != null) sb.append(" — ").append(s.evaluatorRationale());
                sb.append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    // ── Statistical row summariser ────────────────────────────────────────────

    private String buildRowSummary(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "Query returned 0 rows.";
        StringBuilder sb = new StringBuilder();
        sb.append(rows.size()).append(" row(s). Columns: ");
        Set<String> cols = rows.get(0).keySet();
        sb.append(String.join(", ", cols)).append(".\n");

        for (String col : cols) {
            List<String> vals = rows.stream()
                    .map(r -> r.getOrDefault(col, "") == null ? "" : r.get(col).toString())
                    .filter(v -> !v.isBlank())
                    .toList();

            boolean looksNumeric = vals.stream().allMatch(v -> {
                try { Double.parseDouble(v); return true; } catch (Exception e) { return false; }
            });
            boolean isId = col.toLowerCase().endsWith("_id") || col.equalsIgnoreCase("id");
            Map<String, Long> dist = vals.stream()
                    .collect(Collectors.groupingBy(v -> v, Collectors.counting()));
            boolean lowCardinality = dist.size() >= 2 && dist.size() <= 10 && dist.size() < rows.size();

            if (looksNumeric && !isId && !vals.isEmpty()) {
                double sum = vals.stream().mapToDouble(Double::parseDouble).sum();
                double avg = sum / vals.size();
                sb.append("  ").append(col).append(": sum=").append(fmt(sum))
                  .append(", avg=").append(fmt(avg)).append('\n');
            } else if (lowCardinality && !looksNumeric) {
                sb.append("  ").append(col).append(" distribution: ").append(dist).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
