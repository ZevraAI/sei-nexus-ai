package com.sei.nexus.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agent.AgentRepository;
import com.sei.nexus.agent.NexusAgent;
import com.sei.nexus.agentbrain.AgentBrain;
import com.sei.nexus.agentbrain.ConceptScopedMetadataResolver;
import com.sei.nexus.agentbrain.ExecutionContractBuilder;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.ResolvedBusinessModel;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.memory.DocumentChunk;
import com.sei.nexus.memory.DocumentMemoryService;
import com.sei.nexus.reasoning.EvidenceStore;
import com.sei.nexus.reasoning.OperationalFinding;
import com.sei.nexus.reasoning.ReasoningEngine;
import com.sei.nexus.reasoning.ReasoningEventBus;
import com.sei.nexus.reasoning.ReasoningRepository;
import com.sei.nexus.reasoning.ReasoningSession;
import com.sei.nexus.reasoning.ReasoningStep;
import com.sei.nexus.response.NaturalLanguageComposer;
import com.sei.nexus.run.NexusRun;
import com.sei.nexus.run.RunRepository;
import com.sei.nexus.runtime.ExecutionReference;
import com.sei.nexus.runtime.ExecutionReferenceRepository;
import com.sei.nexus.semantic.LearningContextBuilder;
import com.sei.nexus.semantic.ResolvedQuestion;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.strategy.ExecutionStrategySelector;
import com.sei.nexus.strategy.RequestAnalysis;
import com.sei.nexus.temporal.BaselineService;
import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9E — Stage 1 (concept retrieval + one LLM call) is the SOLE semantic concept-and-
 * routing mechanism for the conversational Chat path. The legacy Decision Router
 * ({@code ChatService#getLlmDecision}, which called {@code AzureOpenAiClient#chat}) has been
 * removed entirely — there is no second routing LLM call under any condition. This is a
 * REGRESSION GUARD: if a fallback to a second routing LLM call is ever reintroduced (whether by
 * resurrecting {@code getLlmDecision} or by adding a new one), {@link #chatCalls} in the second
 * test below will be non-zero and the test fails.
 *
 * <p>Hand-rolled fakes throughout (this repo's convention — no Mockito, no Spring context, no
 * database, no network). {@link AgentBrain} is faked directly at its public {@code resolve}
 * seam so this test proves ChatService's own dispatch logic — not AgentBrain's or {@code
 * ConceptScopedMetadataResolver}'s internals, which have their own dedicated test coverage.
 */
class ChatServiceDecisionRouterAbsorptionCallCountTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** Counts every call to {@code AzureOpenAiClient#chat} — the method the now-removed legacy
     *  Decision Router used to call. A non-zero count here after this phase would mean some
     *  code path is once again routing through a second, Java-triggered LLM call. */
    static class CallCountingAiClient extends AzureOpenAiClient {
        final AtomicInteger chatCalls = new AtomicInteger();
        CallCountingAiClient() { super(new ObjectMapper(), null); }
        @Override public String chat(List<ChatMessage> messages, String systemPrompt) {
            chatCalls.incrementAndGet();
            return "{}"; // never expected to be reached — see the class javadoc
        }
    }

    static class FakeAgentBrainWithRouting extends AgentBrain {
        final ConceptScopedMetadataResolver.RoutingDecision routing;
        FakeAgentBrainWithRouting(ConceptScopedMetadataResolver.RoutingDecision routing) {
            super(null, null);
            this.routing = routing;
        }
        @Override
        public ResolvedBusinessModel resolve(String agentId, List<String> connectionKeys,
                                             List<String> domainKeys, String question,
                                             String conversationId, Boolean memoryAvailable) {
            return new ResolvedBusinessModel(agentId, connectionKeys, question,
                    List.of(), Map.of(), Map.of(),
                    ResolvedQuestion.empty(question), Map.of(), true, Optional.of(routing));
        }
    }

    static class NoOpAgentRepository extends AgentRepository {
        NoOpAgentRepository() { super(null); }
        @Override public List<NexusAgent> findActive() { return List.of(); }
    }

    static class NoOpRunRepository extends RunRepository {
        NoOpRunRepository() { super(null); }
        @Override public void save(NexusRun run) { }
        @Override public void update(String runKey, String answer, String decisionType, String status, String resultSnapshot) { }
        @Override public List<NexusRun> findConversationRuns(String conversationId, int limit) { return List.of(); }
        @Override public Optional<String> latestResultSnapshot(String conversationId) { return Optional.empty(); }
        @Override public void saveEvidence(String evidenceKey, String runKey, String evidenceType, String payloadJson) { }
    }

    static class NoOpReasoningRepository extends ReasoningRepository {
        NoOpReasoningRepository() { super(null); }
        @Override public void saveSession(ReasoningSession s) { }
        @Override public void updateSessionStatus(String sessionKey, String status, String conclusion,
                                                   Double confidence, Instant concludedAt) { }
        @Override public void saveStep(ReasoningStep step) { }
        @Override public List<OperationalFinding> findRecentFindings(List<String> domainKeys, int limit) { return List.of(); }
    }

    static class NoOpDocumentMemoryService extends DocumentMemoryService {
        NoOpDocumentMemoryService() { super(null, null); }
        @Override public List<DocumentChunk> retrieveContext(String question, List<String> domainKeys) { return List.of(); }
    }

    static class EmptySemanticService extends SemanticService {
        EmptySemanticService() { super(null, null, null); }
        @Override public SemanticContext semanticContextForObjectKeys(List<String> objectKeys) { return SemanticContext.EMPTY; }
        @Override public SemanticContext semanticContextWithBindings(List<String> domainKeys, String question) { return SemanticContext.EMPTY; }
    }

    static class NoOpBaselineService extends BaselineService {
        NoOpBaselineService() { super(null, null, null, new ObjectMapper(), null, null); }
        @Override public String getAnomalyContext(List<String> domainKeys) { return ""; }
    }

    static class NoOpLearningContextBuilder extends LearningContextBuilder {
        NoOpLearningContextBuilder() { super(null); }
        @Override public LearningContext build(String conversationId) { return new LearningContext(""); }
    }

    static class ChatOnlyStrategySelector implements ExecutionStrategySelector {
        @Override public RequestAnalysis analyze(String question, String tenantSchema) {
            return RequestAnalysis.chat("test — always CHAT");
        }
    }

    static class NoOpExecutionReferenceRepository extends ExecutionReferenceRepository {
        NoOpExecutionReferenceRepository() { super(null, null); }
        @Override public Optional<ExecutionReference> findLatestByConversation(String conversationId) { return Optional.empty(); }
    }

    static class CannedNaturalLanguageComposer extends NaturalLanguageComposer {
        CannedNaturalLanguageComposer() { super(null); }
        @Override public String compose(CompositionRequest req) { return "Here are the results."; }
    }

    /** Reproduces exactly the shape a real investigation with no evidence produces — proves
     *  this test's fake never needs Planner/Evaluator/GovernedSqlRuntime to be real. */
    static class NoOpReasoningEngine extends ReasoningEngine {
        NoOpReasoningEngine() {
            super(null, null, new ReasoningEventBus(new ObjectMapper()), null, null, new ObjectMapper(),
                    new com.sei.nexus.reasoning.ColumnMetadataRequestHandler(
                            new com.sei.nexus.agentbrain.PromptContextBuilder(),
                            new com.sei.nexus.agentbrain.PromptAssembler(),
                            new com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler(null)));
        }
        @Override
        public ReasoningResult reason(String question, String enrichedQ, String sessionKey, String schemaCtx,
                                       String runKey, String userEmail, boolean forceAsync,
                                       Map<String, com.sei.nexus.semanticmodel.ColumnValueDomain> literalScope,
                                       com.sei.nexus.agentbrain.ExecutionContract contract, boolean enforceContractGate,
                                       String conversationId, String parentExecutionId,
                                       ExecutionReference priorExecution,
                                       com.sei.nexus.agentbrain.ExecutionContract resolvedObjects) {
            return new ReasoningResult(new EvidenceStore(), List.of(), List.of(), null, false, List.of(), List.of());
        }
    }

    private ChatService newChatService(AgentBrain agentBrain, AzureOpenAiClient aiClient) {
        ObjectMapper mapper = new ObjectMapper();
        return new ChatService(
                new NoOpRunRepository(),
                new NoOpDocumentMemoryService(),
                new EmptySemanticService(),
                new NoOpAgentRepository(),
                null, null, null, null,
                new NoOpReasoningRepository(),
                new NoOpBaselineService(),
                null,
                null,
                aiClient,
                mapper,
                null, null, null, null, null, null,
                new NoOpReasoningEngine(),
                new ReasoningEventBus(mapper),
                null,
                new NoOpLearningContextBuilder(),
                new ChatOnlyStrategySelector(),
                null,
                null,
                agentBrain,
                new ExecutionContractBuilder(null),
                new PromptContextBuilder(),
                null,
                null,
                new CannedNaturalLanguageComposer(),
                new NoOpExecutionReferenceRepository(),
                null,
                null);
    }

    @Test
    void noSecondLlmRoutingCallIsMadeWhenStage1RoutingIsPresent() {
        TenantContext.set("tenant_x");
        CallCountingAiClient aiClient = new CallCountingAiClient();
        AgentBrain agentBrain = new FakeAgentBrainWithRouting(
                new ConceptScopedMetadataResolver.RoutingDecision("QUERY_LIVE_DATA", ""));
        ChatService chatService = newChatService(agentBrain, aiClient);

        ChatRequest request = new ChatRequest(null, "conv-1", "Show me all purchase orders", null, null);
        ChatResponse response = chatService.ask(request, "user@test.com");

        assertNotNull(response);
        assertEquals(0, aiClient.chatCalls.get(),
                "no second routing LLM call may ever fire when Stage 1 already produced a routing decision");
    }

    static class FakeAgentBrainWithoutRouting extends AgentBrain {
        FakeAgentBrainWithoutRouting() { super(null, null); }
        @Override
        public ResolvedBusinessModel resolve(String agentId, List<String> connectionKeys,
                                             List<String> domainKeys, String question,
                                             String conversationId, Boolean memoryAvailable) {
            // Reproduces the case Stage 1 cannot establish routing for (connection not onboarded
            // onto Concept-Scoped Metadata Narrowing, or the model's own routing field was
            // missing/invalid) — routingDecision() is empty.
            return new ResolvedBusinessModel(agentId, connectionKeys, question,
                    List.of(), Map.of(), Map.of(),
                    ResolvedQuestion.empty(question), Map.of(), false, Optional.empty());
        }
    }

    @Test
    void noSecondLlmRoutingCallIsMadeWhenStage1RoutingIsAbsent_explicitReadinessOutcomeInstead() {
        // REGRESSION GUARD (Phase 9E): this is the exact condition that used to fall back to the
        // legacy Decision Router (ChatService#getLlmDecision, now removed). There must be no
        // second routing LLM call, no Java-side routing inference, and no guessed agent — only
        // an explicit, deterministic readiness outcome.
        TenantContext.set("tenant_x");
        CallCountingAiClient aiClient = new CallCountingAiClient();
        AgentBrain agentBrain = new FakeAgentBrainWithoutRouting();
        ChatService chatService = newChatService(agentBrain, aiClient);

        ChatRequest request = new ChatRequest(null, "conv-1", "Show me all purchase orders", null, null);
        ChatResponse response = chatService.ask(request, "user@test.com");

        assertNotNull(response);
        assertEquals(0, aiClient.chatCalls.get(),
                "there is no legacy Decision Router fallback anymore — no second routing LLM call "
                        + "may ever fire when Stage 1 could not establish routing");
        assertEquals("ROUTING_UNAVAILABLE", response.decision().type(),
                "Stage 1's inability to establish routing must produce an explicit, deterministic "
                        + "readiness outcome, never a silently-guessed decision type");
        assertTrue(response.answer().contains("Apply a Pack for this connection")
                        || response.answer().toLowerCase().contains("setup"),
                "the answer must explain the readiness gap plainly, not invent a semantic answer: "
                        + response.answer());
    }

    static class FakeAgentBrainThatThrows extends AgentBrain {
        FakeAgentBrainThatThrows() { super(null, null); }
        @Override
        public ResolvedBusinessModel resolve(String agentId, List<String> connectionKeys,
                                             List<String> domainKeys, String question,
                                             String conversationId, Boolean memoryAvailable) {
            // Simulates required Stage 1 infrastructure (the concept index / embedding call)
            // failing outright — a condition distinct from "routing absent," which must never be
            // silently absorbed into a routing-unavailable readiness outcome (see
            // ConceptScopedMetadataResolver's own "two distinct degradation modes" javadoc).
            throw new com.sei.nexus.common.NexusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Stage 1 requires a built concept index for this tenant.");
        }
    }

    @Test
    void stage1InfrastructureFailurePropagatesAsAnErrorNeverAsRoutingUnavailableOrADecisionRouterCall() {
        // Distinguishes a genuine Stage 1 infrastructure failure (this test) from "Stage 1 ran but
        // produced no routing decision" (the previous test) — the two must never be conflated.
        TenantContext.set("tenant_x");
        CallCountingAiClient aiClient = new CallCountingAiClient();
        AgentBrain agentBrain = new FakeAgentBrainThatThrows();
        ChatService chatService = newChatService(agentBrain, aiClient);

        ChatRequest request = new ChatRequest(null, "conv-1", "Show me all purchase orders", null, null);

        assertThrows(com.sei.nexus.common.NexusException.class,
                () -> chatService.ask(request, "user@test.com"),
                "a genuine Stage 1 infrastructure failure must propagate as an explicit error — "
                        + "never silently degrade into a routing-unavailable readiness answer");
        assertEquals(0, aiClient.chatCalls.get(),
                "no second routing LLM call may ever fire on a Stage 1 infrastructure failure either");
    }
}
