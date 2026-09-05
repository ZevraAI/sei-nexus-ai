package com.sei.nexus.semantic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds a compact context string of recent conversation corrections, injected into the SQL
 * planner's schema context before every QUERY_LIVE_DATA run.
 *
 * <p>Example output (appended to the schema context string):
 * <pre>
 * Known corrections for this team:
 * - "this week" means Monday–Sunday (not rolling 7 days)
 * </pre>
 *
 * <p>Returns an empty string when no corrections are available, so callers don't need to guard
 * against null.
 *
 * <p><b>What this class no longer does.</b> It used to also inject promoted/eligible learned-
 * mapping vocabulary from {@code nexus_learned_mapping} into this same prompt — that responsibility
 * has been removed entirely (this class has no {@link LearnedMappingRepository} dependency at
 * all). Promoted learning vocabulary now reaches the LLM exclusively through native File Search
 * over the tenant's OpenAI Vector Store: an admin-classified promoted learning is folded into its
 * concept's Vector Store document by {@code ConceptKnowledgeMaterializationService} and kept
 * converged there by {@code ConceptKnowledgeSynchronizationService}. Postgres remains authoritative
 * for the learning lifecycle, but Java no longer reads that table into any LLM prompt.
 */
@Component
public class LearningContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(LearningContextBuilder.class);

    private static final int MAX_CORRECTIONS = 3;

    private final CorrectionRepository correctionRepository;

    public LearningContextBuilder(CorrectionRepository correctionRepository) {
        this.correctionRepository = correctionRepository;
    }

    public record LearningContext(String contextText) {
        public boolean isEmpty() { return contextText.isBlank(); }
    }

    /**
     * Builds the correction context for the given conversation.
     *
     * @param conversationId Used to load conversation-specific corrections.
     * @return A {@link LearningContext} with the text to inject.
     */
    public LearningContext build(String conversationId) {
        try {
            List<Correction> corrections = (conversationId != null && !conversationId.isBlank())
                    ? correctionRepository.findRecentForConversation(conversationId, MAX_CORRECTIONS)
                    : List.of();

            if (corrections.isEmpty()) {
                return new LearningContext("");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Known corrections for this team:\n");
            for (Correction c : corrections) {
                if (c.correctedInterpretation() != null && !c.correctedInterpretation().isBlank()) {
                    sb.append(String.format("- \"%s\" was wrong; correct meaning: %s%n",
                            c.originalInterpretation(), c.correctedInterpretation()));
                }
            }

            return new LearningContext(sb.toString().trim());
        } catch (Exception e) {
            log.debug("LearningContextBuilder.build failed: {}", e.getMessage());
            return new LearningContext("");
        }
    }
}
