package com.sei.nexus.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.run.RunRepository;
import com.sei.nexus.tenant.TenantRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the TenantContext-loss fix changed ONLY where {@link SemanticLearningService}'s
 * {@code @Async} methods execute — never what they do. Hand-rolled fakes throughout (no Mockito,
 * no database, no network) — same convention as this repo's other service-logic tests. Every
 * method is called directly (bypassing Spring's {@code @Async} proxy entirely, exactly like
 * production code would when unit-tested), so these tests exercise the exact same business logic
 * {@code TermExtractor} → {@code mappingRepository.upsert} / {@code
 * correctionDetector.detect} → {@code correctionRepository.save} that existed before this fix.
 */
class SemanticLearningServiceLogicTest {

    static class FakeTermExtractor extends TermExtractor {
        List<ExtractedTerm> scripted = List.of();
        String lastQuestion, lastSql;
        FakeTermExtractor() { super(null, new ObjectMapper()); }
        @Override public List<ExtractedTerm> extract(String question, String sql) {
            lastQuestion = question; lastSql = sql;
            return scripted;
        }
    }

    static class FakeCorrectionDetector extends CorrectionDetector {
        Optional<DetectedCorrection> scripted = Optional.empty();
        FakeCorrectionDetector() { super(null, new ObjectMapper()); }
        @Override public Optional<DetectedCorrection> detect(String currentQuestion, String priorQuestion, String priorAnswer) {
            return scripted;
        }
    }

    static class FakeLearnedMappingRepository extends LearnedMappingRepository {
        List<LearnedMapping> upserted = new ArrayList<>();
        List<String> penalised = new ArrayList<>();
        List<String> reinforced = new ArrayList<>();
        FakeLearnedMappingRepository() { super(null); }
        @Override public LearnedMapping upsert(LearnedMapping m) {
            LearnedMapping saved = new LearnedMapping(
                    m.mappingKey() != null ? m.mappingKey() : "lmap-fake-" + upserted.size(),
                    m.domainKey(), m.businessTerm(), m.sqlPattern(), m.sourceRunKey(), m.source(),
                    m.confidence(), m.useCount(), m.lastUsedAt(), m.promoted(), m.createdAt(), m.updatedAt(),
                    m.conceptKey());
            upserted.add(saved);
            return saved;
        }
        @Override public List<LearnedMapping> findForDomain(String domainKey) { return List.copyOf(upserted); }
        @Override public void penalise(String mappingKey) { penalised.add(mappingKey); }
        @Override public void reinforce(String mappingKey) { reinforced.add(mappingKey); }
    }

    static class FakeCorrectionRepository extends CorrectionRepository {
        List<Correction> saved = new ArrayList<>();
        FakeCorrectionRepository() { super(null); }
        @Override public Correction save(Correction c) { saved.add(c); return c; }
    }

    static class FakeRunRepository extends RunRepository {
        Optional<com.sei.nexus.run.NexusRun> scriptedRun = Optional.empty();
        FakeRunRepository() { super(null); }
        @Override public List<com.sei.nexus.run.NexusRun> findConversationRuns(String conversationId, int limit) {
            return List.of(); // no prior turns — correction detection short-circuits (size < 2)
        }
        @Override public Optional<com.sei.nexus.run.NexusRun> findByKey(String runKey) {
            return scriptedRun;
        }
    }

    private FakeTermExtractor termExtractor;
    private FakeCorrectionDetector correctionDetector;
    private FakeLearnedMappingRepository mappingRepository;
    private FakeCorrectionRepository correctionRepository;
    private FakeRunRepository runRepository;
    private SemanticLearningService service;

    private void setUp() {
        termExtractor = new FakeTermExtractor();
        correctionDetector = new FakeCorrectionDetector();
        mappingRepository = new FakeLearnedMappingRepository();
        correctionRepository = new FakeCorrectionRepository();
        runRepository = new FakeRunRepository();
        service = new SemanticLearningService(termExtractor, correctionDetector, mappingRepository,
                correctionRepository, runRepository, new TenantRepository(null), null,
                new com.sei.nexus.reasoning.ReasoningRepository(null));
    }

