package com.sei.nexus.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.reasoning.ReasoningRepository;
import com.sei.nexus.reasoning.ReasoningSession;
import com.sei.nexus.reasoning.ReasoningStep;
import com.sei.nexus.run.NexusRun;
import com.sei.nexus.run.RunRepository;
import com.sei.nexus.tenant.TenantRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Part A — Implicit Learning. Proves the unified Learning Event pipeline's exactly-two-triggers
 * contract at the {@link SemanticLearningService} level (the same gating calls {@code ChatService}
 * makes — see its post-answer learning block): a plain successful query performs no learning call
 * at all, CLARIFICATION_RESOLUTION and SEMANTIC_CORRECTION each fire correctly from deterministic,
 * persisted signals only, and concept_key propagation follows the documented single-vs-multiple
 * collapse rule. Hand-rolled fakes throughout — no Mockito, no database.
 */
class ImplicitLearningTriggerTest {

    // ── fakes ────────────────────────────────────────────────────────────────────────────────

    static class FakeTermExtractor extends TermExtractor {
        List<ExtractedTerm> scripted = List.of();
        int invocationCount = 0;
        String lastQuestion;
        FakeTermExtractor() { super(null, new ObjectMapper()); }
        @Override public List<ExtractedTerm> extract(String question, String sql) {
            invocationCount++;
            lastQuestion = question;
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
        final List<LearnedMapping> upserted = new ArrayList<>();
        FakeLearnedMappingRepository() { super(null); }
        @Override public LearnedMapping upsert(LearnedMapping m) {
            LearnedMapping saved = new LearnedMapping(
                    m.mappingKey() != null ? m.mappingKey() : "lmap-" + upserted.size(),
                    m.domainKey(), m.businessTerm(), m.sqlPattern(), m.sourceRunKey(), m.source(),
                    m.confidence(), m.useCount(), m.lastUsedAt(), m.promoted(), m.createdAt(),
                    m.updatedAt(), m.conceptKey());
            upserted.add(saved);
            return saved;
        }
        @Override public List<LearnedMapping> findForDomain(String domainKey) { return List.copyOf(upserted); }
    }

    static class FakeCorrectionRepository extends CorrectionRepository {
        final List<Correction> saved = new ArrayList<>();
        FakeCorrectionRepository() { super(null); }
        @Override public Correction save(Correction c) { saved.add(c); return c; }
    }

    static class FakeRunRepository extends RunRepository {
        List<NexusRun> scriptedHistory = List.of();
        FakeRunRepository() { super(null); }
        @Override public List<NexusRun> findConversationRuns(String conversationId, int limit) {
            return scriptedHistory;
        }
    }

    static class FakeReasoningRepository extends ReasoningRepository {
        Optional<ReasoningSession> scriptedSession = Optional.empty();
        List<ReasoningStep> scriptedSteps = List.of();
        FakeReasoningRepository() { super(null); }
        @Override public Optional<ReasoningSession> findSessionByRunKey(String runKey) { return scriptedSession; }
        @Override public List<ReasoningStep> findStepsBySession(String sessionKey) { return scriptedSteps; }
    }

    private FakeTermExtractor termExtractor;
    private FakeCorrectionDetector correctionDetector;
    private FakeLearnedMappingRepository mappingRepository;
    private FakeCorrectionRepository correctionRepository;
    private FakeRunRepository runRepository;
    private FakeReasoningRepository reasoningRepository;
    private SemanticLearningService service;
    private final List<LearningEvent> observedEvents = new ArrayList<>();

    private void setUp() {
        termExtractor = new FakeTermExtractor();
        correctionDetector = new FakeCorrectionDetector();
        mappingRepository = new FakeLearnedMappingRepository();
        correctionRepository = new FakeCorrectionRepository();
        runRepository = new FakeRunRepository();
        reasoningRepository = new FakeReasoningRepository();
        observedEvents.clear();
        service = new SemanticLearningService(termExtractor, correctionDetector, mappingRepository,
                correctionRepository, runRepository, new TenantRepository(null), null, reasoningRepository);
        service.setLearningEventObserver(observedEvents::add);
    }

