package com.sei.nexus.semantic;

import com.sei.nexus.common.Keys;
import com.sei.nexus.reasoning.ReasoningRepository;
import com.sei.nexus.reasoning.ReasoningSession;
import com.sei.nexus.reasoning.ReasoningStep;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

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
    private final ReasoningRepository      reasoningRepository;

    // Package-private test seam: lets tests observe every LearningEvent this service processes
    // (see LearningEvent) without needing a real DB/TermExtractor. Production default is a no-op;
    // tests override via setLearningEventObserver(...). Hand-rolled fake convention — no Mockito.
    private Consumer<LearningEvent> learningEventObserver = e -> { };

    public SemanticLearningService(TermExtractor termExtractor,
                                   CorrectionDetector correctionDetector,
                                   LearnedMappingRepository mappingRepository,
                                   CorrectionRepository correctionRepository,
                                   RunRepository runRepository,
                                   TenantRepository tenantRepository,
                                   SemanticService semanticService,
                                   ReasoningRepository reasoningRepository) {
        this.termExtractor        = termExtractor;
        this.correctionDetector   = correctionDetector;
        this.mappingRepository    = mappingRepository;
        this.correctionRepository = correctionRepository;
        this.runRepository        = runRepository;
        this.tenantRepository     = tenantRepository;
        this.semanticService      = semanticService;
        this.reasoningRepository  = reasoningRepository;
    }

    /** Package-private test seam — see {@link #learningEventObserver}. */
    void setLearningEventObserver(Consumer<LearningEvent> observer) {
        this.learningEventObserver = observer != null ? observer : e -> { };
    }

    // ── Concept-Key Semantic Anchor design: deterministic evidence lookup ──────

    /**
     * Concept-scoped learned-knowledge evidence for Agent Brain: promoted mappings whose
     * concept_key exactly matches one of the given, already-resolved concept keys (see {@code
     * com.sei.nexus.agentbrain.ConceptScopedMetadataResolver#resolveConceptKeys} / {@code
     * ResolvedBusinessModel.resolvedConceptKeys()}). A thin, deterministic delegation to {@link
     * LearnedMappingRepository#findPromotedByConceptKeys} — exposed here rather than adding a new
     * {@code ChatService} constructor dependency, since {@code ChatService} already holds this
     * service. Exact concept_key equality only; no ranking, scoring, or selection among results —
     * the caller must render every returned mapping as evidence, never choose one itself.
     */
    public List<LearnedMapping> findPromotedByConceptKeys(List<String> conceptKeys) {
        return mappingRepository.findPromotedByConceptKeys(conceptKeys);
    }

    // ── Unified Learning Event pipeline ───────────────────────────────────────
    //
    // Replaces the old "every successful query with rows → learnFromRun()" trigger.
    // Exactly two implicit triggers now feed learning (see LearningEvent):
    //   1. CLARIFICATION_RESOLUTION — the current run succeeded AND the immediately-prior
    //      run in this conversation ended in a clarification request (see
    //      #isImmediatelyPriorRunAClarification).
    //   2. SEMANTIC_CORRECTION — CorrectionDetector judged this question a correction of the
    //      prior answer (unchanged mechanism, now also funneled through dispatch() below).
    // A plain successful query that is neither of these performs NO learning call at all —
    // not even a no-op TermExtractor invocation.

    /**
     * Deterministic, persisted-state check for "did the immediately-prior run in this
     * conversation end in a clarification request?" Never infers from question text.
     *
     * <p>Checks two independent, already-persisted signals, either of which counts:
     * <ol>
     *   <li>Planner-level: the prior run's reasoning session's LAST step has
     *       {@code evaluatorDecision == "CLARIFICATION_NEEDED"} (see
     *       {@code ReasoningEngine#reason} — the {@code break;} after this step guarantees
     *       it is the session's last step whenever it fires).</li>
     *   <li>Stage-1/Decision-Router-level: the prior run's own {@code decision_type ==
     *       "ASK_CLARIFICATION"} (see {@code ChatService}'s concept-ambiguity short-circuit
     *       and legacy Decision Router {@code ASK_CLARIFICATION} case, both of which persist
     *       this literal decision_type on {@code nexus_run}).</li>
     * </ol>
     * Documented scope decision: both signals are treated as equivalent "prior turn asked for
     * clarification" evidence — this is a superset of the Planner-level-only interpretation the
     * investigation started from, added because the Stage-1 signal is equally deterministic and
     * persisted (not text-inferred), not because it needed to be inferred or guessed.
     */
    public boolean isImmediatelyPriorRunAClarification(String conversationId, String currentRunKey) {
        if (conversationId == null || conversationId.isBlank()) return false;
        try {
            List<NexusRun> history = runRepository.findConversationRuns(conversationId, 3);
            NexusRun prior = null;
            for (int i = history.size() - 1; i >= 0; i--) {
                NexusRun r = history.get(i);
                if (!r.runKey().equals(currentRunKey)) {
                    prior = r;
                    break;
                }
            }
            if (prior == null) return false;

            if ("ASK_CLARIFICATION".equals(prior.decisionType())) {
                return true;
            }

            Optional<ReasoningSession> session = reasoningRepository.findSessionByRunKey(prior.runKey());
            if (session.isEmpty()) return false;
            List<ReasoningStep> steps = reasoningRepository.findStepsBySession(session.get().sessionKey());
            if (steps.isEmpty()) return false;
            ReasoningStep lastStep = steps.stream()
                    .max(Comparator.comparingInt(ReasoningStep::stepNo))
                    .orElse(null);
            return lastStep != null && "CLARIFICATION_NEEDED".equals(lastStep.evaluatorDecision());
        } catch (Exception e) {
            log.debug("Clarification-signal lookup failed for conversation {}: {}", conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * Deterministic concept-identity collapse rule for {@link LearnedMapping#conceptKey()}:
     * exactly one resolved concept key → persist it; zero, or more than one (no arbitrary
     * pick, no LLM disambiguation) → persist {@code null}. Java never chooses among multiple
     * resolved concepts on semantic grounds.
     */
    static String singleConceptKeyOrNull(List<String> resolvedConceptKeys) {
        if (resolvedConceptKeys == null || resolvedConceptKeys.isEmpty()) return null;
        List<String> distinct = resolvedConceptKeys.stream()
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .toList();
        return distinct.size() == 1 ? distinct.get(0) : null;
    }

    /**
     * Single dispatch point for every Learning Event (implicit and explicit). This is the ONLY
     * place {@link TermExtractor} is invoked for implicit learning, and it is invoked ONLY for
     * {@link LearningEvent.Source#CLARIFICATION_RESOLUTION} — never for a plain successful query,
     * and never for {@link LearningEvent.Source#SEMANTIC_CORRECTION} (that signal is already fully
     * handled by {@link #detectAndSaveCorrection} before this is called; routing it through here
     * too is purely for uniform observability). {@link LearningEvent.Source#EXPLICIT_TEACHING}
     * (Part B) is persisted directly by the Teaching flow — which already holds a fully-formed,
     * user-confirmed mapping and has no SQL to extract terms from — so it is not re-processed here;
     * this call exists so it too passes through the same observable pipeline.
     */
    @Async("semanticLearningExecutor")
    public void dispatch(LearningEvent event) {
        if (event == null) return;
        try {
            learningEventObserver.accept(event);
            if (event.source() == LearningEvent.Source.CLARIFICATION_RESOLUTION) {
                processClarificationResolution(event);
            }
        } catch (Exception e) {
            log.warn("SemanticLearningService.dispatch failed for run {}: {}",
                    event.runKey(), e.getMessage());
        }
    }

    private void processClarificationResolution(LearningEvent event) {
        if (event.questionOrAnswerText() == null || event.questionOrAnswerText().isBlank()
                || event.sql() == null || event.sql().isBlank()) {
            return;
        }
        com.sei.nexus.ai.OperationCorrelationId.set("CHAT:" + event.runKey());
        try {
            String conceptKey = singleConceptKeyOrNull(event.resolvedConceptKeys());
            List<TermExtractor.ExtractedTerm> terms =
                    termExtractor.extract(event.questionOrAnswerText(), event.sql());
            for (TermExtractor.ExtractedTerm t : terms) {
                try {
                    LearnedMapping mapping = new LearnedMapping(
                            null, event.domainKey(), t.term(), t.sql(),
                            event.runKey(), event.source().name(), 0.5, 1,
                            Instant.now(), false, null, null, conceptKey);
                    LearnedMapping saved = mappingRepository.upsert(mapping);
                    log.debug("Learned mapping upserted (clarification resolution): '{}' → '{}' (key: {})",
                            t.term(), truncate(t.sql(), 60), saved.mappingKey());
                } catch (Exception e) {
                    log.debug("Failed to save learned mapping '{}': {}", t.term(), e.getMessage());
                }
            }
        } finally {
            com.sei.nexus.ai.OperationCorrelationId.clear();
        }
    }

    /**
     * Runs the existing correction-detection mechanism (unchanged) against the prior conversation
     * turn and, when a correction is detected, additionally dispatches a
     * {@link LearningEvent.Source#SEMANTIC_CORRECTION} Learning Event so this trigger, too, is
     * observable through the unified pipeline. Public (not @Async) — called synchronously from
     * ChatService's post-answer learning block, same as before this refactor.
     */
    public void detectAndSaveCorrectionForRun(String runKey, String currentQuestion,
                                               String conversationId, String domainKey,
                                               List<String> resolvedConceptKeys) {
        boolean corrected = detectAndSaveCorrection(runKey, currentQuestion, conversationId, domainKey);
        if (corrected) {
            dispatch(new LearningEvent(LearningEvent.Source.SEMANTIC_CORRECTION,
                    currentQuestion, null, domainKey, resolvedConceptKeys, runKey, conversationId));
        }
    }

    // ── Signal 1b: validated literal binding (PRO-33 / PRO-32 D3) ────────────

    /**
     * Captures a literal binding the planner declared and the runtime validated
     * against a persisted Value Domain on a successful run — e.g. "TX" →
     * {@code state_province = 'Texas'}. The binding enters the existing
     * governed lifecycle as an ordinary {@link LearnedMapping}: repeat use
     * reinforces it (upsert: +0.05 confidence, +1 use), and only the nightly
     * maintenance may promote it to company vocabulary at the established
     * thresholds. <b>Never promotes directly.</b>
     */
    @Async("semanticLearningExecutor")
    public void captureLiteralBinding(String runKey, String domainKey,
                                      String surface, String column, String value) {
        if (surface == null || surface.isBlank()
                || column == null || column.isBlank()
                || value == null || value.isBlank()) {
            return;
        }
        try {
            // Bare column name in the pattern — same shape the term extractor
            // produces and the same predicate grammar vocabulary rows use.
            String bareColumn = column.contains(".")
                    ? column.substring(column.lastIndexOf('.') + 1) : column;
            String sqlPattern = bareColumn + " = '" + value.replace("'", "''") + "'";
            // Lowercased term for stable (domain_key, business_term) dedup;
            // BLR matching is case-insensitive, so recall is unaffected.
            LearnedMapping mapping = new LearnedMapping(
                    null, domainKey, surface.toLowerCase(java.util.Locale.ROOT), sqlPattern,
                    runKey, "LITERAL_RESOLUTION", 0.5, 1,
                    Instant.now(), false, null, null, null);
            LearnedMapping saved = mappingRepository.upsert(mapping);
            log.debug("Literal binding captured: '{}' → '{}' (key: {})",
                    surface, sqlPattern, saved.mappingKey());
        } catch (Exception e) {
            log.debug("Failed to capture literal binding '{}': {}", surface, e.getMessage());
        }
    }

    // ── Signal 2: user correction ─────────────────────────────────────────────

    /** @return true iff a correction was detected and saved (used to gate the SEMANTIC_CORRECTION
     *  Learning Event dispatch in {@link #detectAndSaveCorrectionForRun}). */
    private boolean detectAndSaveCorrection(String correctionRunKey, String currentQuestion,
                                             String conversationId, String domainKey) {
        try {
            List<NexusRun> history = runRepository.findConversationRuns(conversationId, 3);
            if (history.size() < 2) return false;

            // Most recent run is the current one; check the one before it
            NexusRun prior = null;
            for (NexusRun r : history) {
                if (!r.runKey().equals(correctionRunKey) && r.answer() != null) {
                    prior = r;
                    break;
                }
            }
            if (prior == null) return false;

            Optional<CorrectionDetector.DetectedCorrection> detected =
                    correctionDetector.detect(currentQuestion, prior.question(), prior.answer());

            if (detected.isEmpty()) return false;

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
            return true;
        } catch (Exception e) {
            log.debug("Correction detection failed: {}", e.getMessage());
            return false;
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
    @Async("semanticLearningExecutor")
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
