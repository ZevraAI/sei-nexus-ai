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
 */
public record InvestigationDataset(int stepNo, String description, List<Map<String, Object>> rows) {}
