package com.sei.nexus.semantic;

import java.util.List;

/**
 * A single implicit-or-explicit learning trigger, carrying exactly the already-resolved
 * identities Java is allowed to know — never a re-derivation of semantic meaning.
 *
 * <p>Two implicit sources exist today (see {@code ChatService}'s post-answer learning
 * block and {@link SemanticLearningService#detectAndSaveCorrection}):
 * <ul>
 *   <li>{@link Source#CLARIFICATION_RESOLUTION} — the current run succeeded immediately after
 *       the prior run in the same conversation ended in a clarification request (Planner-level
 *       {@code CLARIFICATION_NEEDED} reasoning step, or a Stage-1/Decision-Router-level
 *       {@code decision_type = ASK_CLARIFICATION} run) — a deterministic, persisted signal,
 *       never inferred from question text.</li>
 *   <li>{@link Source#SEMANTIC_CORRECTION} — {@link CorrectionDetector} judged the current
 *       question a correction of the immediately prior answer.</li>
 * </ul>
 * A third, explicit source is added by Part B (/TeachZevra):
 * <ul>
 *   <li>{@link Source#EXPLICIT_TEACHING} — a user-submitted, LLM-structured, user-confirmed
 *       teaching proposal.</li>
 * </ul>
 *
 * <p>{@code resolvedConceptKeys} is carried verbatim from {@code ResolvedBusinessModel
 * .resolvedConceptKeys()} (or, for explicit teaching, the single user-selected/validated concept
 * key) — Java never derives or guesses a concept here. See {@link SemanticLearningService
 * #singleConceptKeyOrNull} for the deterministic collapse-to-null rule when more than one concept
 * key is present.
 */
public record LearningEvent(
        Source source,
        String questionOrAnswerText,
        String sql,
        String domainKey,
        List<String> resolvedConceptKeys,
        String runKey,
        String conversationId
) {
    public enum Source {
        CLARIFICATION_RESOLUTION,
        SEMANTIC_CORRECTION,
        EXPLICIT_TEACHING
    }
}
