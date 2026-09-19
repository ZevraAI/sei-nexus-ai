package com.sei.nexus.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agent.AgentRepository;
import com.sei.nexus.agent.NexusAgent;
import com.sei.nexus.agentbrain.AgentBrain;
import com.sei.nexus.agentbrain.ExecutionContractBuilder;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.ResolvedBusinessModel;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.memory.DocumentChunk;
import com.sei.nexus.memory.DocumentMemoryService;
import com.sei.nexus.reasoning.EvidenceStore;
import com.sei.nexus.reasoning.ReasoningEngine;
import com.sei.nexus.reasoning.ReasoningEventBus;
import com.sei.nexus.reasoning.ReasoningRepository;
import com.sei.nexus.reasoning.ReasoningSession;
import com.sei.nexus.reasoning.ReasoningStep;
import com.sei.nexus.reasoning.OperationalFinding;
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
 * Concept-Level Disjunctive Ambiguity design — CRITICAL DOWNSTREAM BOUNDARY: when {@code
 * AgentBrain.resolve()} returns a {@link ResolvedBusinessModel} whose {@code
 * conceptAmbiguityClarification()} is present, {@code ChatService.ask()} must terminate the
 * request into a clarification answer BEFORE compiling an {@code ExecutionContract}, retrieving
 * physical metadata, or running {@code ReasoningEngine}/the Decision Router.
 *
 * <p>Hand-rolled fakes throughout (this repo's convention — no Mockito, no Spring context, no
 * database), mirroring {@code ChatServiceDecisionRouterAbsorptionCallCountTest}'s exact wiring
 * pattern. {@link AgentBrain} is faked directly at its public {@code resolve} seam, so this test
 * proves ChatService's own dispatch logic, independent of AgentBrain's/ConceptScopedMetadataResolver's
 * internals (covered by their own dedicated test suites).
 */
