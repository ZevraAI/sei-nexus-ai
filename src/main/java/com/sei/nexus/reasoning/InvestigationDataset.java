package com.sei.nexus.reasoning;

import java.util.List;
import java.util.Map;

/**
 * One investigation step's own row-bearing result, preserved independently of every other step —
 * never merged, never ranked, never chosen as "the" answer over another. Mechanically derived, in
 * {@link ReasoningEngine#reason}, from every {@link EvidenceStore.StepEvidence} whose {@code
 * rows()} is non-empty, in step order, regardless of {@code evaluatorDecision} (a step is
 * included even when the evaluator asked for more evidence next — see EvidenceStore's javadoc on
 * {@code outcome} vs. {@code evaluatorDecision}).
 *
 * <p>Deliberately narrow — a small dedicated transport shape, not the internal reasoning record:
 * only {@code stepNo}, {@code description} (copied verbatim from {@link
 * EvidenceStore.StepEvidence#description()} — Agent Brain's own stated intent for that step,
 * never re-derived, re-titled, or inferred from the row content), and {@code rows}. No sql,
 * outcome, evaluatorDecision, or rationale — those remain internal to {@link EvidenceStore} and
 * are not part of this presentation-facing contract.
 *
 * @param stepNo      The originating step's number, exactly as recorded in {@code EvidenceStore}.
 * @param description The originating step's own description, verbatim.
 * @param rows        This step's own rows — never combined with any other step's rows.
 * @param chartType   Optional LLM-declared chart hint for this step's own result (see {@code
 *                    ReasoningPlanner.StepPlan}), carried through verbatim — null unless the
 *                    planner explicitly declared one. Never Java-inferred.
 * @param categoryKey Optional category/x-axis column or alias from this step's own SELECT list,
 *                    verbatim — null when not applicable (e.g. chartType "stats") or not declared.
 * @param valueKeys   Optional numeric column/alias name(s) from this step's own SELECT list,
 *                    verbatim — empty when not declared.
 * @param categoryLabel Optional purely presentational, LLM-authored label for {@code
 *                    categoryKey} (see {@code ReasoningPlanner.SYSTEM_PROMPT}'s OPTIONAL
 *                    HUMAN-READABLE LABELS guidance), verbatim — null when not declared.
 * @param valueLabels Optional LLM-authored labels parallel to {@code valueKeys}, verbatim —
 *                    empty when not declared.
 * @param metricLabel Optional LLM-authored label for a single headline figure this step's
 *                    result reduces to, verbatim — null when not declared.
 */
public record InvestigationDataset(int stepNo, String description, List<Map<String, Object>> rows,
        String chartType, String categoryKey, List<String> valueKeys,
        String categoryLabel, List<String> valueLabels, String metricLabel) {

    /** Pre-label shape — every caller from before the OPTIONAL HUMAN-READABLE LABELS guidance
     *  existed. Labels default to null/empty — behavior otherwise unchanged. */
    public InvestigationDataset(int stepNo, String description, List<Map<String, Object>> rows,
            String chartType, String categoryKey, List<String> valueKeys) {
        this(stepNo, description, rows, chartType, categoryKey, valueKeys, null, List.of(), null);
    }
}
