package com.sei.nexus.semantic;

import com.sei.nexus.common.Keys;
import com.sei.nexus.run.NexusRun;
import com.sei.nexus.run.RunRepository;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates all three semantic learning signals:
 *
 * <ol>
 *   <li><b>Query success</b> — called @Async after every successful QUERY_LIVE_DATA run.
 *       Extracts business term → SQL pairs via {@link TermExtractor}.</li>
 *   <li><b>User correction</b> — detected via {@link CorrectionDetector} using the
 *       current + prior conversation turns.  Penalises the related learned mapping.</li>
 *   <li><b>Positive feedback</b> — called when the user gives thumbs-up.
 *       Reinforces any learned mappings associated with that run's SQL.</li>
 * </ol>
 *
 * <h3>Nightly maintenance (02:45)</h3>
 * <ul>
 *   <li>use_count ≥ 10 AND confidence ≥ 0.8 → {@code promoted = true},
 *       creates a formal {@code nexus_operational_vocabulary} entry.</li>
 *   <li>use_count ≥ 5 AND confidence &lt; 0.2 → deleted (never reinforced).</li>
 * </ul>
 *
 * <p>All public methods are fire-and-forget — exceptions are logged and swallowed.
 * Learning must never block the user-facing response.
 */
@Service
public class SemanticLearningService {

    private static final Logger log = LoggerFactory.getLogger(SemanticLearningService.class);

    // Confidence thresholds for nightly maintenance
    private static final int    PROMOTE_MIN_USES        = 10;
    private static final double PROMOTE_MIN_CONFIDENCE  = 0.80;
    private static final int    PURGE_MIN_USES          = 5;
    private static final double PURGE_MAX_CONFIDENCE    = 0.20;

    private final TermExtractor            termExtractor;
    private final CorrectionDetector       correctionDetector;
    private final LearnedMappingRepository mappingRepository;
    private final CorrectionRepository     correctionRepository;
    private final RunRepository            runRepository;
    private final TenantRepository         tenantRepository;
    private final SemanticService          semanticService;   // for promotion to vocabulary

    public SemanticLearningService(TermExtractor termExtractor,
                                   CorrectionDetector correctionDetector,
                                   LearnedMappingRepository mappingRepository,
                                   CorrectionRepository correctionRepository,
                                   RunRepository runRepository,
                                   TenantRepository tenantRepository,
                                   SemanticService semanticService) {
        this.termExtractor        = termExtractor;
        this.correctionDetector   = correctionDetector;
        this.mappingRepository    = mappingRepository;
        this.correctionRepository = correctionRepository;
        this.runRepository        = runRepository;
        this.tenantRepository     = tenantRepository;
        this.semanticService      = semanticService;
    }

    // ── Signal 1: query success ───────────────────────────────────────────────

    /**
     * Called @Async after every successful QUERY_LIVE_DATA run.
     *
     * @param runKey         The run that just completed.
     * @param question       The user's raw question (no attachment content).
     * @param executedSql    The best SQL step that ran successfully.
     * @param domainKey      Agent's domain key (may be null).
     * @param conversationId Used for correction detection against the prior turn.
     */
    @Async
    public void learnFromRun(String runKey, String question, String executedSql,
                              String domainKey, String conversationId) {
        if (question == null || question.isBlank() || executedSql == null || executedSql.isBlank()) {
            return;
        }
        try {
            // 1a. Extract business terms from this run
            List<TermExtractor.ExtractedTerm> terms = termExtractor.extract(question, executedSql);
            for (TermExtractor.ExtractedTerm t : terms) {
                try {
                    LearnedMapping mapping = new LearnedMapping(
                            null, domainKey, t.term(), t.sql(),
                            runKey, "QUERY_SUCCESS", 0.5, 1,
                            Instant.now(), false, null, null);
                    LearnedMapping saved = mappingRepository.upsert(mapping);
                    log.debug("Learned mapping upserted: '{}' → '{}' (key: {})",
                            t.term(), truncate(t.sql(), 60), saved.mappingKey());
                } catch (Exception e) {
                    log.debug("Failed to save learned mapping '{}': {}", t.term(), e.getMessage());
                }
            }

            // 1b. Check if this question corrects the immediately prior answer
            if (conversationId != null && !conversationId.isBlank()) {
                detectAndSaveCorrection(runKey, question, conversationId, domainKey);
            }
        } catch (Exception e) {
            log.warn("SemanticLearningService.learnFromRun failed for run {}: {}", runKey, e.getMessage());
        }
    }

    // ── Signal 2: user correction ─────────────────────────────────────────────

    private void detectAndSaveCorrection(String correctionRunKey, String currentQuestion,
                                          String conversationId, String domainKey) {
        try {
            List<NexusRun> history = runRepository.findConversationRuns(conversationId, 3);
            if (history.size() < 2) return;

            // Most recent run is the current one; check the one before it
            NexusRun prior = null;
            for (NexusRun r : history) {
                if (!r.runKey().equals(correctionRunKey) && r.answer() != null) {
                    prior = r;
                    break;
                }
            }
            if (prior == null) return;

            Optional<CorrectionDetector.DetectedCorrection> detected =
                    correctionDetector.detect(currentQuestion, prior.question(), prior.answer());

            if (detected.isEmpty()) return;

            CorrectionDetector.DetectedCorrection dc = detected.get();
            Correction correction = new Correction(
                    Keys.uniqueKey("corr"),
                    conversationId,
                    prior.runKey(),
                    correctionRunKey,
                    dc.originalInterpretation(),
                    dc.correctedInterpretation(),
                    dc.correctionType(),
                    false,
                    Instant.now());
            correctionRepository.save(correction);

            // Penalise any learned mapping whose business_term appears in the original interpretation
            if (dc.originalInterpretation() != null && !dc.originalInterpretation().isBlank()) {
                penaliseMappingsRelatedTo(dc.originalInterpretation(), domainKey);
            }
            log.info("Correction recorded ({}) for conversation '{}'",
                    dc.correctionType(), conversationId);
        } catch (Exception e) {
            log.debug("Correction detection failed: {}", e.getMessage());
        }
    }