    private NexusRun run(String runKey, String question, String answer, String decisionType) {
        return new NexusRun(runKey, "conv-1", "agent-1", "PLATFORM", "user@example.com",
                question, answer, decisionType, "COMPLETE", "{}", null, null);
    }

    /** Mirrors exactly what ChatService's post-answer block does: check trigger 1, then trigger 2. */
    private void simulateChatServicePostAnswerBlock(String runKey, String question, String sql,
                                                      List<String> resolvedConceptKeys) {
        if (service.isImmediatelyPriorRunAClarification("conv-1", runKey)) {
            service.dispatch(new LearningEvent(LearningEvent.Source.CLARIFICATION_RESOLUTION,
                    question, sql, "PLATFORM", resolvedConceptKeys, runKey, "conv-1"));
        }
        service.detectAndSaveCorrectionForRun(runKey, question, "conv-1", "PLATFORM", resolvedConceptKeys);
    }

    // ── Test 1: normal successful query → TermExtractor NOT invoked ────────────────────────────

    @Test
    void normalSuccessfulQueryNeverInvokesTermExtractor() {
        setUp();
        runRepository.scriptedHistory = List.of(
                run("run-0", "show me all vendors", "here are the vendors", "QUERY_LIVE_DATA"),
                run("run-1", "show me all purchase orders", "here they are", "QUERY_LIVE_DATA"));
        correctionDetector.scripted = Optional.empty();

        simulateChatServicePostAnswerBlock("run-1", "show me all purchase orders",
                "SELECT * FROM purchase_orders", List.of());

        assertEquals(0, termExtractor.invocationCount, "TermExtractor must not even be called");
        assertEquals(0, mappingRepository.upserted.size());
        assertTrue(observedEvents.isEmpty(), "no Learning Event should be dispatched for a plain successful query");
    }

    // ── Test 2: clarification resolution → Learning Event, TermExtractor invoked, persisted ────

    @Test
    void clarificationResolutionDispatchesLearningEventAndPersistsPreservedConceptKey() {
        setUp();
        String priorRunKey = "run-1";
        String currentRunKey = "run-2";
        runRepository.scriptedHistory = List.of(
                run(priorRunKey, "show me open orders", null, "QUERY_LIVE_DATA"),
                run(currentRunKey, "open means status in submitted, acknowledged", "here they are", "QUERY_LIVE_DATA"));
        reasoningRepository.scriptedSession = Optional.of(new ReasoningSession(
                "sess-1", priorRunKey, "conv-1", "agent-1", "PLATFORM",
                "show me open orders", null, "CONCLUDED", null, null, Instant.now(), Instant.now()));
        reasoningRepository.scriptedSteps = List.of(
                new ReasoningStep("step-1", "sess-1", 1, "QUERY", "investigate", null, "",
                        null, null, Instant.now(), "CLARIFICATION_NEEDED", "ambiguous term 'open'"));
        termExtractor.scripted = List.of(new TermExtractor.ExtractedTerm(
                "open", "status IN ('submitted', 'acknowledged')"));

        simulateChatServicePostAnswerBlock(currentRunKey,
                "open means status in submitted, acknowledged",
                "SELECT ... WHERE status IN ('submitted', 'acknowledged')",
                List.of("purchase-order-status"));

        assertEquals(1, termExtractor.invocationCount, "TermExtractor must be invoked for a clarification resolution");
        assertEquals(1, mappingRepository.upserted.size());
        LearnedMapping saved = mappingRepository.upserted.get(0);
        assertEquals("open", saved.businessTerm());
        assertEquals("CLARIFICATION_RESOLUTION", saved.source());
        assertEquals("purchase-order-status", saved.conceptKey(), "the single resolved concept key must be preserved");
        assertEquals(1, observedEvents.size());
        assertEquals(LearningEvent.Source.CLARIFICATION_RESOLUTION, observedEvents.get(0).source());
    }

    // ── Test 3: semantic correction → Learning Event created via the unified pipeline ──────────