class ChatServiceConceptAmbiguityShortCircuitTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** Counts calls to the Decision Router's own LLM call (chat) and the legacy Planner catalog
     *  call (chatWithJson) — a nonzero count of either would mean the request did NOT terminate
     *  before the reasoning/decision-routing machinery ran. */
    static class CallCountingAiClient extends AzureOpenAiClient {
        final AtomicInteger chatCalls = new AtomicInteger();
        final AtomicInteger chatWithJsonCalls = new AtomicInteger();
        CallCountingAiClient() { super(new ObjectMapper(), null); }
        @Override public String chat(List<ChatMessage> messages, String systemPrompt) {
            chatCalls.incrementAndGet();
            return "{}";
        }
        @Override public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
            chatWithJsonCalls.incrementAndGet();
            return "{}";
        }
    }

    static class FakeAgentBrainAmbiguous extends AgentBrain {
        final String clarification;
        FakeAgentBrainAmbiguous(String clarification) { super(null, null); this.clarification = clarification; }
        @Override
        public ResolvedBusinessModel resolve(String agentId, List<String> connectionKeys,
                                             List<String> domainKeys, String question,
                                             String conversationId, Boolean memoryAvailable) {
            return new ResolvedBusinessModel(agentId, connectionKeys, question,
                    List.of(), Map.of(), Map.of(),
                    ResolvedQuestion.empty(question), Map.of(), true, Optional.empty(),
                    Optional.of(new com.sei.nexus.semanticmodel.SemanticModel(List.of(), Map.of(), Map.of())),
                    List.of("purchase-order", "sales-transaction"),
                    Optional.of(clarification));
        }
    }

    static class FakeAgentBrainNonAmbiguous extends AgentBrain {
        FakeAgentBrainNonAmbiguous() { super(null, null); }
        @Override
        public ResolvedBusinessModel resolve(String agentId, List<String> connectionKeys,
                                             List<String> domainKeys, String question,
                                             String conversationId, Boolean memoryAvailable) {
            return new ResolvedBusinessModel(agentId, connectionKeys, question,
                    List.of(), Map.of(), Map.of(),
                    ResolvedQuestion.empty(question), Map.of(), false, Optional.empty());
        }
    }

    static class NoOpAgentRepository extends AgentRepository {
        NoOpAgentRepository() { super(null); }
        @Override public List<NexusAgent> findActive() { return List.of(); }
    }

    static class NoOpRunRepository extends RunRepository {
        final List<String> updatedDecisionTypes = new java.util.ArrayList<>();
        NoOpRunRepository() { super(null); }
        @Override public void save(NexusRun run) { }
        @Override public void update(String runKey, String answer, String decisionType, String status, String resultSnapshot) {
            updatedDecisionTypes.add(decisionType);
        }
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

    /** Counts invocations — a nonzero count would mean the reasoning loop ran, which must never
     *  happen for an ambiguous request short-circuited before it. */
    static class CountingReasoningEngine extends ReasoningEngine {
        final AtomicInteger calls = new AtomicInteger();
        CountingReasoningEngine() {
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
            calls.incrementAndGet();
            return new ReasoningResult(new EvidenceStore(), List.of(), List.of(), null, false, List.of(), List.of());
        }
    }

    private ChatService newChatService(AgentBrain agentBrain, CallCountingAiClient aiClient,
                                        NoOpRunRepository runRepository, CountingReasoningEngine reasoningEngine) {
        ObjectMapper mapper = new ObjectMapper();
        return new ChatService(
                runRepository,
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
                reasoningEngine,
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
    void ambiguousConceptResolutionTerminatesIntoClarificationWithoutReasoningOrDecisionRouterCalls() {
        TenantContext.set("tenant_x");
        CallCountingAiClient aiClient = new CallCountingAiClient();
        CountingReasoningEngine reasoningEngine = new CountingReasoningEngine();
        NoOpRunRepository runRepository = new NoOpRunRepository();
        AgentBrain agentBrain = new FakeAgentBrainAmbiguous("Do you mean purchase orders or sales orders?");
        ChatService chatService = newChatService(agentBrain, aiClient, runRepository, reasoningEngine);

        ChatRequest request = new ChatRequest(null, "conv-1", "show me all open orders", null, null);
        ChatResponse response = chatService.ask(request, "user@test.com");

        assertEquals("Do you mean purchase orders or sales orders?", response.answer(),
                "the clarification must be relayed verbatim from Agent Brain's own response");
        assertEquals("ASK_CLARIFICATION", response.decision().type());
        assertTrue(response.decision().requiresClarification());
        assertFalse(response.decision().requiresExecution());

        assertEquals(0, reasoningEngine.calls.get(),
                "the reasoning/SQL-planning loop must NEVER run for an unresolved concept ambiguity");
        assertEquals(0, aiClient.chatCalls.get(),
                "the legacy Decision Router LLM call must never fire — the request terminated before it");
        assertEquals(0, aiClient.chatWithJsonCalls.get(),
                "no Planner/legacy-catalog LLM call may fire either — no metadata/SQL planning occurred");
        assertEquals(List.of("ASK_CLARIFICATION"), runRepository.updatedDecisionTypes);
    }

    @Test
    void regressionShowMeAllOpenOrdersNeverAsksAboutStatus() {
        TenantContext.set("tenant_x");
        CallCountingAiClient aiClient = new CallCountingAiClient();
        CountingReasoningEngine reasoningEngine = new CountingReasoningEngine();
        NoOpRunRepository runRepository = new NoOpRunRepository();
        AgentBrain agentBrain = new FakeAgentBrainAmbiguous("Do you mean purchase orders or sales orders?");
        ChatService chatService = newChatService(agentBrain, aiClient, runRepository, reasoningEngine);

        ChatRequest request = new ChatRequest(null, "conv-1", "show me all open orders", null, null);
        ChatResponse response = chatService.ask(request, "user@test.com");

        assertFalse(response.answer().toLowerCase().contains("status"),
                "must ask a concept-level question, never a status/value-level question");
        assertEquals(0, reasoningEngine.calls.get(), "must never reach any physical object, e.g. retail_core.sales_transactions");
    }

    @Test
    void nonAmbiguousQuestionsAreUnaffectedByTheShortCircuit() {
        TenantContext.set("tenant_x");
        CallCountingAiClient aiClient = new CallCountingAiClient();
        CountingReasoningEngine reasoningEngine = new CountingReasoningEngine();
        NoOpRunRepository runRepository = new NoOpRunRepository();
        AgentBrain agentBrain = new FakeAgentBrainNonAmbiguous();
        ChatService chatService = newChatService(agentBrain, aiClient, runRepository, reasoningEngine);

        ChatRequest request = new ChatRequest(null, "conv-1", "show me open purchase orders", null, null);
        ChatResponse response = chatService.ask(request, "user@test.com");

        assertNotNull(response);
        assertFalse(runRepository.updatedDecisionTypes.contains("ASK_CLARIFICATION")
                        && response.answer().contains("purchase orders or sales orders"),
                "a non-ambiguous resolution must never be short-circuited into the concept clarification path");
    }
}