    private void penaliseMappingsRelatedTo(String originalInterpretation, String domainKey) {
        List<LearnedMapping> candidates = mappingRepository.findForDomain(domainKey);
        String lower = originalInterpretation.toLowerCase();
        for (LearnedMapping m : candidates) {
            if (lower.contains(m.businessTerm().toLowerCase())) {
                mappingRepository.penalise(m.mappingKey());
                log.debug("Penalised mapping '{}' due to correction", m.businessTerm());
            }
        }
    }

    // ── Signal 3: positive feedback ───────────────────────────────────────────

    /**
     * Reinforces learned mappings associated with a run the user rated positively.
     * Called from ChatController when a thumbs-up feedback is received.
     */
    @Async
    public void reinforceFromFeedback(String runKey, String domainKey) {
        try {
            Optional<NexusRun> run = runRepository.findByKey(runKey);
            if (run.isEmpty() || run.get().resultSnapshot() == null) return;

            // The question that this run answered is used to find related learned terms
            String question = run.get().question();
            if (question == null || question.isBlank()) return;

            List<LearnedMapping> candidates = mappingRepository.findForDomain(domainKey);
            String lowerQ = question.toLowerCase();
            for (LearnedMapping m : candidates) {
                if (lowerQ.contains(m.businessTerm().toLowerCase())) {
                    mappingRepository.reinforce(m.mappingKey());
                    log.debug("Reinforced mapping '{}' from positive feedback", m.businessTerm());
                }
            }
        } catch (Exception e) {
            log.debug("SemanticLearningService.reinforceFromFeedback failed: {}", e.getMessage());
        }
    }

    // ── Nightly maintenance: promote + purge ─────────────────────────────────

    /**
     * Runs at 02:45 across all active tenant schemas.
     *
     * <p>Promotion: use_count ≥ 10 AND confidence ≥ 0.80 → promoted = true
     *    and a formal nexus_operational_vocabulary entry is created.
     * <p>Purging: use_count ≥ 5 AND confidence ≤ 0.20 → deleted.
     */
    @Scheduled(cron = "0 45 2 * * *")
    public void runMaintenanceAcrossTenants() {
        List<String> schemas = new ArrayList<>();
        schemas.add(TenantContext.PUBLIC_SCHEMA);
        try {
            tenantRepository.findAll().stream()
                    .filter(t -> "ACTIVE".equals(t.status()))
                    .map(t -> t.schemaName())
                    .forEach(schemas::add);
        } catch (Exception e) {
            log.warn("Could not load tenant list for semantic learning maintenance: {}", e.getMessage());
        }

        for (String schema : schemas) {
            TenantContext.set(schema);
            try {
                runMaintenanceForCurrentSchema();
            } catch (Exception e) {
                log.warn("Semantic learning maintenance failed for schema '{}': {}", schema, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void runMaintenanceForCurrentSchema() {
        // Promote high-confidence terms to formal vocabulary
        List<LearnedMapping> toPromote =
                mappingRepository.findPromotionCandidates(PROMOTE_MIN_USES, PROMOTE_MIN_CONFIDENCE);
        for (LearnedMapping m : toPromote) {
            try {
                // Create a formal vocabulary entry
                semanticService.createTerm(Map.of(
                        "domainKey",   m.domainKey() != null ? m.domainKey() : "",
                        "term",        m.businessTerm(),
                        "definition",  "Learned from team usage: maps to SQL pattern — " + m.sqlPattern(),
                        "sql_equivalent", m.sqlPattern(),
                        "status",      "ACTIVE"));
                mappingRepository.markPromoted(m.mappingKey());
                log.info("Promoted learned term '{}' to vocabulary (confidence={}, uses={})",
                        m.businessTerm(), String.format("%.2f", m.confidence()), m.useCount());
            } catch (Exception e) {
                log.debug("Could not promote term '{}': {}", m.businessTerm(), e.getMessage());
            }
        }

        // Purge terms that were never meaningfully reinforced
        List<LearnedMapping> toPurge =
                mappingRepository.findPurgeCandidates(PURGE_MIN_USES, PURGE_MAX_CONFIDENCE);
        for (LearnedMapping m : toPurge) {
            try {
                mappingRepository.delete(m.mappingKey());
                log.debug("Purged low-confidence term '{}' (confidence={}, uses={})",
                        m.businessTerm(), String.format("%.2f", m.confidence()), m.useCount());
            } catch (Exception e) {
                log.debug("Could not purge term '{}': {}", m.businessTerm(), e.getMessage());
            }
        }

        if (!toPromote.isEmpty() || !toPurge.isEmpty()) {
            log.info("Semantic learning maintenance: {} promoted, {} purged",
                    toPromote.size(), toPurge.size());
        }
    }

    private String truncate(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
