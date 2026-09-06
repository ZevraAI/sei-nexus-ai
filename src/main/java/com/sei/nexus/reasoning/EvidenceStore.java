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
 *
 * <p><b>{@code outcome} vs. {@code evaluatorDecision} (Investigation-Step Semantics):</b> every
 * step has an {@link StepEvidence#outcome()} — what THIS step itself achieved (a query executed
 * successfully, metadata was retrieved, a request was declined/rejected/blocked, or execution
 * failed). {@link StepEvidence#evaluatorDecision()} is a DIFFERENT, optional signal: the {@code
 * ReasoningEvaluator}'s verdict on whether the evidence accumulated so far (not just this one
 * step) is sufficient to answer the whole question — {@code null} unless this exact step was run
 * through evaluation. A step whose {@code evaluatorDecision} is {@code "NEED_MORE_DATA"} still
 * has {@code outcome() == "QUERY_SUCCEEDED"}: the query succeeded: Agent Brain simply decided
 * more evidence was needed next. Conflating these two (using one field for both) is the exact
 * defect this split fixes — a successful step must never render as failed/incomplete merely
 * because reasoning continued past it.
 */
public final class EvidenceStore {

    /** This step's own result — never influenced by what happens in a later step. */
    public static final String OUTCOME_QUERY_SUCCEEDED   = "QUERY_SUCCEEDED";
    public static final String OUTCOME_METADATA_RETRIEVED = "METADATA_RETRIEVED";

    public record StepEvidence(
            int                          stepNo,
            String                       description,
            String                       sql,
            String                       connectionKey,
            List<Map<String, Object>>    rows,
            String                       rowSummary,    // compact statistical description
            // This step's own result (see class javadoc) — always non-null.
            String                       outcome,
            // The evaluator's verdict on OVERALL accumulated sufficiency, present ONLY for a
            // step actually run through evaluation — null for every other step type (metadata,
            // clarification, rejection, error). Never a substitute for `outcome`.
            String                       evaluatorDecision,
            String                       evaluatorRationale,
            String                       plannerRationale,
            Instant                      executedAt,
            long                         executionMs
    ) {}

    private final List<StepEvidence> steps = new ArrayList<>();

    /**
     * Records an executed query step — the ONLY step type carrying a real {@link
     * ReasoningEvaluator} verdict. {@code outcome} is always {@link #OUTCOME_QUERY_SUCCEEDED}:
     * the query itself executed (rows may legitimately be empty — see {@link #buildRowSummary});
     * {@code evaluatorDecision} separately says whether Agent Brain judged the ACCUMULATED
     * evidence sufficient to stop investigating. Use {@link #addOutcome} for a step that never
     * reached execution/evaluation (declined, rejected, blocked, or failed).
     */
    public void add(int stepNo, String description, String sql, String connectionKey,
                    List<Map<String, Object>> rows, String plannerRationale,
                    String evaluatorDecision, String evaluatorRationale, long executionMs) {
        steps.add(new StepEvidence(
                stepNo, description, sql, connectionKey,
                rows, buildRowSummary(rows),
                OUTCOME_QUERY_SUCCEEDED, evaluatorDecision, evaluatorRationale, plannerRationale,
                Instant.now(), executionMs));
    }

    /**
     * Records a step whose own result is something other than "executed a query the evaluator
     * judged" — a declined/rejected/blocked step, an execution error, or (via {@link
     * #addMetadataStep}) a metadata retrieval. {@code outcome} is this step's OWN, terminal
     * result (e.g. {@code "CLARIFICATION_NEEDED"}, {@code "UNAPPROVED_OBJECTS"}, {@code
     * "ERROR"}) — never the evaluator's sufficiency verdict, which does not apply here (no
     * query executed for the evaluator to judge, so {@code evaluatorDecision} is {@code null}).
     * {@code detail} is caller-supplied — describes what happened, never derived from {@code
     * rows} (there are none) via the generic row-count summary, so this step never misrepresents
     * as an empty query.
     */
    public void addOutcome(int stepNo, String description, String sql, String connectionKey,
                           String plannerRationale, String outcome, String detail, long executionMs) {
        steps.add(new StepEvidence(
                stepNo, description, sql == null ? "" : sql, connectionKey == null ? "" : connectionKey,
                List.of(), detail,
                outcome, null, detail, plannerRationale,
                Instant.now(), executionMs));
    }

    /**
     * Records a step that retrieved (or attempted to retrieve) metadata, not query rows — a
     * Missing-Column Metadata Request outcome (see {@code ReasoningEngine}). A thin, purpose-
     * named wrapper over {@link #addOutcome} — a metadata step never gets the generic "Query
     * returned 0 rows." row summary: it never executed a query and has no rows, so that summary
     * would misrepresent a successful metadata retrieval as an empty query result.
     */
    public void addMetadataStep(int stepNo, String description, String plannerRationale,
                                String decision, String summary) {
        addOutcome(stepNo, description, "", "", plannerRationale, decision, summary, 0L);
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
            // This step's own outcome — always present, never affected by what a later step
            // decides (see class javadoc: outcome vs. evaluatorDecision).
            sb.append("Outcome: ").append(s.outcome()).append('\n');
            if (s.evaluatorDecision() != null) {
                // A SEPARATE signal: whether the evidence accumulated so far (not just this
                // step) is enough to stop investigating — the reason for the NEXT action, not
                // a verdict on this step's own success.
                sb.append("Next-step assessment: ").append(s.evaluatorDecision());
                if (s.evaluatorRationale() != null) sb.append(" — ").append(s.evaluatorRationale());
                sb.append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    // ── Statistical row summariser ────────────────────────────────────────────

    /**
     * Small-result ceiling for explicit value rendering (below). Reuses the same magnitude
     * already established by the distribution branch's own cardinality cap — not a new
     * threshold invented for this case.
     */
    private static final int SMALL_RESULT_ROW_LIMIT = 10;

    private String buildRowSummary(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "Query returned 0 rows.";
        StringBuilder sb = new StringBuilder();
        sb.append(rows.size()).append(" row(s). Columns: ");
        Set<String> cols = rows.get(0).keySet();
        sb.append(String.join(", ", cols)).append(".\n");

        boolean smallResult = rows.size() <= SMALL_RESULT_ROW_LIMIT;
        StringBuilder valuesBlock = new StringBuilder();

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
            } else if (smallResult && !vals.isEmpty()) {
                // Neither the aggregate nor the distribution branch captures a column whose
                // value a later step may need to reuse verbatim — a single resolved id, a
                // single resolved name, or a handful of distinct rows' values (including a
                // numeric id, which the aggregate branch deliberately excludes). This only
                // fires when the whole result is small (<= SMALL_RESULT_ROW_LIMIT rows), so
                // a large result set is never enumerated here — it stays exactly as
                // summarized as it was before this branch existed.
                String joined = vals.stream().map(v -> truncate(v, 80)).collect(Collectors.joining(", "));
                valuesBlock.append("  ").append(col).append(" = ").append(joined).append('\n');
            }
        }

        if (!valuesBlock.isEmpty()) {
            sb.append("Values:\n").append(valuesBlock);
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
