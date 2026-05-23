package com.sei.nexus.semantic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds a compact learning context string that is injected into the
 * SQL planner's schema context before every QUERY_LIVE_DATA run.
 *
 * <p>This is the mechanism that makes Zevra progressively smarter:
 * as the team uses it, their business vocabulary is learned and fed back
 * into the planner so future queries use correct terminology automatically.
 *
 * <p>Example output (appended to the schema context string):
 * <pre>
 * Business vocabulary learned from this team's usage:
 * - "overdue invoice" means: due_date < CURRENT_DATE AND status NOT IN ('PAID','VOID')  (used 14×, confidence 0.91)
 * - "active account" means: last_activity_at > NOW() - INTERVAL '30 days'  (used 9×, confidence 0.78)
 *
 * Known corrections for this team:
 * - "this week" means Monday–Sunday (not rolling 7 days)
 * </pre>
 *
 * <p>Returns an empty string when no learnings are available, so callers
 * don't need to guard against null.
 */
@Component
public class LearningContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(LearningContextBuilder.class);

    // Minimum quality bar to include a mapping in planner context
    private static final double MIN_CONFIDENCE = 0.55;
    private static final int    MIN_USES       = 2;
    private static final int    MAX_TERMS      = 10;
    private static final int    MAX_CORRECTIONS = 3;

    private final LearnedMappingRepository mappingRepository;
    private final CorrectionRepository     correctionRepository;

    public LearningContextBuilder(LearnedMappingRepository mappingRepository,
                                  CorrectionRepository correctionRepository) {
        this.mappingRepository    = mappingRepository;
        this.correctionRepository = correctionRepository;
    }

    public record LearningContext(
            String contextText,
            List<String> termsApplied   // for the ChatResponse learnings_applied field
    ) {
        public boolean isEmpty() { return contextText.isBlank(); }
    }

    /**
     * Builds the learning context for the given domain.
     *
     * @param domainKey      Agent's domain key; null to use cross-domain learnings only.
     * @param conversationId Used to load conversation-specific corrections.
     * @return A {@link LearningContext} with the text to inject and the terms applied.
     */
    public LearningContext build(String domainKey, String conversationId) {
        try {
            List<LearnedMapping> mappings = mappingRepository.findForContextInjection(
                    domainKey, MIN_CONFIDENCE, MIN_USES, MAX_TERMS);

            List<Correction> corrections = (conversationId != null && !conversationId.isBlank())
                    ? correctionRepository.findRecentForConversation(conversationId, MAX_CORRECTIONS)
                    : List.of();

            if (mappings.isEmpty() && corrections.isEmpty()) {
                return new LearningContext("", List.of());
            }

            StringBuilder sb = new StringBuilder();

            if (!mappings.isEmpty()) {
                sb.append("Business vocabulary learned from this team's usage:\n");
                for (LearnedMapping m : mappings) {
                    sb.append(String.format("- \"%s\" means: %s  (used %d×, confidence %.2f)%n",
                            m.businessTerm(),
                            m.sqlPattern(),
                            m.useCount(),
                            m.confidence()));
                }
            }

            if (!corrections.isEmpty()) {
                sb.append("\nKnown corrections for this team:\n");
                for (Correction c : corrections) {
                    if (c.correctedInterpretation() != null && !c.correctedInterpretation().isBlank()) {
                        sb.append(String.format("- \"%s\" was wrong; correct meaning: %s%n",
                                c.originalInterpretation(), c.correctedInterpretation()));
                    }
                }
            }

            List<String> termsApplied = mappings.stream()
                    .map(LearnedMapping::businessTerm)
                    .toList();

            return new LearningContext(sb.toString().trim(), termsApplied);
        } catch (Exception e) {
            log.debug("LearningContextBuilder.build failed: {}", e.getMessage());
            return new LearningContext("", List.of());
        }
    }
}