    // ── TermExtractor → upsert (Signal 1, now CLARIFICATION_RESOLUTION) — the exact
    //    clarification-answer scenario, dispatched as a Learning Event ─────────────

    @Test
    void clarificationResolutionExtractsAndPersistsTheExactClarificationMapping() {
        setUp();
        termExtractor.scripted = List.of(new TermExtractor.ExtractedTerm(
                "open", "status IN ('submitted', 'acknowledged', 'partially_received')"));

        service.dispatch(new LearningEvent(LearningEvent.Source.CLARIFICATION_RESOLUTION,
                "open means status in submitted, acknowledged, partially_received",
                "SELECT ... WHERE status IN ('submitted', 'acknowledged', 'partially_received')",
                "PLATFORM", List.of(), "run-1", null));

        assertEquals(1, mappingRepository.upserted.size());
        LearnedMapping saved = mappingRepository.upserted.get(0);
        assertEquals("open", saved.businessTerm());
        assertEquals("status IN ('submitted', 'acknowledged', 'partially_received')", saved.sqlPattern());
        assertEquals("CLARIFICATION_RESOLUTION", saved.source());
        assertEquals(0.5, saved.confidence());
        assertEquals(1, saved.useCount());
    }

    @Test
    void clarificationResolutionPersistsNothingWhenTermExtractorFindsNoTerms() {
        setUp();
        termExtractor.scripted = List.of(); // the "no meaningful terms" case

        service.dispatch(new LearningEvent(LearningEvent.Source.CLARIFICATION_RESOLUTION,
                "show me all purchase orders", "SELECT * FROM purchase_orders",
                "PLATFORM", List.of(), "run-1", null));

        assertEquals(0, mappingRepository.upserted.size());
    }

    @Test
    void clarificationResolutionDoesNothingForBlankQuestionOrSql() {
        setUp();
        service.dispatch(new LearningEvent(LearningEvent.Source.CLARIFICATION_RESOLUTION,
                "", "SELECT 1", "PLATFORM", List.of(), "run-1", null));
        service.dispatch(new LearningEvent(LearningEvent.Source.CLARIFICATION_RESOLUTION,
                "q", "", "PLATFORM", List.of(), "run-1", null));
        assertEquals(0, mappingRepository.upserted.size());
        assertNull(termExtractor.lastQuestion, "TermExtractor must not even be called for blank input");
    }

    @Test
    void plainSuccessfulQueryNeverInvokesTermExtractor() {
        setUp();
        // A SEMANTIC_CORRECTION event (or no event at all) must never reach TermExtractor —
        // only CLARIFICATION_RESOLUTION (and, in Part B, EXPLICIT_TEACHING) do.
        service.dispatch(new LearningEvent(LearningEvent.Source.SEMANTIC_CORRECTION,
                "show me all purchase orders", "SELECT * FROM purchase_orders",
                "PLATFORM", List.of(), "run-1", "conv-1"));
        assertNull(termExtractor.lastQuestion, "TermExtractor must not be invoked for a plain/correction event");
        assertEquals(0, mappingRepository.upserted.size());
    }

    // ── captureLiteralBinding (Signal 1b) ────────────────────────────────────────────────────────

    @Test
    void captureLiteralBindingBuildsTheExpectedSqlPatternAndPersists() {
        setUp();
        service.captureLiteralBinding("run-1", "PLATFORM", "TX", "stores.state_province", "Texas");

        assertEquals(1, mappingRepository.upserted.size());
        LearnedMapping saved = mappingRepository.upserted.get(0);
        assertEquals("tx", saved.businessTerm(), "surface is lowercased for stable dedup");
        assertEquals("state_province = 'Texas'", saved.sqlPattern(), "bare column name, matching TermExtractor's own shape");
        assertEquals("LITERAL_RESOLUTION", saved.source());
    }