    @Test
    void semanticCorrectionDispatchesALearningEvent() {
        setUp();
        runRepository.scriptedHistory = List.of(
                run("run-1", "show me open orders from last month", "here they are: 12 orders", "QUERY_LIVE_DATA"),
                run("run-2", "no I meant this month", null, "QUERY_LIVE_DATA"));
        correctionDetector.scripted = Optional.of(new CorrectionDetector.DetectedCorrection(
                "last month", "this month", "TIME_RANGE_CORRECTION"));

        service.detectAndSaveCorrectionForRun("run-2", "no I meant this month", "conv-1", "PLATFORM", List.of());

        assertEquals(1, correctionRepository.saved.size(), "existing correction mechanism still fires unchanged");
        assertEquals(1, observedEvents.size(), "a SEMANTIC_CORRECTION Learning Event must also be dispatched");
        assertEquals(LearningEvent.Source.SEMANTIC_CORRECTION, observedEvents.get(0).source());
        assertEquals(0, termExtractor.invocationCount, "SEMANTIC_CORRECTION never invokes TermExtractor");
    }

    // ── Test 4: normal follow-up referencing an existing concept → no TermExtractor invocation ─

    @Test
    void ordinaryFollowUpReferencingKnownConceptNeverInvokesTermExtractor() {
        setUp();
        runRepository.scriptedHistory = List.of(
                run("run-1", "show me open purchase orders", "here they are", "QUERY_LIVE_DATA"),
                run("run-2", "now show me just the ones over $10,000", "here they are", "QUERY_LIVE_DATA"));
        // Prior run was an ordinary success (decisionType QUERY_LIVE_DATA, no CLARIFICATION_NEEDED
        // step) — this is a plain filter follow-up, not a clarification resolution.
        reasoningRepository.scriptedSession = Optional.of(new ReasoningSession(
                "sess-1", "run-1", "conv-1", "agent-1", "PLATFORM",
                "show me open purchase orders", null, "CONCLUDED", null, null, Instant.now(), Instant.now()));
        reasoningRepository.scriptedSteps = List.of(
                new ReasoningStep("step-1", "sess-1", 1, "QUERY", "investigate", null, "",
                        null, null, Instant.now(), "SUFFICIENT", "enough evidence"));
        correctionDetector.scripted = Optional.empty();

        simulateChatServicePostAnswerBlock("run-2", "now show me just the ones over $10,000",
                "SELECT * FROM purchase_orders WHERE status IN (...) AND total > 10000",
                List.of("purchase-order-status"));

        assertEquals(0, termExtractor.invocationCount, "an ordinary follow-up must never invoke TermExtractor");
        assertTrue(observedEvents.isEmpty());
    }

    // ── Test 5: no resolved concept identity → concept_key = null, no inference ────────────────

    @Test
    void noResolvedConceptIdentityPersistsNullConceptKeyWithoutInference() {
        setUp();
        runRepository.scriptedHistory = List.of(
                run("run-1", "show me open orders", null, "ASK_CLARIFICATION"),
                run("run-2", "open means submitted or acknowledged", "here they are", "QUERY_LIVE_DATA"));
        termExtractor.scripted = List.of(new TermExtractor.ExtractedTerm(
                "open", "status IN ('submitted', 'acknowledged')"));

        // Empty resolvedConceptKeys — e.g. AgentBrain resolved none for this run.
        simulateChatServicePostAnswerBlock("run-2", "open means submitted or acknowledged",
                "SELECT ... WHERE status IN ('submitted', 'acknowledged')", List.of());

        assertEquals(1, mappingRepository.upserted.size());
        assertNull(mappingRepository.upserted.get(0).conceptKey(), "no resolved concept ⇒ null, never guessed");
        // Also proves the >1-resolved-concepts collapse-to-null rule directly:
        assertNull(SemanticLearningService.singleConceptKeyOrNull(List.of("a", "b")));
        assertEquals("a", SemanticLearningService.singleConceptKeyOrNull(List.of("a")));
        assertNull(SemanticLearningService.singleConceptKeyOrNull(List.of()));
        assertNull(SemanticLearningService.singleConceptKeyOrNull(null));
    }
}