    @Test
    void captureLiteralBindingSkipsWhenAnyFieldIsBlank() {
        setUp();
        service.captureLiteralBinding("run-1", "PLATFORM", "", "col", "val");
        service.captureLiteralBinding("run-1", "PLATFORM", "surface", "", "val");
        service.captureLiteralBinding("run-1", "PLATFORM", "surface", "col", "");
        assertEquals(0, mappingRepository.upserted.size());
    }

    // ── Correction detection (Signal 2) — now reached via detectAndSaveCorrectionForRun ──────────

    @Test
    void correctionDetectionIsSkippedWhenConversationIdIsNull() {
        setUp();
        termExtractor.scripted = List.of();
        // ChatService only calls detectAndSaveCorrectionForRun when conversationId is non-blank;
        // this asserts the (blank) case is a no-op guarded at the call site, and even when called
        // with a blank id it does nothing.
        service.detectAndSaveCorrectionForRun("run-1", "q", "", "PLATFORM", List.of());
        assertEquals(0, correctionRepository.saved.size(), "no conversationId ⇒ correction detection never runs");
    }

    // ── reinforceFromFeedback (Signal 3) — logic unchanged by the executor-only fix ────────────────

    @Test
    void reinforceFromFeedbackReinforcesEveryMappingWhoseTermAppearsInTheRunsQuestion() {
        setUp();
        mappingRepository.upsert(new LearnedMapping(null, "PLATFORM", "open",
                "status IN ('submitted', 'acknowledged')", "run-1", "QUERY_SUCCESS",
                0.5, 1, java.time.Instant.now(), false, null, null, null));
        mappingRepository.upsert(new LearnedMapping(null, "PLATFORM", "closed",
                "status = 'closed'", "run-1", "QUERY_SUCCESS",
                0.5, 1, java.time.Instant.now(), false, null, null, null));
        runRepository.scriptedRun = Optional.of(new com.sei.nexus.run.NexusRun(
                "run-1", "conv-1", "agent-1", "PLATFORM", "user@example.com",
                "show me all open purchase orders", "answer", "QUERY_LIVE_DATA", "OK",
                "{}", null, null));

        service.reinforceFromFeedback("run-1", "PLATFORM");

        assertEquals(1, mappingRepository.reinforced.size());
        assertEquals(mappingRepository.upserted.get(0).mappingKey(), mappingRepository.reinforced.get(0),
                "only the mapping whose business_term appears in the question is reinforced");
    }

    @Test
    void reinforceFromFeedbackReinforcesNothingWhenNoTermMatches() {
        setUp();
        mappingRepository.upsert(new LearnedMapping(null, "PLATFORM", "closed",
                "status = 'closed'", "run-1", "QUERY_SUCCESS",
                0.5, 1, java.time.Instant.now(), false, null, null, null));
        runRepository.scriptedRun = Optional.of(new com.sei.nexus.run.NexusRun(
                "run-1", "conv-1", "agent-1", "PLATFORM", "user@example.com",
                "show me all open purchase orders", "answer", "QUERY_LIVE_DATA", "OK",
                "{}", null, null));

        service.reinforceFromFeedback("run-1", "PLATFORM");

        assertEquals(0, mappingRepository.reinforced.size());
    }

    @Test
    void reinforceFromFeedbackDoesNothingWhenRunIsMissingOrHasNoResultSnapshot() {
        setUp();
        runRepository.scriptedRun = Optional.empty();
        service.reinforceFromFeedback("run-missing", "PLATFORM");
        assertEquals(0, mappingRepository.reinforced.size());

        runRepository.scriptedRun = Optional.of(new com.sei.nexus.run.NexusRun(
                "run-1", "conv-1", "agent-1", "PLATFORM", "user@example.com",
                "open orders", "answer", "QUERY_LIVE_DATA", "OK",
                null /* no resultSnapshot */, null, null));
        service.reinforceFromFeedback("run-1", "PLATFORM");
        assertEquals(0, mappingRepository.reinforced.size());
    }
}
