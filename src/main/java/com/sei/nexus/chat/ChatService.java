package com.sei.nexus.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agent.AgentPlaybook;
import com.sei.nexus.artifacts.ResponseArtifacts;
import com.sei.nexus.artifacts.ResponseArtifactsBuilder;
import com.sei.nexus.agent.AgentRepository;
import com.sei.nexus.agent.NexusAgent;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.attachment.ChatAttachment;
import com.sei.nexus.attachment.ChatAttachmentRepository;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.governance.ColumnMaskingService;
import com.sei.nexus.governance.DataContractService;
import com.sei.nexus.governance.GovernanceAuditService;
import com.sei.nexus.governance.RowLevelSecurityService;
import com.sei.nexus.governance.UserAttributesRepository;
import com.sei.nexus.reasoning.EvidenceStore;
import com.sei.nexus.reasoning.ReasoningEngine;
import com.sei.nexus.reasoning.ReasoningEventBus;
import com.sei.nexus.reasoning.ProgressPhase;
import com.sei.nexus.knowledge.KnowledgeGap;
import com.sei.nexus.knowledge.KnowledgeGapRepository;
import com.sei.nexus.memory.DocumentChunk;
import com.sei.nexus.memory.DocumentMemoryService;
import com.sei.nexus.query.QueryExecutionRepository;
import com.sei.nexus.query.QueryGovernanceService;
import com.sei.nexus.reasoning.OperationalFinding;
import com.sei.nexus.reasoning.ReasoningRepository;
import com.sei.nexus.reasoning.ReasoningSession;
import com.sei.nexus.run.NexusRun;
import com.sei.nexus.run.RunRepository;
import com.sei.nexus.agentbrain.AgentBrain;
import com.sei.nexus.agentbrain.ConceptScopedMetadataResolver;
import com.sei.nexus.agentbrain.ExecutionBindings;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.ExecutionContractBuilder;
import com.sei.nexus.agentbrain.PromptAssembler;
import com.sei.nexus.response.ExecutionOutcomeInterpreter;
import com.sei.nexus.response.NaturalLanguageComposer;
import com.sei.nexus.response.StructuredAnswer;
import com.sei.nexus.agentbrain.PromptContext;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.ResolvedBusinessModel;
import com.sei.nexus.agentmemory.BusinessWorldToolAdapter;
import com.sei.nexus.agentmemory.ConversationMemoryService;
import com.sei.nexus.agentmemory.ConversationRosterEntry;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.agentrunner.AgentRunner;
import com.sei.nexus.agentrunner.ZevraAgent;
import com.sei.nexus.agentrunner.ZevraAgentRouter;
import com.sei.nexus.agentrunner.ZevraSession;
import com.sei.nexus.strategy.ExecutionStrategy;
import com.sei.nexus.strategy.ExecutionStrategySelector;
import com.sei.nexus.strategy.IntentType;
import com.sei.nexus.strategy.RequestAnalysis;
import com.sei.nexus.semantic.LearningContextBuilder;
import com.sei.nexus.semantic.ResolvedQuestion;
import com.sei.nexus.semantic.SemanticLearningService;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.graph.KnowledgeGraphService;
import com.sei.nexus.temporal.BaselineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final RunRepository runRepository;
    private final DocumentMemoryService documentMemoryService;
    private final SemanticService semanticService;
    private final AgentRepository agentRepository;
    private final ConnectionRepository connectionRepository;
    private final QueryGovernanceService queryGovernanceService;
    private final QueryExecutionRepository queryExecutionRepository;
    private final DynamicSqlService dynamicSqlService;
    private final ReasoningRepository reasoningRepository;
    private final BaselineService baselineService;
    private final KnowledgeGapRepository knowledgeGapRepository;
    private final KnowledgeGraphService knowledgeGraphService;
    private final AzureOpenAiClient        aiClient;
    private final ObjectMapper             objectMapper;
    private final ChatAttachmentRepository attachmentRepository;
    // ── Governance chain (Phase 1) ────────────────────────────────────────────
    private final DataContractService      dataContractService;
    private final RowLevelSecurityService  rowLevelSecurityService;
    private final ColumnMaskingService     columnMaskingService;
    private final GovernanceAuditService   governanceAuditService;
    private final UserAttributesRepository userAttributesRepository;
    // ── Multi-step reasoning (Phase 2) ───────────────────────────────────────
    private final ReasoningEngine          reasoningEngine;
    private final ReasoningEventBus        reasoningEventBus;
    // ── Semantic learning (Phase 3) ───────────────────────────────────────────
    private final SemanticLearningService  semanticLearningService;
    private final LearningContextBuilder   learningContextBuilder;
    // ── Execution Strategy Selection (Unified Answer Engine front door) ───────
    private final ExecutionStrategySelector strategySelector;
    // ── Zevra Agentic AI routing ──────────────────────────────────────────────
    private final ZevraAgentRouter         zevraAgentRouter;
    private final AgentRunner              agentRunner;
    // ── Business reasoning (Unified Answer Engine) ───────────────────────────
    private final AgentBrain               agentBrain;
    private final ExecutionContractBuilder executionContractBuilder;
    private final PromptContextBuilder     promptContextBuilder;
    private final PromptAssembler          promptAssembler;
    // ── Response composition (Unified Answer Engine, Phase 4) ────────────────
    private final ExecutionOutcomeInterpreter outcomeInterpreter;
    private final NaturalLanguageComposer     nlComposer;
    // ── Execution Continuity ─────────────────────────────────────────────────
    private final com.sei.nexus.runtime.ExecutionReferenceRepository executionReferenceRepository;
    // ── Conversation Memory (Chat integration, Phase 3) ──────────────────────
    // Reuses the exact Core capability already implemented for Agent (Phase 1/2) — no
    // second memory implementation. ConversationMemoryService/BusinessWorldToolAdapter have
    // no dependency on AgentRunner/AgentToolRegistry, so Chat can invoke them directly.
    private final ConversationMemoryService  conversationMemoryService;
    private final BusinessWorldToolAdapter   businessWorldToolAdapter;

    // Max characters for entity schema context per LLM call.
    // Configurable via nexus.context.max-entity-chars — keeps prompts lean
    // without hardcoding tenant-specific table counts.
    @org.springframework.beans.factory.annotation.Value("${nexus.context.max-entity-chars:1500}")
    private int maxEntityContextChars;

    // Unified Answer Engine, Phase 3 Step 2 — business-object gate migration mode:
    //   off     : the compiled contract is not passed to the runtime (gate inert)
    //   shadow  : the gate is evaluated and its would-be rejections recorded, but not enforced
    //   enforce : an unapproved table becomes a re-plannable observation, as on the agent path
    // Defaults to `shadow` so production behaviour is unchanged until the migration is measured.
    /** Fixed user-facing message for any orchestration failure. Raw exception detail is logged and
     *  audited privately, never returned to the user (no SQL, table names, or driver text leaks). */
    static final String GENERIC_INVESTIGATION_ERROR =
            "I couldn't complete this investigation. Please try again or rephrase your question.";

    // Approved-surface enforcement. Default 'enforce': an out-of-contract table reference is a
    // deterministic re-plan signal (ReasoningEngine treats UNAPPROVED_OBJECTS like a literal
    // rejection and re-plans). 'shadow' observes only; 'off' disables the gate. Overridable per env.
    @org.springframework.beans.factory.annotation.Value("${nexus.chat.contract-gate:enforce}")
    private String contractGateMode;

    // Conversation Memory → Chat (Phase 3). Independent of the Agent memory-tools flag.
    // false (default): behavior identical to before this change — no memory-selection call,
    // no context injection. true: an isolated, dedicated memory-selection call (NOT merged
    // into getLlmDecision(), NOT a change to ReasoningEngine/ReasoningPlanner) selects
    // already-remembered entity_keys and folds their authoritative objects into the SQL
    // planner's context, exactly like the existing learningContextBuilder/playbook injection.
    @org.springframework.beans.factory.annotation.Value("${zevra.chat.conversation-memory.enabled:false}")
    private boolean chatMemoryEnabled;

    public ChatService(RunRepository runRepository,
                       DocumentMemoryService documentMemoryService,
                       SemanticService semanticService,
                       AgentRepository agentRepository,
                       ConnectionRepository connectionRepository,
                       QueryGovernanceService queryGovernanceService,
                       QueryExecutionRepository queryExecutionRepository,
                       DynamicSqlService dynamicSqlService,
                       ReasoningRepository reasoningRepository,
                       BaselineService baselineService,
                       KnowledgeGapRepository knowledgeGapRepository,
                       KnowledgeGraphService knowledgeGraphService,
                       AzureOpenAiClient aiClient,
                       ObjectMapper objectMapper,
                       ChatAttachmentRepository attachmentRepository,
                       DataContractService dataContractService,
                       RowLevelSecurityService rowLevelSecurityService,
                       ColumnMaskingService columnMaskingService,
                       GovernanceAuditService governanceAuditService,
                       UserAttributesRepository userAttributesRepository,
                       ReasoningEngine reasoningEngine,
                       ReasoningEventBus reasoningEventBus,
                       SemanticLearningService semanticLearningService,
                       LearningContextBuilder learningContextBuilder,
                       ExecutionStrategySelector strategySelector,
                       ZevraAgentRouter zevraAgentRouter,
                       AgentRunner agentRunner,
                       AgentBrain agentBrain,
                       ExecutionContractBuilder executionContractBuilder,
                       PromptContextBuilder promptContextBuilder,
                       PromptAssembler promptAssembler,
                       ExecutionOutcomeInterpreter outcomeInterpreter,
                       NaturalLanguageComposer nlComposer,
                       com.sei.nexus.runtime.ExecutionReferenceRepository executionReferenceRepository,
                       ConversationMemoryService conversationMemoryService,
                       BusinessWorldToolAdapter businessWorldToolAdapter) {
        this.runRepository            = runRepository;
        this.documentMemoryService    = documentMemoryService;
        this.semanticService          = semanticService;
        this.agentRepository          = agentRepository;
        this.connectionRepository     = connectionRepository;
        this.queryGovernanceService   = queryGovernanceService;
        this.queryExecutionRepository = queryExecutionRepository;
        this.dynamicSqlService        = dynamicSqlService;
        this.reasoningRepository      = reasoningRepository;
        this.baselineService          = baselineService;
        this.knowledgeGapRepository   = knowledgeGapRepository;
        this.knowledgeGraphService    = knowledgeGraphService;
        this.aiClient                 = aiClient;
        this.objectMapper             = objectMapper;
        this.attachmentRepository     = attachmentRepository;
        this.dataContractService      = dataContractService;
        this.rowLevelSecurityService  = rowLevelSecurityService;
        this.columnMaskingService     = columnMaskingService;
        this.governanceAuditService   = governanceAuditService;
        this.userAttributesRepository = userAttributesRepository;
        this.reasoningEngine          = reasoningEngine;
        this.reasoningEventBus        = reasoningEventBus;
        this.semanticLearningService  = semanticLearningService;
        this.learningContextBuilder   = learningContextBuilder;
        this.strategySelector         = strategySelector;
        this.zevraAgentRouter         = zevraAgentRouter;
        this.agentRunner              = agentRunner;
        this.agentBrain               = agentBrain;
        this.executionContractBuilder = executionContractBuilder;
        this.promptContextBuilder     = promptContextBuilder;
        this.promptAssembler          = promptAssembler;
        this.outcomeInterpreter       = outcomeInterpreter;
        this.nlComposer               = nlComposer;
        this.executionReferenceRepository = executionReferenceRepository;
        this.conversationMemoryService = conversationMemoryService;
        this.businessWorldToolAdapter   = businessWorldToolAdapter;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public ChatResponse ask(ChatRequest request, String userEmail) {
        // Fail closed: conversational data access is tenant-scoped. If no tenant context was
        // established for this request, refuse rather than silently reading the public schema.
        if (!com.sei.nexus.tenant.TenantContext.isSet()) {
            throw new NexusException(HttpStatus.UNAUTHORIZED,
                    "No tenant context established for this request");
        }
        String raw = request.question() != null ? request.question().trim() : "";
        boolean forceAsync = false;
        com.sei.nexus.usage.UsageContext.set("chat", userEmail);

        // STEP 1: Slash command routing
        if (raw.startsWith("/knowledge ")) {
            return handleKnowledgeProposal(raw.substring(11).trim(), userEmail);
        }
        if (raw.startsWith("/request-source ")) {
            return handleSourceRequest(raw.substring(16).trim(), userEmail);
        }
        if (raw.startsWith("/async ")) {
            raw = raw.substring(7).trim();
            forceAsync = true;
        }
        // STEP 1b: Zevra Agent routing — semantic dispatch, no keywords.
        // Check active agents before loading attachment or running the full pipeline.
        // Falls through silently if no agent matches or routing fails.
        String tenantSchemaForRouting = com.sei.nexus.tenant.TenantContext.getSchemaStrict();

        // Unified Answer Engine front door: the Execution Strategy Selector owns HOW this request
        // executes. It classifies on execution characteristics only (never data ownership),
        // producing the canonical RequestAnalysis exactly once (Invariant 1, 3). The Agent Router
        // (which agent) runs ONLY when the selected strategy is AGENT — it is never the front door
        // (Invariant 2). AGENT with no suitable agent falls back to CHAT (approved graceful path).
        RequestAnalysis requestAnalysis = strategySelector.analyze(raw, tenantSchemaForRouting);
        java.util.Optional<ZevraAgent> routedZevraAgent = java.util.Optional.empty();
        if (shouldInvokeAgentRouter(requestAnalysis)) {
            routedZevraAgent = zevraAgentRouter.route(raw, tenantSchemaForRouting);
            if (routedZevraAgent.isEmpty()) {
                log.info("Strategy AGENT selected but no suitable agent matched — falling back to CHAT");
            }
        }
        // One NexusRun per conversation request: derive the identities once and persist
        // exactly one run, shared by the routed-agent branch and the normal pipeline. This
        // prevents a routed fall-through from re-inserting the same run_key (ADR-0003 A2
        // lifecycle fix), and the routed agent reuses this run rather than creating a second.
        String conversationId = (request.conversationId() != null && !request.conversationId().isBlank())
                ? request.conversationId() : Keys.conversationKey();
        String runKey = (request.clientRunKey() != null && !request.clientRunKey().isBlank())
                ? request.clientRunKey() : Keys.runKey();
        boolean runPersisted = false;

        // Cost baseline instrumentation (measurement-only): every LLM_METRIC line emitted while
        // handling this question — strategy selection, embedding, Stage 1, routing, planner/
        // evaluator loop, answer composition — will carry this run's own runKey, so they can be
        // grouped back to one chat question. ask() runs synchronously on this request thread, so
        // no explicit clear() is needed: the next request handled by this pooled thread overwrites
        // it here before making any LLM call of its own (same reasoning as LlmCallTag's javadoc).
        com.sei.nexus.ai.OperationCorrelationId.set("CHAT:" + runKey);

        // Runtime Progress Projection: intent classification + agent routing (above) already
        // ran by the time runKey exists, so this phase is reported as a single started+completed
        // pair rather than fabricating a mid-flight "started" moment that never happened.
        reasoningEventBus.phaseStarted(runKey, ProgressPhase.UNDERSTANDING);
        reasoningEventBus.phaseCompleted(runKey, ProgressPhase.UNDERSTANDING);

        // Execution Continuity: follow-ups ground on the PREVIOUS execution's facts
        // (ExecutionReference) — retrieval target, bindings, result shape — never on truncated
        // answer prose. parentExecutionId links this turn to that execution (AgentBrain's lineage
        // decision, recorded verbatim by Runtime). Both the routed-agent branch and the
        // conversational path below use this grounding.
        List<NexusRun> convHistory = runRepository.findConversationRuns(conversationId, 8);
        java.util.Optional<com.sei.nexus.runtime.ExecutionReference> prevExecution =
                executionReferenceRepository.findLatestByConversation(conversationId);
        String parentExecutionId = prevExecution
                .map(com.sei.nexus.runtime.ExecutionReference::executionId).orElse(null);
        String conversationContext = buildExecutionGrounding(prevExecution.orElse(null));

        if (routedZevraAgent.isPresent()) {
            ZevraAgent za = routedZevraAgent.get();
            runRepository.save(new NexusRun(runKey, conversationId, za.slug(), null,
                    userEmail, raw, null, null, "RUNNING", null, null, null));
            runPersisted = true;
            try {
                reasoningEventBus.phaseStarted(runKey, ProgressPhase.REASONING);
                ZevraSession session = agentRunner.run(za, raw, userEmail, runKey, conversationContext,
                        conversationId, parentExecutionId);
                String answer = session.finalOutput() != null && !session.finalOutput().isBlank()
                        ? session.finalOutput()
                        : "The agent completed but produced no response.";
                reasoningEventBus.phaseCompleted(runKey, ProgressPhase.REASONING);
                reasoningEventBus.phaseStarted(runKey, ProgressPhase.COMPOSITION);
                reasoningEventBus.phaseCompleted(runKey, ProgressPhase.COMPOSITION);
                reasoningEventBus.publish(runKey, "answer_ready", Map.of("answer", answer));
                reasoningEventBus.complete(runKey);

                // Surface + persist the agent's query results so the investigation's evidence is
                // reproducible: Live Mode renders the table now, and reload / Report Mode render the
                // identical table later. The rows already exist in the agent's step log — we
                // denormalise them into the existing result_snapshot, exactly as the reasoning path
                // does (RunRepository.update). No new storage, no pipeline change.
                List<Map<String, Object>> agentRows = extractAgentQueryRows(session.stepsJson());
                String resultSnapshot = agentRows.isEmpty() ? null : toJson(agentRows);
                runRepository.update(runKey, answer, "ZEVRA_AGENT", "COMPLETE", resultSnapshot);

                // Record a data-backed investigation as an OperationalFinding so the homepage
                // surfaces real intelligence (Open findings / Recommendations / Signals).
                persistAgentFinding(za, raw, answer, session, runKey, conversationId);

                OrchestratorDecision decision = new OrchestratorDecision(
                        "ZEVRA_AGENT", requestAnalysis.intentType().name(), "LIVE_DATA",
                        true, false, false);
                List<Map<String, Object>> agentSteps = agentReasoningSteps(session.stepsJson());
                StructuredAnswer agentSemantics = extractAgentSemantics(session.stepsJson(), answer);
                // The Zevra Agent path uses its own ReAct loop (AgentRunner), not ReasoningEngine —
                // it has no EvidenceStore/InvestigationDataset to preserve; empty, never null.
                ResponseArtifacts artifacts = ResponseArtifactsBuilder.build(raw, answer, agentSteps,
                        agentRows, List.of(), List.of(), new ResponseArtifacts.AgentContext(
                                za.slug(), za.name(), session.id(), session.iterationsUsed()),
                        agentSemantics);
                return new ChatResponse(conversationId, runKey, answer, List.of(), decision,
                        za.slug(), za.name(), null, 0.9, false, "",
                        List.of(), List.of(), agentRows, List.of(), agentSteps, List.of(),
                        session.id(), artifacts);
            } catch (Exception e) {
                log.warn("ZevraAgent '{}' failed, falling through to normal chat: {}",
                        za.name(), e.getMessage());
                runRepository.update(runKey, null, "ZEVRA_AGENT", "FAILED", null);
                // Fall through to normal chat pipeline below
            }
        }

        // STEP 1d: Load attachment content if present.
        // IMPORTANT: the raw user question (raw) is kept separate from the enriched
        // version (enrichedQuestion) that includes file content.
        // - Routing, intent detection, agent selection all use `raw` — they must read
        //   the user's intent, not the file contents.
        // - SQL planning and answer composition use `enrichedQuestion` — they need the
        //   file content to build WHERE IN clauses and incorporate reference data.
        // - The run record stored in the DB also uses `raw` to keep it readable.
        String attachmentContext = "";
        String attachmentSummary = "";
        if (request.attachmentKey() != null && !request.attachmentKey().isBlank()) {
            try {
                ChatAttachment att = attachmentRepository.findByKey(request.attachmentKey())
                        .orElse(null);
                if (att != null && att.extractedText() != null) {
                    attachmentContext = att.extractedText();
                    attachmentSummary = att.summary() != null ? att.summary() : att.fileName();
                    log.info("Attachment '{}' ({}) injected into conversation context",
                            att.fileName(), att.attachmentType());
                }
            } catch (Exception e) {
                log.warn("Could not load attachment {}: {}", request.attachmentKey(), e.getMessage());
            }
        }

        // enrichedQuestion is used only by the SQL planner and answer composer.
        final String enrichedQuestion = attachmentContext.isBlank() ? raw
                : "=== ATTACHED FILE: " + attachmentSummary + " ===\n"
                + attachmentContext + "\n"
                + "=== END OF ATTACHMENT ===\n\n"
                + "User question: " + raw;

        // STEP 2: Conversation — conversationId already derived above (one run per request).

        // STEP 3: Recent history (already loaded above as convHistory — reuse it)
        List<NexusRun> history = convHistory;

        // STEP 4: Route agent — use raw question only (not file content)
        NexusAgent agent = resolveAgent(request.agentKey(), raw, history);
        double routingConfidence = agent != null ? 0.9 : 0.5;

        // STEP 5: Persist the run — reuse the single run for this request. On a routed
        // fall-through the run already exists (created above), so it is never re-inserted;
        // runKey was derived above (client-provided key preserved for SSE pre-subscription).
        if (!runPersisted) {
            runRepository.save(new NexusRun(runKey, conversationId,
                    agent != null ? agent.agentKey() : null,
                    agent != null ? agent.domainKeys() : null,
                    userEmail, raw, null, null, "RUNNING", null, null, null));
            runPersisted = true;
        }

        try {
            List<String> domainKeys = toDomainKeyList(agent);
            List<String> connKeys = toConnKeyList(agent);

            // Decision Router absorption: document memory availability is the one runtime fact
            // the combined Persistent Knowledge / File Search call cannot obtain itself (document
            // memory is a structurally separate subsystem from the tenant's persistent-knowledge
            // Vector Store — not retrievable via file_search) — so it must be computed BEFORE
            // AgentBrain.resolve() runs Stage 1, not after. This is the one reordering this
            // absorption required; STEP 6's own SSE phase markers below are otherwise unchanged
            // and simply reuse this already-computed value instead of re-fetching it.
            List<DocumentChunk> memChunks = documentMemoryService.retrieveContext(raw, domainKeys);
            boolean memoryAvailable = !memChunks.isEmpty();

            // STEP 5b: Business reasoning (Unified Answer Engine, Phase 3 Step 1).
            // AgentBrain is the sole reasoning owner: it performs Business Language
            // Resolution (PRO-31) for this scope — deterministic, domain-scoped,
            // annotate-never-substitute — and returns the resolved business model.
            // `raw` is never modified; resolutions annotate it. On any failure or
            // zero matches the resolution is empty and the whole pipeline behaves
            // byte-identically to the pre-BLR behavior.
            //
            // The compiled ExecutionContract is the approved execution surface for
            // this request. It is recorded on the audit trail now and is not yet
            // consumed by grounding or enforcement — the runtime gate arrives in
            // Step 2 and the grounding swap in Step 4, so prompts and execution are
            // unchanged by this step.
            reasoningEventBus.phaseStarted(runKey, ProgressPhase.METADATA);
            // Zevra Cognitive Runtime baseline — measurement only: wall-clock time for
            // AgentBrain.resolve, which is PostgreSQL retrieval + Java assembly PLUS, whenever
            // concept-scoped narrowing applies, the Stage 1 LLM call's own latency (that call's
            // own latency is separately captured as its own LLM_METRIC line — this figure is not
            // a substitute for that, it's the wall-clock cost of the whole resolve() call).
            long agentBrainResolveStartNanos = System.nanoTime();
            ResolvedBusinessModel businessModel = agentBrain.resolve(
                    agent != null ? agent.agentKey() : null, connKeys, domainKeys, raw, conversationId, memoryAvailable);
            long agentBrainResolveMs = (System.nanoTime() - agentBrainResolveStartNanos) / 1_000_000;
            log.info("RETRIEVAL_TIMING stage=agentBrainResolve wallClockMs={} "
                            + "note=includesStage1LlmCallLatencyWhenConceptScoped",
                    agentBrainResolveMs);

            // Concept-Level Disjunctive Ambiguity design — CRITICAL DOWNSTREAM BOUNDARY: when
            // Agent Brain's own Stage 1 call explicitly marked this question ambiguous between
            // mutually exclusive business concepts (see ResolvedBusinessModel
            // #conceptAmbiguityClarification()), the request terminates here, into a concept-level
            // clarification answer — BEFORE any ExecutionContract is compiled, any physical
            // metadata is retrieved, or the reasoning/decision-routing machinery runs. The
            // clarification question itself is relayed verbatim from Agent Brain's own response;
            // Java neither constructs nor chooses it.
            if (businessModel.conceptAmbiguityClarification().isPresent()) {
                String clarification = businessModel.conceptAmbiguityClarification().get();
                reasoningEventBus.phaseCompleted(runKey, ProgressPhase.METADATA);
                runRepository.update(runKey, clarification, "ASK_CLARIFICATION", "COMPLETE", null);
                reasoningEventBus.publish(runKey, "answer_ready", Map.of("answer", clarification));
                reasoningEventBus.complete(runKey);
                return buildResponse(conversationId, runKey, clarification, "ASK_CLARIFICATION",
                        agent, routingConfidence, false, List.of(), List.of(), List.of(), List.of(), List.of());
            }

            ResolvedQuestion resolved = businessModel.resolution();
            ExecutionContract executionContract = executionContractBuilder.compile(businessModel);

            // The TABLE SCHEMA grounding is now rendered by the shared PromptAssembler from the
            // contract's PromptContext — the same pipeline the autonomous-agent path uses. The
            // conversational policy renders the full grounding (schema-qualified, with connection
            // key, data types, and value domains) within the entity-context budget.
            PromptContext promptContext = promptContextBuilder.build(executionContract);
            reasoningEventBus.phaseCompleted(runKey, ProgressPhase.METADATA);

            // STEP 6: Memory retrieval — semantic search on the user's intent, not the file.
            // memChunks was already computed above (Decision Router absorption needs it before
            // Stage 1 runs) — this phase marker is purely for SSE progress display and is
            // otherwise unchanged.
            reasoningEventBus.phaseStarted(runKey, ProgressPhase.RETRIEVAL);

            // STEP 7: Semantic + Anomaly + Findings context.
            // The semantic context also carries entity/vocabulary → table bindings.
            //
            // Downstream Context Boundary (Concept-Scoped Metadata Narrowing): when AgentBrain's
            // Stage 1/2 concept-scoped resolution actually produced businessModel (see
            // ResolvedBusinessModel#conceptScoped()), THIS channel must be bounded by the same
            // Stage-2-resolved object scope PromptAssembler already renders the physical schema
            // from — never the broader, domain-wide entity retrieval. That domain-wide call is
            // preserved byte-for-byte as the fallback whenever concept-scoping did not apply
            // (no active pack, no tenant concept catalog, resolver unavailable, or any of the
            // other conditions AgentBrain already falls back on). No new selection/ranking is
            // performed here — objectTargets().keySet() is exactly Stage 2's own resolved set.
            SemanticService.SemanticContext semantic = businessModel.conceptScoped()
                    ? semanticService.semanticContextForObjectKeys(
                            new ArrayList<>(businessModel.objectTargets().keySet()))
                    : semanticService.semanticContextWithBindings(domainKeys, raw);
            String semCtx = semantic.contextText();
            List<OperationalFinding> findings = reasoningRepository.findRecentFindings(domainKeys, 5);
            String anomalyCtx = baselineService.getAnomalyContext(domainKeys);
            reasoningEventBus.phaseCompleted(runKey, ProgressPhase.RETRIEVAL);

            // STEP 8: Write intent boundary — check user's question only
            if (isWriteIntent(raw)) {
                String ans = "Zevra is a read-only operational intelligence platform. I can help you " +
                        "investigate and understand your business data, but cannot perform modifications. " +
                        "Use /request-source to request workflow integrations.";
                runRepository.update(runKey, ans, "READ_ONLY_BOUNDARY", "COMPLETE", null);
                reasoningEventBus.publish(runKey, "answer_ready", Map.of("answer", ans));
                reasoningEventBus.complete(runKey);
                return buildResponse(conversationId, runKey, ans, "READ_ONLY_BOUNDARY",
                        agent, routingConfidence, false, List.of(), List.of(), List.of(), List.of(), List.of());
            }

            // STEP 9: Prior result check
            Optional<String> priorSnapshot = runRepository.latestResultSnapshot(conversationId);

            // STEP 10: Routing decision. Decision Router absorption: when the combined
            // Persistent Knowledge / File Search call (Stage 1) already produced a routing
            // decision, it is relayed VERBATIM here — Java parses and consumes it, never
            // overrides or second-guesses it, and the separate Decision Router LLM call is
            // skipped entirely for this request. Only when routing is absent (Stage 1
            // inapplicable, the legacy catalog-in-prompt fallback ran, the flag is off, or the
            // model's own routing field was invalid/unparseable) does the legacy Decision Router
            // call still run — this is the ONLY path that reaches getLlmDecision() today.
            Map<String, Object> decision;
            boolean decisionRouterCalled;
            if (businessModel.routingDecision().isPresent()) {
                ConceptScopedMetadataResolver.RoutingDecision routing = businessModel.routingDecision().get();
                decision = Map.of("type", routing.type(), "clarification_question", routing.clarificationQuestion());
                decisionRouterCalled = false;
            } else {
                // This is the key: the router sees "do these orders exist in the system?" and
                // naturally picks QUERY_LIVE_DATA. It doesn't need to see the CSV to decide that.
                decision = getLlmDecision(raw, memChunks, promptContext, semantic,
                        findings, anomalyCtx, history, priorSnapshot.isPresent(), agent, resolved, conversationContext,
                        businessModel.conceptScoped(), businessModel.objectTargets().keySet());
                decisionRouterCalled = true;
            }
            // Observability (Decision Router absorption): distinguishes, without logging the
            // question or any routing/clarification content, whether this request's routing
            // decision came from the combined Persistent Knowledge call or the legacy Decision
            // Router LLM call.
            log.info("DECISION_SOURCE conversationId={} source={} decisionRouterCalled={}",
                    conversationId, decisionRouterCalled ? "DECISION_ROUTER_LEGACY" : "PERSISTENT_KNOWLEDGE_COMBINED",
                    decisionRouterCalled);
            String decisionType = (String) decision.getOrDefault("type", "ANSWER_FROM_MEMORY");

            String answer;
            // The model's own semantic decomposition of `answer` (see composeAnswer) — null for
            // every decision type except QUERY_LIVE_DATA/HYBRID_DOC_AND_DATA/ANSWER_FROM_PRIOR_RESULTS
            // with real rows, where structured composition actually ran. ResponseArtifactsBuilder
            // treats null as "no LLM semantics available" and falls back to its legacy heuristic.
            StructuredAnswer llmSemantics = null;
            List<Map<String, Object>> asyncOps        = new ArrayList<>();
            List<Map<String, Object>> queryData        = new ArrayList<>();
            // Every row-bearing investigation step's own rows, preserved independently — see
            // ReasoningEngine.ReasoningResult#investigationDatasets. Empty for every decision type
            // that doesn't run the reasoning loop (nothing to preserve), never null.
            List<com.sei.nexus.reasoning.InvestigationDataset> investigationDatasets = List.of();
            // The model's UI-content plan, resolved against `investigationDatasets` above (see
            // ChatService#resolveSections) — empty for every decision type that doesn't run
            // composeAnswer's sections-based contract, never null.
            List<ResponseArtifacts.Section> resolvedSections = List.of();
            List<Map<String, Object>> reasoningSteps   = new ArrayList<>();
            // May now always be empty: LearningContextBuilder no longer injects Postgres-mapping
            // vocabulary (that reaches the LLM exclusively via File Search over the tenant's
            // Vector Store), and nothing else in this method attributes learnings today. Left in
            // place pending a future File-Search-driven attribution mechanism.
            List<String>              learningsApplied  = new ArrayList<>();
            String resultSnapshot = null;

            // ── PRO-31 explainability: every successful resolution joins the
            // reasoning trace, regardless of decision type, so users can see
            // exactly how their business language was interpreted and from
            // which tier the mapping came.
            //
            // Business Concept Transparency: each resolution also carries the business
            // object(s) its target table belongs to, when derivable — a pure metadata
            // join against the already-compiled ExecutionContract (table name parsed from
            // the resolution's own target string; no SQL parsing, no new repository, no
            // change to BusinessLanguageResolver). Empty when not derivable — never guessed.
            Map<String, List<String>> tableToBusinessObjects = buildTableToBusinessObjectIndex(executionContract);
            for (ResolvedQuestion.Resolution r : resolved.resolutions()) {
                reasoningSteps.add(Map.of(
                        "stepNo",         0,
                        "type",           "resolution",
                        "description",    "\"" + r.surface() + "\" → " + r.target(),
                        "surface",        r.surface(),
                        "kind",           r.kind().label(),
                        "target",         r.target(),
                        "tier",           r.tier(),
                        "source",         r.sourceLabel(),
                        "businessObjects", businessObjectsForResolution(r, tableToBusinessObjects)));
            }

            // Runtime Progress Projection: QUERY_LIVE_DATA / HYBRID_DOC_AND_DATA run the governed
            // execution phase inside their own case (bracketing reasoningEngine.reason); every
            // other decision type has no execution step, so "forming business judgment" starts
            // here, directly after retrieval.
            // ANSWER_FROM_PRIOR_RESULTS is kept here only as a defensive alias in case the
            // router still emits it (prompt/model drift) — it is no longer a distinct decision
            // type in DECISION_SYSTEM_PROMPT and carries no special behavior of its own. It is
            // routed through the exact same investigation path as QUERY_LIVE_DATA; whether prior
            // evidence is enough is decided by ReasoningEvaluator (see reasoningEngine.reason
            // below, seeded from the existing ExecutionReference), not by this switch.
            boolean hasExecutionPhase = "QUERY_LIVE_DATA".equals(decisionType)
                    || "HYBRID_DOC_AND_DATA".equals(decisionType)
                    || "ANSWER_FROM_PRIOR_RESULTS".equals(decisionType);
            if (!hasExecutionPhase) {
                reasoningEventBus.phaseStarted(runKey, ProgressPhase.REASONING);
            }

            switch (decisionType) {
                case "ANSWER_FROM_MEMORY" -> {
                    // enrichedQuestion: if the user uploaded a file and asked about it, this path
                    // has the file content available so the AI can summarise / translate / explain it.
                    answer = answerFromMemory(enrichedQuestion, memChunks, semCtx, agent);
                }
                case "ASK_CLARIFICATION" -> {
                    answer = (String) decision.getOrDefault("clarification_question",
                            "Could you provide more context about what you're looking for?");
                }
                case "KNOWLEDGE_GAP" -> {
                    String gapKey = Keys.uniqueKey("gap");
                    KnowledgeGap gap = new KnowledgeGap(gapKey,
                            agent != null ? agent.domainKeys() : null,
                            "MISSING_KNOWLEDGE", runKey, raw,
                            "No approved knowledge or data sources found for this question.",
                            null, "OPEN", null, null, null, null);
                    knowledgeGapRepository.save(gap);
                    answer = "I don't have approved knowledge or data sources for this question. " +
                            "Use /knowledge to propose adding this, or /request-source to request a data connection.";
                    decisionType = "KNOWLEDGE_GAP";
                }
                case "QUERY_LIVE_DATA", "HYBRID_DOC_AND_DATA", "ANSWER_FROM_PRIOR_RESULTS" -> {
                    // Create reasoning session
                    String sessionKey = Keys.uniqueKey("rsession");
                    ReasoningSession session = new ReasoningSession(sessionKey, runKey, conversationId,
                            agent != null ? agent.agentKey() : null,
                            agent != null ? agent.domainKeys() : null,
                            raw, null, "ACTIVE", null, null, Instant.now(), null);
                    reasoningRepository.saveSession(session);

                    // Build schema context string for the iterative planner.
                    // enrichedQuestion (with attachment content) is passed separately so
                    // the planner can extract WHERE IN values from uploaded files.
                    String playbookCtx = "";
                    if (agent != null) {
                        List<AgentPlaybook> playbooks = agentRepository.findPlaybooksByAgent(agent.agentKey());
                        if (!playbooks.isEmpty()) playbookCtx = "Playbook: " + playbooks.get(0).investigationSteps();
                    }
                    // Zevra Cognitive Runtime baseline — measurement only: buildContextSummary
                    // makes no LLM call itself (confirmed by the investigation report), so this
                    // wall-clock figure is purely PostgreSQL retrieval (already-completed calls
                    // like SemanticService/KnowledgeGraphService feed their results in as already-
                    // fetched parameters, but promptAssembler.assemble and the graph/semantic
                    // rendering happen inside) + Java string-building time, isolated from any LLM
                    // latency.
                    long buildContextStartNanos = System.nanoTime();
                    String schemaCtx = buildContextSummary(raw, memChunks, promptContext, semantic, findings,
                            anomalyCtx, false, history, agent, resolved, conversationContext,
                            businessModel.conceptScoped(), businessModel.objectTargets().keySet(),
                            businessModel.resolvedConceptKeys());
                    long buildContextMs = (System.nanoTime() - buildContextStartNanos) / 1_000_000;
                    log.info("RETRIEVAL_TIMING stage=buildContextSummary wallClockMs={} note=noLlmCallInsideThisMethod",
                            buildContextMs);
                    if (!playbookCtx.isBlank()) schemaCtx = schemaCtx + "\nPlaybook:\n" + playbookCtx;

                    // ── Phase 3: inject known conversation corrections into the planner context ──
                    // Promoted learned-mapping vocabulary is deliberately NOT injected here anymore —
                    // it reaches the LLM exclusively via native File Search over the tenant's Vector
                    // Store (see ConceptKnowledgeMaterializationService/
                    // ConceptKnowledgeSynchronizationService). agentDomainKey is still needed below
                    // for learnFromRun/captureLiteralBinding, independent of this injection.
                    String agentDomainKey = agent != null ? agent.domainKeys() : null;
                    long learningCtxStartNanos = System.nanoTime();
                    LearningContextBuilder.LearningContext learningCtx =
                            learningContextBuilder.build(conversationId);
                    long learningCtxMs = (System.nanoTime() - learningCtxStartNanos) / 1_000_000;
                    log.info("RETRIEVAL_TIMING stage=learningContextBuilder wallClockMs={} note=noLlmCallInsideThisMethod",
                            learningCtxMs);
                    if (!learningCtx.isEmpty()) {
                        schemaCtx = schemaCtx + "\n\n" + learningCtx.contextText();
                    }

                    // ── Conversation Memory → Chat (Phase 3): an isolated, dedicated
                    // memory-selection call — NOT merged into getLlmDecision(), NOT a change
                    // to ReasoningEngine/ReasoningPlanner. Empty string (no-op) when disabled,
                    // when the roster is empty, or on any failure — see buildMemorySelectionContext.
                    String memoryCtx = buildMemorySelectionContext(raw, conversationId);
                    if (!memoryCtx.isBlank()) {
                        schemaCtx = schemaCtx + "\n\n" + memoryCtx;
                    }

                    // Run the iterative reasoning loop (Phase 2).
                    // The engine generates one SQL step at a time, executes it through the
                    // governance chain, evaluates whether the evidence is sufficient, and
                    // continues until the evaluator says SUFFICIENT, DEAD_END, or MAX_STEPS.
                    boolean gateOff     = "off".equalsIgnoreCase(contractGateMode);
                    boolean gateEnforced = "enforce".equalsIgnoreCase(contractGateMode);
                    reasoningEventBus.phaseStarted(runKey, ProgressPhase.EXECUTION);
                    // Seed evidence from the conversation's last execution (already fetched above
                    // as prevExecution) — reuses the existing ExecutionReference record rather than
                    // introducing new persistence. ReasoningEngine evaluates it against THIS
                    // question before planning anything new; Planner is unaware either way.
                    ReasoningEngine.ReasoningResult reasonResult = reasoningEngine.reason(
                            raw, enrichedQuestion, sessionKey, schemaCtx, runKey, userEmail, forceAsync,
                            buildLiteralScope(resolved),
                            gateOff ? null : executionContract, gateEnforced,
                            conversationId, parentExecutionId, prevExecution.orElse(null),
                            // Missing-Column Metadata Request validation always sees the full
                            // resolved object set, independent of the gate migration mode above —
                            // see ReasoningEngine#reason's resolvedObjects javadoc.
                            executionContract);
                    reasoningEventBus.phaseCompleted(runKey, ProgressPhase.EXECUTION);

                    // Conversation Memory write-side (Phase 4): mechanical, post-execution
                    // registration of the objects this turn's SQL actually touched — never a
                    // semantic decision. See registerExecutedBusinessObjects() for the exact
                    // registration point and its documented multi-step limitation.
                    registerExecutedBusinessObjects(conversationId);

                    resultSnapshot = reasonResult.resultSnapshot();
                    queryData      = reasonResult.queryData();
                    investigationDatasets = reasonResult.investigationDatasets();

                    // Convert EvidenceStore steps to the execResults format composeAnswer expects
                    List<Map<String, Object>> execResults = evidenceToExecResults(reasonResult.evidence());

                    reasoningEventBus.phaseStarted(runKey, ProgressPhase.REASONING);
                    StructuredAnswer composed = composeAnswer(raw, attachmentSummary, execResults,
                            investigationDatasets, memChunks, semCtx,
                            findings, anomalyCtx, agent, "HYBRID_DOC_AND_DATA".equals(decisionType));
                    answer = composed.answer();
                    llmSemantics = composed;
                    // The ONLY Java decision in this path: does a claimed "step-N" reference
                    // exist? Never which dataset to reference, never whether to display it — see
                    // resolveSections' javadoc.
                    resolvedSections = resolveSections(composed.sections(), investigationDatasets, runKey);
                    reasoningEventBus.phaseCompleted(runKey, ProgressPhase.REASONING);
                    reasoningEventBus.phaseStarted(runKey, ProgressPhase.COMPOSITION);

                    // Conclude the reasoning session and, when the investigation was backed by real
                    // query results, persist an OperationalFinding — so the homepage reflects real
                    // intelligence (Open findings / Recommendations / Signals) instead of staying empty.
                    persistInvestigationOutcome(sessionKey, agent, raw, answer, queryData, conversationId);

                    // Phase 3 Step 2: record what the business-object gate would have rejected,
                    // so the migration can be measured before enforcement is switched on.
                    if (!reasonResult.shadowGateFindings().isEmpty()) {
                        log.warn("Contract gate (shadow) findings for run {}: {}",
                                runKey, reasonResult.shadowGateFindings());
                        runRepository.saveEvidence(Keys.uniqueKey("ev"), runKey, "CONTRACT_GATE_SHADOW",
                                toJson(Map.of("contract_id", executionContract.contractId(),
                                        "findings", reasonResult.shadowGateFindings())));
                    }

                    // Notify SSE clients the answer is ready, then close the stream
                    reasoningEventBus.phaseCompleted(runKey, ProgressPhase.COMPOSITION);
                    reasoningEventBus.publish(runKey, "answer_ready", Map.of("answer", answer));
                    reasoningEventBus.complete(runKey);

                    // ── Unified Learning Event pipeline: exactly two implicit triggers ──
                    // Normal successful queries, follow-ups, browsing, filters, etc. perform NO
                    // learning call at all — not even a no-op TermExtractor invocation. Pick the
                    // SQL from the most data-rich step (unchanged precondition: a successful query
                    // is a prerequisite for either trigger, never sufficient on its own).
                    String bestSql = reasonResult.evidence().getSteps().stream()
                            .filter(s -> !s.rows().isEmpty() && s.sql() != null)
                            .max(java.util.Comparator.comparingInt(s -> s.rows().size()))
                            .map(s -> s.sql())
                            .orElse(null);
                    if (bestSql != null) {
                        // Trigger 1 — CLARIFICATION_RESOLUTION: this run succeeded immediately
                        // after the prior run in this conversation ended in a clarification
                        // request (deterministic, persisted signal — see
                        // SemanticLearningService#isImmediatelyPriorRunAClarification).
                        if (semanticLearningService.isImmediatelyPriorRunAClarification(conversationId, runKey)) {
                            semanticLearningService.dispatch(new com.sei.nexus.semantic.LearningEvent(
                                    com.sei.nexus.semantic.LearningEvent.Source.CLARIFICATION_RESOLUTION,
                                    raw, bestSql, agentDomainKey,
                                    businessModel.resolvedConceptKeys(), runKey, conversationId));
                        }
                        // Trigger 2 — SEMANTIC_CORRECTION: existing CorrectionDetector mechanism,
                        // unchanged, now funneled into the same Learning Event pipeline.
                        if (conversationId != null && !conversationId.isBlank()) {
                            semanticLearningService.detectAndSaveCorrectionForRun(
                                    runKey, raw, conversationId, agentDomainKey,
                                    businessModel.resolvedConceptKeys());
                        }
                    }

                    // ── PRO-33: successful validated literal bindings enter the
                    // existing governed learning lifecycle (LearnedMapping upsert
                    // → nightly thresholds → review) — never auto-promoted here.
                    for (ReasoningEngine.ValidatedBinding vb : reasonResult.validatedBindings()) {
                        semanticLearningService.captureLiteralBinding(
                                runKey, agentDomainKey, vb.surface(), vb.column(), vb.value());
                    }

                    // ── PRO-33 explainability: validated literal bindings join the
                    // trace beside BLR's resolution entries — "TX" → Texas, chosen
                    // by the AI from offered values and validated against the domain.
                    for (ReasoningEngine.ValidatedBinding vb : reasonResult.validatedBindings()) {
                        reasoningSteps.add(Map.of(
                                "stepNo",      0,
                                "type",        "literal",
                                "description", "\"" + vb.surface() + "\" → " + vb.column()
                                                + " = '" + vb.value() + "'",
                                "surface",     vb.surface(),
                                "column",      vb.column(),
                                "value",       vb.value(),
                                "outcome",     "validated",
                                "source",      vb.authoritative()
                                        ? "AI choice (validated: legal values)"
                                        : "AI choice (validated: observed values)"));
                    }

                    // Collect step summaries for the frontend reasoning trace
                    for (EvidenceStore.StepEvidence s : reasonResult.evidence().getSteps()) {
                        reasoningSteps.add(Map.of(
                                "stepNo",             s.stepNo(),
                                "description",        s.description() != null ? s.description() : "",
                                "sql",                s.sql()         != null ? s.sql()         : "",
                                "rowCount",           s.rows().size(),
                                "rowSummary",         s.rowSummary()          != null ? s.rowSummary()          : "",
                                // This step's own result — successful query / metadata retrieval /
                                // declined / rejected / failed — never blended with the separate
                                // "should we keep investigating" signal below (Investigation-Step
                                // Semantics; see EvidenceStore's outcome-vs-evaluatorDecision javadoc).
                                "outcome",             s.outcome()             != null ? s.outcome()             : "",
                                // The evaluator's verdict on OVERALL accumulated sufficiency — present
                                // only for a step actually evaluated; the reason for the NEXT action,
                                // not this step's own success/failure.
                                "evaluatorDecision",  s.evaluatorDecision()   != null ? s.evaluatorDecision()   : "",
                                "evaluatorRationale", s.evaluatorRationale()  != null ? s.evaluatorRationale()  : "",
                                "executionMs",        s.executionMs()));
                    }
                }
                default -> {
                    answer = "I was unable to determine how to answer this question with available approved sources.";
                }
            }

            if (!hasExecutionPhase) {
                // QUERY_LIVE_DATA / HYBRID_DOC_AND_DATA already published their own
                // reasoning/composition-complete + answer_ready above; every other decision
                // type closes it out here, right after its answer was actually produced.
                reasoningEventBus.phaseCompleted(runKey, ProgressPhase.REASONING);
                reasoningEventBus.phaseStarted(runKey, ProgressPhase.COMPOSITION);
                reasoningEventBus.phaseCompleted(runKey, ProgressPhase.COMPOSITION);
                reasoningEventBus.publish(runKey, "answer_ready", Map.of("answer", answer));
                reasoningEventBus.complete(runKey);
            }

            runRepository.update(runKey, answer, decisionType, "COMPLETE", resultSnapshot);
            runRepository.saveEvidence(Keys.uniqueKey("ev"), runKey, "ROUTING",
                    toJson(Map.of("decision_type", decisionType,
                            "agent", agent != null ? agent.agentKey() : "none",
                            "memory_chunks", memChunks.size(),
                            // Phase 3 Step 1: the approved execution surface compiled for
                            // this request, traceable for audit, replay, and lineage.
                            "contract_id", executionContract.contractId(),
                            "contract_objects", executionContract.semanticView().businessObjects().size(),
                            // PRO-31: resolution provenance joins the audit trail
                            "resolutions", resolved.resolutions().stream()
                                    .map(r -> "\"" + r.surface() + "\" = " + r.kind().label()
                                            + ": " + r.target() + " [" + r.tier() + "]")
                                    .toList())));

            List<Map<String, Object>> quickRefs = buildQuickRefinements(decisionType, raw);
            return buildResponse(conversationId, runKey, answer, decisionType,
                    agent, routingConfidence, "KNOWLEDGE_GAP".equals(decisionType),
                    quickRefs, asyncOps, queryData, investigationDatasetsToMaps(investigationDatasets),
                    reasoningSteps, learningsApplied, requestAnalysis.intentType(), llmSemantics,
                    resolvedSections);

        } catch (Exception e) {
            // The raw exception (SQL, table names, driver detail) is kept for operators only —
            // never surfaced to the user. The user sees a fixed, generic message.
            log.error("Chat orchestration failed for run {}: {}", runKey, e.getMessage(), e);
            runRepository.update(runKey, GENERIC_INVESTIGATION_ERROR, "ERROR", "FAILED", null);
            // Close the SSE stream rather than leaving it open for the 5-minute buffer TTL —
            // the frontend learns of the failure from the rejected POST, not from this stream.
            reasoningEventBus.complete(runKey);
            try {
                runRepository.saveEvidence(Keys.uniqueKey("ev"), runKey, "ERROR_DETAIL",
                        toJson(Map.of("error", String.valueOf(e.getMessage()))));
            } catch (Exception ignored) { /* audit is best-effort; never mask the original failure */ }
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_INVESTIGATION_ERROR);
        }
    }

    // =========================================================================
    // Business Concept Transparency — pure post-hoc projection, reads only
    // (never mutates) the already-compiled ExecutionContract. No SQL parsing,
    // no new repository, no change to BusinessLanguageResolver or AgentBrain.
    // =========================================================================

    /** {@code physical table (lowercase, unqualified) -> business object name(s)} bound to it
     *  in this request's contract. Built once per request from data already computed by
     *  {@link ExecutionContractBuilder}; nothing here is queried or resolved anew. */
    private static Map<String, List<String>> buildTableToBusinessObjectIndex(ExecutionContract contract) {
        Map<String, List<String>> index = new java.util.HashMap<>();
        Map<String, ExecutionBindings.ExecutionTarget> objectBindings =
                contract.executionBindings().objectBindings();
        for (var bo : contract.semanticView().businessObjects()) {
            ExecutionBindings.ExecutionTarget target = objectBindings.get(bo.objectKey());
            if (target == null || target.table() == null) continue;
            index.computeIfAbsent(target.table().toLowerCase(), k -> new ArrayList<>())
                 .add(bo.businessName());
        }
        return index;
    }

    /** The business object(s) a resolution's target table belongs to, or an empty list when
     *  the resolution carries no table reference (VALUE kind) or the table isn't in this
     *  request's contract — never guessed, only ever a real lookup against real bindings. */
    private static List<String> businessObjectsForResolution(
            ResolvedQuestion.Resolution r, Map<String, List<String>> tableToBusinessObjects) {
        String table = switch (r.kind()) {
            case COLUMN -> {
                int dot = r.target().lastIndexOf('.');
                yield dot > 0 ? r.target().substring(0, dot) : null;
            }
            case ENTITY -> {
                int start = r.target().indexOf("(table: ");
                int end   = r.target().indexOf(')', start);
                if (start < 0 || end < 0) yield null;
                String qualified = r.target().substring(start + "(table: ".length(), end);
                int dot = qualified.lastIndexOf('.');
                yield dot > 0 ? qualified.substring(dot + 1) : qualified;
            }
            default -> null; // VALUE — no table reference to resolve
        };
        if (table == null) return List.of();
        return tableToBusinessObjects.getOrDefault(table.toLowerCase(), List.of());
    }

    // =========================================================================
    // Agent resolution
    // =========================================================================

    private NexusAgent resolveAgent(String agentKey, String question, List<NexusRun> history) {
        if (agentKey != null && !agentKey.isBlank()) {
            return agentRepository.findByKey(agentKey).orElse(null);
        }
        List<NexusAgent> active = agentRepository.findActive();
        if (active.isEmpty()) return null;
        if (active.size() == 1) return active.get(0);
        // LLM-based routing
        try {
            StringBuilder ctx = new StringBuilder("Active agents:\n");
            for (NexusAgent a : active) {
                ctx.append("- ").append(a.agentKey()).append(": ").append(a.purpose()).append("\n");
            }
            String prompt = "Question: " + question + "\n\n" + ctx +
                    "\nRespond with JSON only: {\"agent_key\": \"...\", \"confidence\": 0.9}";
            com.sei.nexus.ai.LlmCallTag.set("AGENT_ROUTER");
            String resp = aiClient.chatWithJsonFast(List.of(ChatMessage.user(prompt)),
                    "You are an agent router. Select the most appropriate agent for the question. Return JSON only.");
            Map<?, ?> parsed = objectMapper.readValue(extractJson(resp), Map.class);
            String chosen = (String) parsed.get("agent_key");
            return active.stream().filter(a -> a.agentKey().equals(chosen)).findFirst().orElse(active.get(0));
        } catch (Exception e) {
            log.warn("Agent routing via LLM failed: {}", e.getMessage());
            return active.get(0);
        }
    }

    // =========================================================================
    // Intent detection
    // =========================================================================

    private boolean isWriteIntent(String q) {
        String u = q.toUpperCase();
        return u.matches(".*\\b(DELETE|UPDATE|INSERT|MODIFY|CHANGE|CREATE|DROP|TRUNCATE|APPROVE|REJECT|CANCEL|VOID|REVERSE)\\b.*");
    }

    // =========================================================================
    // LLM decision
    // =========================================================================

    /**
     * Decision-router system prompt. Package-private constant so tests can pin
     * the routing contract.
     *
     * <p>ANSWER_FROM_PRIOR_RESULTS was removed as a decision type: it asked this router to
     * judge whether previously-gathered evidence is sufficient to answer a new question,
     * without the router ever seeing that evidence — a judgment {@link ReasoningEvaluator}
     * already exists to make, from the actual evidence, for every other decision type. Every
     * follow-up that needs investigation now routes to QUERY_LIVE_DATA; ReasoningEngine seeds
     * evidence from the conversation's last {@link com.sei.nexus.runtime.ExecutionReference}
     * and asks the evaluator whether it already answers the new question before planning
     * anything new. This router now answers one question only: does this need investigation,
     * or not.
     *
     * <p>Rule 6 is the PRO-34 fix: the LITERAL CANDIDATES section (PRO-32/33)
     * renders into this router's context too, and its text — "matched no known
     * term" plus the planner-directed clarification instruction — read, to a
     * router with no rule about it, like grounds for ASK_CLARIFICATION. That
     * pre-empted the approved flow: the SQL planner (which owns the
     * constrained literal choice) never ran. Rule 6 makes the section's
     * meaning explicit at the routing layer: candidates present ⇒ the term is
     * resolvable downstream ⇒ route to live data.
     *
     * @deprecated Decision Router absorption: the same five-value routing decision this prompt
     * produces is now produced, when possible, by the combined Persistent Knowledge / File
     * Search Stage 1 call itself — see {@link
     * com.sei.nexus.agentbrain.ConceptScopedMetadataResolver#resolveObjectKeysWithRouting} and
     * {@code docs/ai/decision-router-absorption.md}. Persistent Knowledge / File Search is now
     * the sole Stage 1 semantic-resolution implementation (no feature flag, no legacy Stage 1
     * fallback) — but the combined call's own {@code routing} field can still be absent
     * (invalid/unparseable, or Stage 1 does not apply at all for this connection: no active pack,
     * no tenant concept catalog), and in that case {@link #getLlmDecision} is still the routing
     * fallback — only ever called from {@link #ask} when {@code businessModel.routingDecision()}
     * is empty. Not removed and not to be extended with new functionality. This is a Decision
     * Router / routing concern, distinct from and unaffected by Stage 1 concept-selection's own
     * legacy-path removal.
     */
    @Deprecated
    static final String DECISION_SYSTEM_PROMPT = """
            You are the SEI Nexus orchestration engine. Decide the best answer mode.
            Return JSON only:
            {
              "type": "ANSWER_FROM_MEMORY|QUERY_LIVE_DATA|HYBRID_DOC_AND_DATA|ASK_CLARIFICATION|KNOWLEDGE_GAP",
              "intentType": "OPERATIONAL_INVESTIGATION|ANALYTICAL|INFORMATIONAL|FOLLOW_UP",
              "requiresExecution": true,
              "requiresMemory": true,
              "requiresClarification": false,
              "clarification_question": ""
            }

            Routing rules (in priority order):
            1. Use QUERY_LIVE_DATA if the question needs fresh data from the database — including
               EVERY follow-up question in an existing conversation, whether it asks for different
               metrics, different filters, different entities, more detail than before, or simply
               to explain/justify the previous answer. This router does not decide whether prior
               evidence already answers a follow-up — the reasoning engine decides that from the
               actual evidence. When a question is a follow-up to an investigation, route it here.
            2. Use ANSWER_FROM_MEMORY if document memory can answer without live data.
            3. Use HYBRID_DOC_AND_DATA for complex questions needing both memory and live data.
            4. Use KNOWLEDGE_GAP if no knowledge or data sources are available at all.
            5. Use ASK_CLARIFICATION ONLY if the question is completely ambiguous AND there is no prior conversation context.
            6. A LITERAL CANDIDATES section means an unfamiliar term in the question already has
               stored candidate values — the SQL planner will choose the exact value and the runtime
               will validate it. Such a question is NOT ambiguous: use QUERY_LIVE_DATA. Do NOT use
               ASK_CLARIFICATION for a term that has literal candidates; clarification for those
               terms belongs to the SQL planner only when none of the offered values fits.
            Key rule: when in doubt between investigating and answering from memory,
            always choose QUERY_LIVE_DATA. It is always better to investigate than to
            give an answer without evidence behind it.
            RESOLUTIONS map the user's terms to this tenant's canonical names and values.
            Prefer them over your own interpretation of those terms.
            Literals filtered on columns with listed legal values MUST be copied exactly
            from those lists or from the user's question — never invented.
            """;

    /**
     * Conversation Memory selection prompt (Chat integration, Phase 3). Deliberately a
     * SEPARATE, independent system prompt from {@link #DECISION_SYSTEM_PROMPT} — an empirical
     * prototype (live-tested, not merely designed) showed that folding memory-selection
     * instructions into that prompt measurably regressed its existing ASK_CLARIFICATION vs
     * QUERY_LIVE_DATA classification. This call's only job is selecting zero or more
     * already-remembered entity_keys; it never routes, classifies, plans SQL, or composes an
     * answer. Package-private so tests can pin the contract, same convention as
     * DECISION_SYSTEM_PROMPT.
     */
    static final String MEMORY_SELECTION_SYSTEM_PROMPT = """
            You select which already-remembered items (if any) are needed to answer the current question.

            You will be given:
            1. The current question.
            2. A list of items already known in this conversation (entity_key | business_name | type).

            Return ONLY this JSON, nothing else:
            {"entity_keys": ["..."]}

            Rules:
            - Only return entity_key values that appear EXACTLY in the supplied list. Never invent, guess, or modify a key.
            - Return a key only if it is actually REQUIRED to answer the current question — not merely because its name or a synonym appears in the question text.
            - If the question needs something not in the list, do not return it — leave it out entirely.
            - If nothing in the list is needed, return an empty list.
            - If the list is empty, always return an empty list.
            - Do not explain. Do not add any other field. Do not ask for clarification. Return JSON only.
            """;

    /**
     * Builds the Conversation Memory context block to append to the SQL planner's context —
     * or an empty string when the capability is disabled, the roster is empty, or anything
     * fails (fail-safe: memory selection is never allowed to fail the user's request).
     *
     * <p>Reuses the exact Core capability already implemented for Agent — {@link
     * ConversationMemoryService} and {@link BusinessWorldToolAdapter} — with zero new
     * implementation. Java never inspects {@code question} itself: the only Java-side logic
     * here is (a) an exact roster-membership check per key and (b) an exact-key authoritative
     * retrieval; the LLM alone decides which keys (if any) are needed.
     */
    private String buildMemorySelectionContext(String question, String conversationId) {
        if (!chatMemoryEnabled) return "";
        try {
            List<ConversationRosterEntry> roster = conversationMemoryService.list(conversationId);
            if (roster.isEmpty()) return "";

            String index = roster.stream()
                    .map(e -> e.entityKey() + " | " + e.businessName() + " | " + e.objectType())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            String prompt = "Question: " + question + "\n\nAlready known in this conversation:\n" + index;
            com.sei.nexus.ai.LlmCallTag.set("MEMORY_SELECTION");
            // Phase 1 Responses API migration: transport-only — same prompt/roster context, same
            // exact-roster-membership validation below. Model tiering (pre-production cost
            // optimization): selecting entity_keys by exact match from a short roster is
            // low-complexity — nexus.openai.memory-selection-model (defaults to gpt-4o-mini)
            // instead of the core chat model. Phase 4 Structured Outputs: the response now
            // carries an OpenAI strict json_schema (see #memorySelectionJsonSchema) guaranteeing
            // "entity_keys" is always a (possibly empty) array of strings — the exact-roster
            // membership check per key below is completely unchanged; no new cache key is
            // introduced here (Memory Selection has never had one, per Phase 1 scope).
            String resp = aiClient.respondForMemorySelection(List.of(ChatMessage.user(prompt)), MEMORY_SELECTION_SYSTEM_PROMPT,
                    "memory_selection", memorySelectionJsonSchema());

            Map<String, Object> parsed = objectMapper.readValue(extractJson(resp),
                    new TypeReference<Map<String, Object>>() {});
            Object rawKeys = parsed.get("entity_keys");
            if (!(rawKeys instanceof List<?> keys) || keys.isEmpty()) return "";

            StringBuilder ctx = new StringBuilder();
            for (Object k : keys) {
                String entityKey = String.valueOf(k);
                // Exact membership validation — an unknown/hallucinated key is silently
                // rejected, never substituted, never discovered. No fallback to Business
                // World discovery here: that remains a separate, LLM-directed concern.
                if (!conversationMemoryService.isMember(conversationId, entityKey)) continue;
                Optional<DataObject> obj = businessWorldToolAdapter.getBusinessObject(entityKey);
                if (obj.isEmpty()) continue;
                DataObject o = obj.get();
                ctx.append("Remembered object \"").append(entityKey).append("\" (")
                        .append(o.businessName()).append("): table ")
                        .append(o.schemaName() != null ? o.schemaName() + "." : "").append(o.tableName())
                        .append(o.purpose() != null && !o.purpose().isBlank() ? " — " + o.purpose() : "")
                        .append('\n');
            }
            if (ctx.isEmpty()) return "";
            return "Conversation memory (already-known objects the model selected for this turn):\n" + ctx;
        } catch (Exception e) {
            // Fail-safe: memory selection never fails the user's request. Same posture as
            // getLlmDecision()'s own catch-and-fallback below.
            log.warn("Conversation memory selection failed, continuing without it: {}", e.getMessage());
            return "";
        }
    }

    /**
     * The strict JSON Schema for {@link #MEMORY_SELECTION_SYSTEM_PROMPT}'s {@code
     * {"entity_keys": ["..."]}} contract — formalized as an OpenAI Structured-Outputs strict
     * schema (same idiom as {@link #dataAnswerJsonSchema}). STRUCTURE enforcement only:
     * guarantees {@code entity_keys} is always present as a (possibly empty) array of strings;
     * WHICH keys (if any) it selects remains entirely the model's judgment, and {@link
     * #buildMemorySelectionContext}'s own exact-roster-membership validation per key is
     * completely unchanged — an unknown/hallucinated key is still silently rejected there, never
     * enforced by this schema.
     *
     * <p>Package-private static seam — a pure function of no inputs, for direct unit testing.
     */
    static Map<String, Object> memorySelectionJsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("entity_keys", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("entity_keys"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * Conversation Memory write side (Chat integration, Phase 4). Mechanically registers the
     * Business Objects this turn's successful execution actually touched — the counterpart to
     * Phase 3's read-only {@link #buildMemorySelectionContext}. Java never decides WHAT belongs
     * in the roster (no inspection of the question, SQL text, or relevance); it only records
     * WHAT WAS successfully retrieved, exactly like {@code AgentToolRegistry}'s existing
     * {@code execGetBusinessObject} → {@code registerDiscovery} side effect for Agent.
     *
     * <p>Registration point: re-reads the same {@link com.sei.nexus.runtime.ExecutionReferenceRepository
     * #findLatestByConversation} call already used for follow-up grounding — now returning the
     * turn just completed — and registers every object key in its {@code businessObjectBindings}.
     *
     * <p><b>Known, accepted limitation (not solved in this phase):</b> {@code
     * findLatestByConversation} returns only the most recent {@code ExecutionReference}. If one
     * Chat turn executes multiple successful SQL steps touching different objects, only the
     * LAST step's objects are registered here — not the full union across the turn. Registering
     * the full union would require a hook inside {@code ReasoningEngine}'s per-step loop, which
     * this phase deliberately does not touch (frozen substrate). For the common single-step
     * turn this is fully correct.
     *
     * <p>Fail-safe throughout, matching {@code GovernedSqlRuntime}'s own best-effort convention
     * for {@code ExecutionReference} persistence: any failure here is caught and logged, never
     * propagated — a successful user answer must never be turned into a failure by this
     * secondary bookkeeping step.
     */
    private void registerExecutedBusinessObjects(String conversationId) {
        if (!chatMemoryEnabled) return;
        try {
            Optional<com.sei.nexus.runtime.ExecutionReference> latest =
                    executionReferenceRepository.findLatestByConversation(conversationId);
            if (latest.isEmpty()) return;

            for (String entityKey : latest.get().businessObjectBindings().keySet()) {
                Optional<DataObject> obj = businessWorldToolAdapter.getBusinessObject(entityKey);
                if (obj.isEmpty()) continue; // unknown object — skip, never invent
                conversationMemoryService.registerDiscovery(conversationId, entityKey,
                        obj.get().businessName(), BusinessWorldToolAdapter.OBJECT_TYPE_ENTITY);
            }
        } catch (Exception e) {
            log.warn("Conversation memory registration failed, continuing (result already succeeded): {}",
                    e.getMessage());
        }
    }

    /**
     * @deprecated Decision Router absorption: only called from {@link #ask} when {@code
     * businessModel.routingDecision()} is empty — i.e. the legacy fallback path. See {@link
     * #DECISION_SYSTEM_PROMPT}'s own deprecation note for the full explanation and retirement
     * condition. Not removed, not extended.
     */
    @Deprecated
    private Map<String, Object> getLlmDecision(String question, List<DocumentChunk> memChunks,
            PromptContext promptContext, SemanticService.SemanticContext semantic, List<OperationalFinding> findings,
            String anomalyCtx, List<NexusRun> history, boolean hasPrior, NexusAgent agent,
            ResolvedQuestion resolved, String executionGrounding,
            boolean conceptScoped, java.util.Set<String> objectKeyScope) {
        try {
            String ctx = buildContextSummary(question, memChunks, promptContext, semantic, findings, anomalyCtx,
                    hasPrior, history, agent, resolved, executionGrounding, conceptScoped, objectKeyScope);
            String prompt = "Question: " + question + "\n\nContext:\n" + ctx;
            com.sei.nexus.ai.LlmCallTag.set("DECISION_ROUTER");
            String resp = aiClient.chat(List.of(ChatMessage.user(prompt)), DECISION_SYSTEM_PROMPT);
            return objectMapper.readValue(extractJson(resp),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("LLM decision failed, defaulting to ANSWER_FROM_MEMORY: {}", e.getMessage());
            return Map.of("type", "ANSWER_FROM_MEMORY", "requiresExecution", false, "requiresMemory", true);
        }
    }

    // =========================================================================
    // Answer composition
    // =========================================================================

    private String answerFromMemory(String question, List<DocumentChunk> memChunks,
            String semCtx, NexusAgent agent) {
        StringBuilder ctx = new StringBuilder();
        memChunks.forEach(c -> ctx.append(c.chunkText()).append("\n\n"));
        if (!semCtx.isBlank()) ctx.append("Entity Context:\n").append(semCtx).append("\n\n");
        String prompt = "Question: " + question + "\n\nKnowledge:\n" + ctx;
        return nlComposer.compose(NaturalLanguageComposer.CompositionRequest.text(prompt,
                """
                You are Zevra, an enterprise intelligence AI briefing an executive. Answer using ONLY the
                provided knowledge. Lead with a single-sentence verdict (the conclusion, ending in a period),
                then 1-2 short sentences on why. Be concise and business-focused; do not enumerate records.
                """,
                "Unable to retrieve answer from memory at this time."));
    }

    /**
     * Produces the answer and, when there is real evidence to reason about (rows genuinely
     * returned), asks the SAME LLM call to also decompose its own answer into semantic roles
     * (understanding / key findings / related facts / recommendation / next steps) — see
     * {@link #DATA_ANSWER_JSON_SYSTEM_PROMPT}. No second LLM call: this is the existing
     * answer-composition call switched from TEXT to JSON mode for this one outcome.
     *
     * <p>The four non-data outcomes (error / blocked / clarification / zero rows) stay in TEXT
     * mode — there is no evidence for the model to find "key findings" or "related facts" in an
     * error message, so asking for structured decomposition there would only invite it to
     * invent content to fill the schema. Those cases return {@link StructuredAnswer#plain},
     * which downstream ({@code ResponseArtifactsBuilder}) means no fabricated semantics — an
     * honest empty state, not a heuristic pretending to be one.
     */
    /**
     * Renders every row-bearing investigation dataset with explicit boundaries — a {@code
     * Dataset: step-N} identifier, its description (verbatim from the planner's own step
     * description), and (via {@link ExecutionOutcomeInterpreter#summarizeRows}) its columns and
     * actual values. This is what lets the composition LLM tell which stats belong to which
     * investigation step, and reference a step back by its exact {@code step-N} identifier (see
     * {@code StructuredAnswer.Section#datasetRef} / {@link #resolveSections}).
     *
     * <p>Package-private static seam: a pure function of its inputs. Every dataset is included,
     * unconditionally, in step order — no selection, ranking, or filtering by row count,
     * evaluator status, or any other signal happens here.
     */
    static String buildInvestigationDatasetsBlock(
            List<com.sei.nexus.reasoning.InvestigationDataset> investigationDatasets,
            ExecutionOutcomeInterpreter outcomeInterpreter) {
        if (investigationDatasets == null || investigationDatasets.isEmpty()) return "";
        StringBuilder ctx = new StringBuilder("INVESTIGATION DATASETS\n\n");
        for (com.sei.nexus.reasoning.InvestigationDataset ds : investigationDatasets) {
            ctx.append("Dataset: step-").append(ds.stepNo()).append("\n");
            ctx.append("Description: ").append(ds.description() != null ? ds.description() : "").append("\n");
            ctx.append(outcomeInterpreter.summarizeRows(ds.rows()));
            ctx.append("\n");
        }
        return ctx.toString();
    }

    private StructuredAnswer composeAnswer(String question, String attachmentSummary,
            List<Map<String, Object>> execResults,
            List<com.sei.nexus.reasoning.InvestigationDataset> investigationDatasets,
            List<DocumentChunk> memChunks, String semCtx, List<OperationalFinding> findings,
            String anomalyCtx, NexusAgent agent, boolean includeMemory) {
            StringBuilder ctx = new StringBuilder();

            boolean anyRows             = investigationDatasets != null && !investigationDatasets.isEmpty();
            boolean anyError            = false;
            boolean anyBlocked          = false;
            boolean anyClarification    = false;
            boolean anyValidationFailed = false;

            if (anyRows) {
                ctx.append(buildInvestigationDatasetsBlock(investigationDatasets, outcomeInterpreter));
            }

            for (Map<String, Object> r : execResults) {
                if (r.containsKey("rows")) {
                    // Already rendered above, per-dataset, with explicit boundaries — skip here
                    // to avoid rendering the same step's rows twice.
                    continue;
                } else if (r.containsKey("error")) {
                    ctx.append("Query error: ").append(r.get("error")).append("\n");
                    anyError = true;
                } else if (r.containsKey("clarification")) {
                    ctx.append("Clarification needed: ").append(r.get("question")).append("\n");
                    anyClarification = true;
                } else if (r.containsKey("validationFailed")) {
                    ctx.append("Query validation failed: ").append(r.get("reason")).append("\n");
                    anyValidationFailed = true;
                } else if (r.containsKey("blocked")) {
                    ctx.append("Query blocked: ").append(r.get("reason")).append("\n");
                    anyBlocked = true;
                }
            }

            if (!findings.isEmpty()) {
                ctx.append("\nRelevant prior findings:\n");
                findings.stream().limit(2).forEach(f ->
                        ctx.append("- ").append(f.title()).append(": ").append(f.description()).append("\n"));
            }
            if (!anomalyCtx.isBlank()) ctx.append("\n").append(anomalyCtx);
            if (includeMemory) {
                memChunks.stream().limit(2).forEach(c ->
                        ctx.append("\nContext: ").append(c.chunkText(), 0, Math.min(300, c.chunkText().length())));
            }

            // When a file was attached and the database returned nothing, be explicit.
            // Do not let the AI fall back to analysing the file content.
            String attachmentNote = (attachmentSummary != null && !attachmentSummary.isBlank())
                    ? "\nNote: the user uploaded a file (" + attachmentSummary + ") whose values were used as query parameters."
                    : "";

            String prompt = "Question: " + question + attachmentNote + "\n\nQuery results:\n" + ctx;

            String systemPrompt = resultSystemPrompt(anyRows, anyError, anyBlocked, anyClarification,
                    anyValidationFailed, attachmentSummary != null && !attachmentSummary.isBlank());

            // Presentation policy (system prompt) and evidence context are chat's; only the
            // model call + failure handling are delegated to the shared composer.
            String fallback = resultFallbackMessage(anyRows, anyError, anyBlocked, anyClarification,
                    anyValidationFailed);

            if (!anyRows) {
                // No evidence to decompose — TEXT mode, exactly as before this change.
                return StructuredAnswer.plain(nlComposer.compose(
                        NaturalLanguageComposer.CompositionRequest.text(prompt, systemPrompt, fallback)));
            }

            // Real evidence exists — ask the SAME call for the model's own semantic decomposition
            // instead of a second call. Strict-schema enforced: the API guarantees every field
            // below is present in the returned JSON — the model's CONTENT judgment for every field
            // remains completely unconstrained, only the FIELD'S PRESENCE is now enforced.
            // FOLLOW_UP_QUESTIONS reasoning lives on that same section (see #dataAnswerJsonSchema's
            // javadoc) — there is no independent top-level reasoning field to enforce here anymore.
            // On any parse failure (or a genuine API-level failure), degrade to plain text via
            // `fallback` — never throw, never block the answer on the JSON shape being perfect.
            String json = nlComposer.compose(
                    NaturalLanguageComposer.CompositionRequest.strictJson(prompt, DATA_ANSWER_JSON_SYSTEM_PROMPT,
                            "data_answer", dataAnswerJsonSchema(), fallback));
            return parseStructuredAnswer(json, objectMapper, fallback);
    }

    /**
     * The strict JSON Schema for the {@code DATA_ANSWER_JSON_SYSTEM_PROMPT} contract — every
     * field that prompt already asks for, formalized into an OpenAI Structured-Outputs strict
     * schema (see {@link AzureOpenAiClient#respondWithStrictJson}). This is STRUCTURE enforcement
     * only: which keys must be present, and their shape. It decides nothing about content — every
     * field's actual value remains entirely the model's judgment, including the legitimate choice
     * of an empty array/null string for a field that has nothing to say this turn.
     *
     * <p>Strict mode requires every property to be listed in "required" at every object level, so
     * genuine optionality is expressed with nullable types ({@code ["string","null"]}) rather than
     * omitting a key — never a Java-side default filling in the "real" value; a null here is
     * relayed to {@link #parseStructuredAnswer} exactly like the previous loose-JSON contract's
     * absent key already was.
     *
     * <p>Package-private static seam — a pure function of no inputs, so a unit test can validate
     * its shape directly (mirrors {@code TeachingService#teachingProposalJsonSchema}'s pattern).
     *
     * <p><b>FOLLOW_UP_QUESTIONS structural fix:</b> {@code reasoning} is a property of the shared
     * {@code Section} object schema (nullable, like {@code title}/{@code purpose}/{@code content}
     * already are for section types that don't use them) — never a separate top-level field. This
     * is deliberate: it eliminates the prior structural flaw where {@code
     * follow_up_questions_reasoning} was an independent, always-required top-level string totally
     * decoupled from whether a {@code FOLLOW_UP_QUESTIONS} section/items existed at all, which let
     * the model satisfy the schema by writing a plausible-sounding reasoning sentence while never
     * emitting the corresponding section. Now {@code reasoning} and {@code items} are properties of
     * the exact same object — a {@code FOLLOW_UP_QUESTIONS} section cannot exist without a {@code
     * reasoning} slot directly beside its {@code items}. <b>Known limitation, by design:</b> OpenAI
     * strict mode requires every property of an object schema to be listed in "required" at that
     * object level (nullable types express optionality, not omission), and all section types share
     * ONE flat object schema — so strict JSON Schema alone cannot express "reasoning is required
     * ONLY when type=FOLLOW_UP_QUESTIONS and items is non-empty," nor can it forbid reasoning
     * describing items that were never actually emitted. That cross-field, per-type conditional is
     * enforced by explicit prompt instruction (see DATA_ANSWER_JSON_SYSTEM_PROMPT's FOLLOW_UP_QUESTIONS
     * rules), not by the schema — the schema's job here is only to remove the structural possibility
     * of reasoning existing in total isolation from items, which it previously did.
     */
    static Map<String, Object> dataAnswerJsonSchema() {
        Map<String, Object> sectionProps = new LinkedHashMap<>();
        sectionProps.put("type", Map.of("type", "string",
                "enum", List.of("DATASET", "HIGHLIGHT", "FINDINGS", "RELATED_FACTS",
                        "RECOMMENDATION", "FOLLOW_UP_QUESTIONS", "TEXT")));
        sectionProps.put("title", Map.of("type", List.of("string", "null")));
        sectionProps.put("purpose", Map.of("type", List.of("string", "null")));
        sectionProps.put("dataset_refs", Map.of(
                "type", List.of("array", "null"),
                "items", Map.of("type", "string")));
        sectionProps.put("display", Map.of("type", List.of("boolean", "null")));
        sectionProps.put("items", Map.of(
                "type", List.of("array", "null"),
                "items", Map.of("type", "string")));
        sectionProps.put("content", Map.of("type", List.of("string", "null")));
        // "reasoning" — meaningful only for type="FOLLOW_UP_QUESTIONS" (the reasoning for the
        // questions in this same section's "items"), null on every other section type. This is
        // the structural fix: reasoning and items are now properties of the SAME object, so a
        // FOLLOW_UP_QUESTIONS section cannot exist with its reasoning living anywhere else. See
        // dataAnswerJsonSchema's class-level javadoc for why strict mode cannot make this
        // property conditionally required only for FOLLOW_UP_QUESTIONS.
        sectionProps.put("reasoning", Map.of("type", List.of("string", "null")));

        Map<String, Object> sectionSchema = new LinkedHashMap<>();
        sectionSchema.put("type", "object");
        sectionSchema.put("properties", sectionProps);
        sectionSchema.put("required", List.of("type", "title", "purpose", "dataset_refs",
                "display", "items", "content", "reasoning"));
        sectionSchema.put("additionalProperties", false);

        Map<String, Object> metricProps = new LinkedHashMap<>();
        metricProps.put("label", Map.of("type", "string"));
        metricProps.put("value", Map.of("type", "string"));
        Map<String, Object> metricSchema = new LinkedHashMap<>();
        metricSchema.put("type", "object");
        metricSchema.put("properties", metricProps);
        metricSchema.put("required", List.of("label", "value"));
        metricSchema.put("additionalProperties", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("answer", Map.of("type", "string"));
        properties.put("sections", Map.of("type", "array", "items", sectionSchema));
        properties.put("metrics", Map.of("type", "array", "items", metricSchema));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("answer", "sections", "metrics"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * Parses the answer-composition JSON response into {@link StructuredAnswer}. Package-private
     * static seam — pure function, no instance state — so contract tests can exercise it directly
     * (this repo's convention; mirrors {@code ConceptScopedMetadataResolver.parseSelection}'s
     * two-layer defense: a malformed/non-JSON response degrades to a plain-text answer rather
     * than throwing or fabricating semantics).
     */
    static StructuredAnswer parseStructuredAnswer(String json, ObjectMapper mapper, String fallbackAnswer) {
        try {
            String extracted = extractJsonObject(json);
            JsonNode node = mapper.readTree(extracted);
            String answerText = node.path("answer").asText("");
            String answer = answerText.isBlank() ? fallbackAnswer : answerText;

            // "metrics" is a top-level field parallel to "sections" — the model's optional,
            // whole-answer headline figures (see DATA_ANSWER_JSON_SYSTEM_PROMPT's METRICS rules).
            // Parsed identically regardless of which contract shape (sections vs. legacy flat)
            // the rest of the response uses. Each entry parses straight into
            // StructuredAnswer.Metric — a mechanical shape read, never an interpretation of what
            // a metric means.
            List<StructuredAnswer.Metric> metrics = new java.util.ArrayList<>();
            JsonNode metricsNode = node.get("metrics");
            if (metricsNode != null && metricsNode.isArray()) {
                for (JsonNode metricNode : metricsNode) {
                    StructuredAnswer.Metric m = mapper.treeToValue(metricNode, StructuredAnswer.Metric.class);
                    if (m != null && m.label() != null && !m.label().isBlank()
                            && m.value() != null && !m.value().isBlank()) {
                        metrics.add(m);
                    }
                }
            }

            JsonNode sectionsNode = node.get("sections");
            if (sectionsNode != null && sectionsNode.isArray() && !sectionsNode.isEmpty()) {
                // The sections-based contract — the model's UI-content plan. Each entry parses
                // straight into StructuredAnswer.Section (Jackson's global snake_case naming
                // strategy maps "dataset_ref" -> datasetRef automatically); the legacy flat
                // fields are then mechanically derived by StructuredAnswer.fromSections, never
                // independently read from this JSON.
                List<StructuredAnswer.Section> sections = new java.util.ArrayList<>();
                for (JsonNode sectionNode : sectionsNode) {
                    sections.add(mapper.treeToValue(sectionNode, StructuredAnswer.Section.class));
                }
                return StructuredAnswer.fromSections(answer, sections, metrics);
            }

            // Legacy shape — no `sections` in the response (an older/degraded model turn).
            // Lenient JSON-shape handling, exactly as this parser has always tolerated a
            // response missing optional fields; not a semantic fallback.
            StructuredAnswer parsed = mapper.treeToValue(node, StructuredAnswer.class);
            return new StructuredAnswer(answer, List.of(), parsed.understanding(), parsed.keyFindings(),
                    parsed.relatedFacts(), parsed.recommendation(), parsed.followUpQuestions(), metrics);
        } catch (Exception e) {
            log.warn("Failed to parse structured answer; falling back to plain text: {}", e.getMessage());
            return StructuredAnswer.plain(fallbackAnswer);
        }
    }

    /**
     * Resolves each {@code StructuredAnswer.Section}'s {@code datasetRefs} (a list of bare
     * {@code step-N} strings, present on ANY section type that carries one — not only {@code
     * DATASET}, e.g. a {@code HIGHLIGHT} grounding its narrative in the one or more datasets its
     * facts actually come from) against this investigation's own preserved datasets — exact
     * string match only, one reference at a time, nothing else. This is the ONLY thing Java
     * decides in this path: whether each claimed reference exists. Which dataset(s) to
     * reference, why, how many, and whether to display them were already decided by the model
     * when it authored {@code sections} — Java does not determine that two referenced datasets
     * are related to each other, does not inspect {@code content} to guess which datasets should
     * have been referenced, and never validates that a narrative's stated values actually appear
     * in the resolved rows (that remains entirely the model's responsibility).
     *
     * <p>A section any of whose {@code datasetRefs} does not exactly match a known {@code step-N}
     * — including a step that exists in the investigation but has zero rows, since such a step
     * was never included in {@code datasets} to begin with — is a model-contract defect: the
     * ENTIRE section is dropped (narrative content included — an ungroundable claim is never
     * shown half-verified), never partially accepted by resolving only the valid references, and
     * never repaired by substituting another dataset, the largest dataset, {@code queryData}, or
     * the last successful step. Logged loudly so the defect stays visible. A section with no
     * {@code datasetRefs} at all needs no resolution and passes through unchanged — it never had
     * a dataset to ground.
     *
     * <p>Package-private static seam: a pure function of its inputs (no {@code queryData}, no
     * question text, no evaluator/row-count/step-order/column-name signal is even in scope here
     * — {@code InvestigationDataset} itself carries none of that), no Spring/instance state, no
     * ranking, no fuzzy matching, no fallback of any kind.
     */
    static List<ResponseArtifacts.Section> resolveSections(List<StructuredAnswer.Section> sections,
            List<com.sei.nexus.reasoning.InvestigationDataset> datasets, String runKey) {
        if (sections == null || sections.isEmpty()) return List.of();

        Map<String, com.sei.nexus.reasoning.InvestigationDataset> byRef = new java.util.LinkedHashMap<>();
        if (datasets != null) {
            for (com.sei.nexus.reasoning.InvestigationDataset d : datasets) {
                byRef.put("step-" + d.stepNo(), d);
            }
        }

        List<ResponseArtifacts.Section> out = new java.util.ArrayList<>();
        for (StructuredAnswer.Section s : sections) {
            List<String> refs = s.datasetRefs();
            if (refs == null || refs.isEmpty()) {
                // No dataset(s) to ground — passes through unchanged, no resolution attempted.
                out.add(new ResponseArtifacts.Section(s.type(), s.title(), s.purpose(), s.display(),
                        s.items(), s.content(), s.reasoning(), List.of()));
                continue;
            }

            // Resolve every declared reference independently, preserving each under its own step
            // identity (never merged/flattened together) — exactly the datasets the model named,
            // in the order it named them.
            List<ResponseArtifacts.Section.ResolvedDataset> resolved = new java.util.ArrayList<>();
            boolean allValid = true;
            for (String ref : refs) {
                com.sei.nexus.reasoning.InvestigationDataset ds = byRef.get(ref);
                if (ds == null) {
                    // ANY invalid reference invalidates the whole section — a claim citing one
                    // real and one fake dataset is exactly as unverifiable as one citing only a
                    // fake dataset, so it is never partially accepted.
                    log.warn("MODEL_CONTRACT_DEFECT runKey={} invalidDatasetRef={} declaredRefs={} "
                            + "knownDatasetRefs={} sectionType={} — section '{}' dropped entirely, "
                            + "never partially accepted, never substituted with another dataset",
                            runKey, ref, refs, byRef.keySet(), s.type(), s.title());
                    allValid = false;
                    break;
                }
                // Full rows are only ever rendered for a type=DATASET section — any other type
                // (HIGHLIGHT/TEXT) cites a dataset purely for traceability (a "step-N · N rows"
                // trace tag), never to display its table, so carrying the full row payload there
                // is pure unused bloat (see ResponseArtifacts.Section.ResolvedDataset javadoc).
                List<Map<String, Object>> rows = "DATASET".equals(s.type()) ? ds.rows() : List.of();
                resolved.add(new ResponseArtifacts.Section.ResolvedDataset(ds.stepNo(), rows, ds.rows().size()));
            }
            if (!allValid) continue;

            out.add(new ResponseArtifacts.Section(s.type(), s.title(), s.purpose(), s.display(),
                    s.items(), s.content(), s.reasoning(), resolved));
        }
        return out;
    }

    /** Same tolerant extraction used elsewhere for LLM JSON responses (e.g.
     *  {@code ConceptScopedMetadataResolver.extractJson}) — strips any stray prose/fencing a
     *  model adds around the JSON object despite {@code response_format: json_object}. */
    private static String extractJsonObject(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }


    // =========================================================================
    // Context building
    // =========================================================================

    /**
     * Execution Continuity grounding. Renders the <b>facts</b> of the previous execution (from its
     * {@link com.sei.nexus.runtime.ExecutionReference}) so a follow-up continues the same execution
     * rather than reconstructing scope from truncated answer prose: the retrieval target, the row
     * scope actually returned, and the business attributes available to extend it. Returns
     * {@code ""} when there is no prior execution (first data turn), so single-turn questions are
     * unaffected.
     *
     * <p>This grounds AgentBrain's reasoning; it never instructs execution. The prior result set is
     * the base a projection-style follow-up ("I need product name as well") must retain.
     */
    static String buildExecutionGrounding(com.sei.nexus.runtime.ExecutionReference ref) {
        if (ref == null) return "";
        StringBuilder sb = new StringBuilder(
                "=== PREVIOUS EXECUTION (continue THIS result set; ground follow-up references against it, not prose) ===\n");
        if (!ref.retrievalTargets().isEmpty()) {
            sb.append("Retrieved from: ").append(String.join(", ", ref.retrievalTargets())).append("\n");
        }
        sb.append("Rows returned: ").append(ref.rowCount()).append("\n");
        if (!ref.resultColumns().isEmpty()) {
            sb.append("Result columns: ").append(String.join(", ", ref.resultColumns())).append("\n");
        }
        if (!ref.businessAttributeBindings().isEmpty()) {
            sb.append("Business attributes available on this base (attribute → column):\n");
            ref.businessAttributeBindings().forEach((k, v) -> sb.append("  - ").append(k).append(" → ").append(v).append("\n"));
        }
        sb.append("A follow-up that adds an attribute (enrichment) must keep this same retrieval base and the same ")
          .append(ref.rowCount()).append(" row(s) — extend the projection; do not re-scope or filter unless the user explicitly asks.\n");
        return sb.toString();
    }

    /**
     * The system prompt for a zero-row result. A legitimately empty analytical result ("no active
     * promotions") must not be framed as a system or metadata fault. Only the file-lookup case —
     * where the user supplied specific values as parameters — states those records "do not exist".
     */
    static String zeroRowSystemPrompt(boolean hasAttachment) {
        return hasAttachment
                ? """
                You are Zevra, an enterprise operational intelligence AI.
                The database query returned zero matching rows.
                The user uploaded a file whose values were used as lookup parameters — zero rows means
                those specific records do NOT exist in the connected database.
                State this clearly and concisely: what was searched for, what was found (nothing), and
                what the user should check next (e.g. different ID format, data not yet loaded).
                Do NOT summarise or analyse the uploaded file content itself — it was input, not output.
                Keep the response to 2-3 sentences.
                """
                : """
                You are Zevra, an enterprise operational intelligence AI.
                The query executed successfully and returned no matching records.
                State plainly and concisely that no records currently match the requested criteria.
                Do NOT suggest the table is wrong, the metadata is misconfigured, the data is missing,
                or that the system failed — the query was valid and simply had no matches.
                Offer one natural next step only if helpful (e.g. broadening the criteria or a different filter).
                Keep the response to 1-2 sentences.
                """;
    }

    /**
     * The system prompt when a step actually failed (a real execution error), as opposed to a
     * query that ran cleanly and simply matched nothing. Must never be confused with
     * {@link #zeroRowSystemPrompt} — a failure is not an empty result.
     */
    static String failedQuerySystemPrompt() {
        return """
                You are Zevra, an enterprise operational intelligence AI.
                The database query could not be executed — it failed with an error.
                State plainly and concisely that the query could not be completed and no data
                was retrieved. Do NOT say the query executed successfully. Do NOT say no
                records were found or matched the criteria — that would misrepresent a failure
                as an empty result.
                Do not expose raw error text, SQL, or internal implementation details; a brief,
                plain-language statement that execution failed is enough. Suggest the user try
                again or rephrase the request.
                Keep the response to 1-2 sentences.
                """;
    }

    /**
     * The system prompt when a step was blocked/rejected by governance before it could run —
     * distinct from both a clean zero-row result and an execution failure.
     */
    static String blockedQuerySystemPrompt() {
        return """
                You are Zevra, an enterprise operational intelligence AI.
                The query was blocked by data governance policy before it could run. This is
                different from a query that executed and found nothing, and different from a
                query that failed to execute.
                State plainly and concisely that the request could not be completed because it
                is not permitted under current data access policy. Do NOT say no records were
                found. Do NOT say the query executed successfully.
                Do not expose raw policy/internal details beyond that plain statement.
                Keep the response to 1-2 sentences.
                """;
    }

    /**
     * The system prompt when a step's own filter value failed deterministic literal validation
     * (PRO-33) — a technical data-quality/format check on the SQL Zevra itself generated, never a
     * governance or access-control decision. Must never be confused with {@link
     * #blockedQuerySystemPrompt} — telling the user their request is "not permitted under data
     * access policy" when the real cause is an internal validation limitation misattributes an
     * internal issue as a deliberate access restriction.
     */
    static String validationFailedSystemPrompt() {
        return """
                You are Zevra, an enterprise operational intelligence AI.
                The investigation could not complete because a filter value it generated failed an
                internal data-validation check — this is a technical limitation, NOT an access or
                permissions restriction, and NOT a data-access policy decision.
                State plainly and concisely that the investigation could not be completed due to an
                internal technical limitation, and suggest the user try rephrasing the request.
                Do NOT say the request is not permitted, not allowed, or restricted by policy. Do NOT
                say no records were found. Do NOT say the query executed successfully.
                Do not expose raw error text, SQL, or internal implementation details.
                Keep the response to 1-2 sentences.
                """;
    }

    private static final String DATA_ANSWER_SYSTEM_PROMPT = """
            You are Zevra, an enterprise operational intelligence AI briefing a busy executive.
            Answer like a chief of staff, not a database.

            - LEAD with a single-sentence VERDICT: the conclusion itself, as one plain declarative
              sentence that ends with a period and can stand alone
              (e.g. "Margins are healthy overall, but two beauty products are priced below cost.").
            - Then give 1-2 short sentences on WHY it matters — the driver or the exception.
            - Add ONE recommendation only if clearly warranted, as its own sentence.
            - The full data is already shown to the user in a table and chart — NEVER enumerate
              individual records, do a product-by-product (or row-by-row) breakdown, or reproduce
              row-level values. Summarise; do not transcribe.
            - Bold the key figures. Plain prose only; no markdown headings or bullet lists unless
              there are several genuinely distinct, independent findings.
            - Be brief: 2 to 5 sentences total. Stop once the point is made.
            """;

    /**
     * The JSON-mode sibling of {@link #DATA_ANSWER_SYSTEM_PROMPT} — used only when real query
     * rows exist to reason about. Asks the SAME model call that used to return prose-only to also
     * plan the complete UI content: which investigation dataset(s) answer which part of the
     * question, which should be displayed, and what belongs in findings / related facts /
     * recommendation / follow-up questions. This is the ownership boundary: the model decides all of this
     * — Java never selects, ranks, or infers any of it (see ChatService#resolveSections, which
     * only resolves an exact {@code step-N} reference the model already chose).
     */
    private static final String DATA_ANSWER_JSON_SYSTEM_PROMPT = """
            You are Zevra, an enterprise operational intelligence AI briefing a busy executive on
            an Investigation Console. The frontend owns PRESENTATION — layout, cards vs. inline
            content, spacing, typography, colors, chart/table rendering, placement. You own
            CONTENT — deciding what the answer is, which evidence is worth showing, which
            additional findings/facts are genuinely useful, whether a recommendation is warranted,
            which follow-up investigations are genuinely useful, and which headline figures deserve
            visual emphasis. Respond with a single JSON object — no markdown fences, no prose
            outside the JSON — with exactly these fields:

            {
              "answer": string,        // the primary executive conclusion (see rules below)
              "sections": [             // OPTIONAL content beyond "answer" — see rules below
                {
                  "type": string,        // "DATASET" | "HIGHLIGHT" | "FINDINGS" | "RELATED_FACTS" |
                                          // "RECOMMENDATION" | "FOLLOW_UP_QUESTIONS" | "TEXT"
                  "title": string|null,
                  "purpose": string|null,
                  "dataset_refs": string[]|null,  // REQUIRED (at least one) for type="DATASET";
                                                   // optional (for grounding) on any other type —
                                                   // see below. A LIST: one entry, or several when
                                                   // this section's content genuinely depends on
                                                   // more than one dataset.
                  "display": boolean|null,     // type="DATASET" only
                  "items": string[]|null,      // type="FINDINGS" | "RELATED_FACTS" | "FOLLOW_UP_QUESTIONS"
                  "content": string|null,      // type="HIGHLIGHT" | "RECOMMENDATION" | "TEXT"
                  "reasoning": string|null     // type="FOLLOW_UP_QUESTIONS" only — see rules below
                }
              ],
              "metrics": [               // OPTIONAL — see METRICS rules below; omit or [] when
                {                          // nothing beyond the obvious row count is noteworthy
                  "label": string,        // e.g. "Top Buyer", "Total Value", "Earliest Delivery"
                  "value": string         // pre-formatted for display, e.g. "Marcus Webb",
                }                          // "$373,750", "Oct 3, 2025" — your own formatting
              ]
            }

            ══════════════════════════════ CORE PRINCIPLE ══════════════════════════════
            The schema below lists AVAILABLE content types, not REQUIRED content. Never create a
            section merely because its type exists in the schema, and never populate "metrics" or
            "sections" just to avoid an empty array. If "answer" already communicates everything
            important, the correct response may legitimately be just "answer" plus the necessary
            DATASET section(s) — or even an empty "sections" array when no dataset needs to be
            shown. Every piece of content — every section, every metric, every follow-up question —
            must earn its place by adding something the reader does not already have. When it
            doesn't, omit it. A shorter, sparser response is not an incomplete one.

            RULES FOR "answer" — the primary executive conclusion. Everything else in the response
            (sections, metrics) exists only to add what "answer" itself does not already say.
            - Start with the most important conclusion — the verdict itself — as the opening
              statement.
            - Keep it concise: normally 1-3 sentences. Add a short sentence on WHY it matters — the
              driver, the exception, a comparison — ONLY when that explanation is tied to a
              concrete figure or comparison actually present in the evidence (a proportion, a
              count, a rate — e.g. "3 of 5 (60%)"). Never append generic risk/urgency language
              ("this could indicate a problem," "this needs monitoring") that isn't backed by an
              actual number or comparison in the data.
            - If the question has more than one substantive part (e.g. "show me all open orders
              AND which item I ordered the most"), address every part — do not silently drop one.
            - Use only evidence supplied in the investigation datasets shown to you below — never
              outside knowledge, never an invented figure or relationship.
            - Do NOT reproduce row-level values here — that is what a DATASET section is for.
              Summarise; do not transcribe.
            - Do not add generic risk language, and do not invent implications the evidence does
              not support.
            - Do not mention UI sections, datasets, steps, SQL, metadata, or your own internal
              reasoning — "answer" is a business conclusion, not a description of how you produced
              it.
            - Include a concrete figure, comparison, or relationship when it materially explains
              the conclusion; bold important figures when useful. Plain prose otherwise.

            RULES FOR "sections" — each one is OPTIONAL, and exists only when it adds something
            "answer" doesn't already say. Below you will be shown one or more INVESTIGATION
            DATASETS, each with an explicit "Dataset: step-N" identifier, its description, row
            count, columns, and actual values.
            - "DATASET": appropriate when the question asks to show/list/retrieve/display/compare/
              inspect records, or when a dataset is important supporting evidence. "dataset_refs"
              MUST be that dataset's EXACT "step-N" identifier as shown (character-for-character —
              never invent, abbreviate, or guess one); "display" MUST be true for a dataset meant
              for the user. "title" should be concise and business-friendly. "purpose" is OPTIONAL
              — write it only when it adds real context beyond the title (what makes this result
              notable, or what the user will do with it); if the title already explains the
              dataset and nothing further is genuinely useful, set "purpose" to null. Never write a
              boilerplate purpose such as "This dataset lists all X with details." Never invent
              rows, values, columns, or entities. A dataset that exists but is not directly
              responsive to the question may be omitted entirely, or included with "display": false.
            - "HIGHLIGHT": use ONLY when there is a specific, important observation that deserves
              separate visual emphasis AND that observation is NOT already contained in "answer".
              For example, if "answer" already says "Open purchase orders total 5, with Marcus
              Webb accounting for 3," do not create a HIGHLIGHT restating that Marcus Webb has 3
              open orders — that is duplication. A genuinely additional HIGHLIGHT would be a
              different fact "answer" never mentioned, e.g. "Three of the five orders are
              partially received." Put the narrative in "content" and set "dataset_refs" to EVERY
              dataset that narrative's facts actually come from (this may be more than one — e.g. a
              name/SKU from one dataset and a quantity from another: "dataset_refs": ["step-3",
              "step-5"]). Every factual value in "content" MUST come from a dataset listed in
              "dataset_refs" (or from "answer") — never state a value not actually present in a
              dataset shown to you. If there is no genuinely additional highlight, do not emit one.
            - "FINDINGS": use ONLY for additional evidence-backed discoveries that materially add
              to "answer", as "items". The test for each item: "does this tell the reader something
              they did not already learn from 'answer' or another section?" If no, drop it — do not
              restate "answer" in different wording, do not split one answer into multiple
              artificial findings, and do not create this section simply because the schema offers
              it. For categorical or aggregate evidence (rows grouped by some dimension, each with
              a count/sum/other aggregate), a finding may identify a meaningful quantified
              relationship — one category dominates, one entity accounts for most records, a
              category is unusually high/low, two groups differ materially — but only when the
              supplied evidence actually supports it. State the quantified fact and stop; do not
              append interpretive commentary ("this indicates...", "this could signal...") unless
              that interpretation is itself tied to a specific figure already in the same item. If
              nothing genuinely qualifies, omit FINDINGS.
            - "RELATED_FACTS": use ONLY when additional context helps the user understand the
              investigation but is not itself the primary finding — e.g. a related date pattern, a
              supporting category, a secondary attribute. Do not use it to repeat "answer". If
              nothing useful exists, omit it.
            - "RECOMMENDATION": use ONLY when the evidence supports an actionable business
              consideration — one concise sentence in "content". Do not manufacture one, and do not
              write generic language such as "This should be monitored," "Further investigation may
              be needed," or "Management should review this," unless the evidence itself provides a
              concrete basis for that specific recommendation. If no recommendation is warranted,
              omit RECOMMENDATION.
            - "FOLLOW_UP_QUESTIONS": ONE cohesive, OPTIONAL section holding BOTH the actual
              possible questions the USER may naturally ask NEXT ("items") AND the reasoning for
              why those specific questions are plausible/useful ("reasoning") — the two always
              travel together, in the same section object. Evaluate this explicitly on EVERY
              response, before you decide to omit it: CURRENT QUESTION + AVAILABLE EVIDENCE →
              "Are there useful, concrete questions this user might naturally ask next, beyond
              what 'answer' already covers?" → YES: emit FOLLOW_UP_QUESTIONS with both "items" and
              "reasoning" populated; NO: omit it entirely. That is a different question from "is
              the current investigation/answer incomplete?" — do not silently default to omission
              just because this section is marked optional, and do not skip evaluating it merely
              because "answer" already feels complete. For list, status-breakdown, and
              aggregate-style results especially — the exact shape where a curious user most often
              has a natural next question — actively check whether the visible fields (status,
              dates, owners/buyers, amounts, categories) support a concrete next question before
              concluding there is nothing useful to add; treat silently skipping this check, not
              emitting a well-grounded section, as the failure mode to avoid. NOT a generic
              chatbot suggestion list,
              and NOT investigation steps, reasoning steps, tasks, actions, recommendations,
              required next actions, or workflow instructions for Zevra. Think: CURRENT USER
              QUESTION → CURRENT ANSWER/EVIDENCE → "what questions might this user naturally ask
              next?" → FOLLOW-UP QUESTIONS. Do NOT think: CURRENT USER QUESTION → CURRENT ANSWER →
              "what should Zevra/the investigation do next?" → actions/investigation steps — that
              is a different, disallowed framing. These are optional and speculative BY DESIGN:
              they do not represent required actions, and proposing them is NOT a determination
              that the current result is incomplete. You do NOT need to establish that the dataset
              is incomplete, insufficient, or missing something before proposing a follow-up
              question — a follow-up is a plausible NEXT QUESTION a curious user might ask, not
              evidence of a gap in the current answer, and it can be useful even when the current
              answer is already complete. For "show me open purchase orders", the current dataset
              already containing every open purchase order does NOT prevent follow-up questions
              like "How many are partially received?", "Which open purchase orders are due for
              delivery soon?", "Who has the most open purchase orders?", or "What is the total
              value of the open purchase orders?" — these are useful precisely because they explore
              a different dimension, comparison, or detail than the current answer, not because the
              current answer is somehow lacking. Follow-up questions must still be grounded in the
              available investigation evidence/datasets — explore a dimension, comparison, or
              detail that dataset actually supports; do not invent an unavailable dataset, fact,
              metric, or capability. Do NOT generate follow-up questions merely to fill a quota, do
              NOT suggest questions unrelated to the current investigation, do NOT ask the user to
              retrieve information Zevra already has (check every INVESTIGATION DATASET shown to
              you first — if the value you'd otherwise ask for already appears there, use it
              directly instead), and do NOT turn FOLLOW_UP_QUESTIONS into generic advice such as
              "review the data," "take action," "would you like more information?", or "should I
              analyze this further?" — every item must be a genuine, concrete, answerable question.
              A simple lookup can legitimately have no useful follow-up questions — but do not omit
              a genuinely useful FOLLOW_UP_QUESTIONS section merely because the current dataset is
              complete; completeness of the current answer and usefulness of a follow-up question
              are unrelated. IMPORTANT: a FOLLOW_UP_QUESTIONS item is NOT disqualified as
              duplication merely because it concerns the same dataset or topic as "answer" — it is
              a proposed question the user might ask, not a repeated fact, so the AVOID DUPLICATION
              rule below does not apply to it.
              "reasoning" (this section's other field) is your own one-line record of WHY the
              questions actually present in THIS section's "items" are plausible/useful — e.g.
              "these explore order status, delivery timing, and buyer distribution, each a
              dimension the current dataset doesn't already break out." It must describe the
              ACTUAL items emitted in this same section, never a different or hypothetical set of
              questions, and never questions you considered but chose not to include. Do NOT write
              "reasoning" describing candidate follow-up questions without also emitting those same
              questions in "items" — a narrative about follow-up questions that never actually
              appear in "items" is a contract violation, not a valid response.
              If you identify useful follow-up questions, emit the FOLLOW_UP_QUESTIONS section with
              both "items" (the actual questions) and "reasoning" (why they're useful) populated.
              If no useful follow-up questions exist, OMIT the FOLLOW_UP_QUESTIONS section entirely
              — never include one with an empty "items" array, and never include one whose
              "reasoning" describes questions that aren't present in "items".
            - "TEXT": free-form narrative that doesn't fit any type above — use sparingly, and only
              when it states something genuinely not already said elsewhere. If you have nothing
              further to add, omit it.

            RULES FOR "metrics" — a separate visual-emphasis layer, distinct from any per-dataset
            chart hint you may have declared earlier when building a step's query (those are about
            how ONE dataset charts; "metrics" is about what's worth headlining across the entire
            evidence set for this answer). A metric is NOT required to be different from every
            number in "answer" — it is valid when it gives the reader a useful at-a-glance
            representation of an important figure, even one "answer" also states (e.g. answer:
            "There are 5 open purchase orders, with Marcus Webb accounting for 3" → valid metrics:
            {"label": "Open Orders", "value": "5"}, {"label": "Top Buyer", "value": "Marcus Webb"}
            — this is visual repetition for at-a-glance emphasis, not semantic duplication, and is
            allowed). Each entry is {"label": string, "value": string} — "value" is the exact
            display string, already formatted the way you'd want it shown (currency symbols, date
            formatting, a percentage — your own judgment; Java will not reformat it). Only include
            metrics genuinely useful for this investigation and grounded in the supplied evidence —
            do not create metrics merely to fill space, and do not calculate an unsupported metric.
            Zero metrics is valid.

            CRITICAL — DATASET GROUNDING: every entry in "dataset_refs" MUST exactly match one of
            the "Dataset: step-N" identifiers you were actually shown — never invent a step number,
            never reference a dataset that wasn't shown to you, never invent rows, columns, values,
            or relationships that aren't in it, and never use outside knowledge. If a section's
            "dataset_refs" is ["step-1"], every factual claim in that section must be supported by
            step-1; if a claim genuinely depends on multiple datasets, list all of them
            (["step-1", "step-3"]) — but never include a dataset reference merely because it is
            available. If ANY reference in "dataset_refs" is invalid, the ENTIRE section is
            rejected — narrative included, not partially accepted, and never repaired by
            substituting or guessing a different step.

            CRITICAL — AVOID DUPLICATION: apply one rule — every piece of content must earn its
            place by adding useful information or serving a distinct presentation purpose. Two
            exceptions are explicitly allowed: (1) METRICS may visually repeat a figure already in
            "answer" for at-a-glance emphasis (see METRICS rules above), and (2)
            FOLLOW_UP_QUESTIONS may concern the same subject as "answer" because it is a proposed
            question, not a repeated fact (see FOLLOW_UP_QUESTIONS rules above). For every other
            section type — HIGHLIGHT, FINDINGS,
            RELATED_FACTS, RECOMMENDATION, TEXT — do NOT repeat a fact already established in
            "answer" or another section, even worded differently, sliced with different numbers
            grouped together, or padded with an extra minor detail; ask "does this tell the reader
            something 'answer' did not," not "is this sentence worded exactly the same." If nothing
            genuinely new remains after writing "answer", return an empty "sections" array — do not
            fill a slot just because the schema offers it, and do not paraphrase the same fact to
            make a section appear populated.

            CRITICAL — NO UNEARNED INTERPRETATION: do not turn a factual result into unsupported
            business commentary. "3 of 5 orders are partially received" is allowed; "this indicates
            supplier delays" is not, without evidence beyond the supplied result. "Marcus Webb
            accounts for 3 of 5 orders" is allowed; "this makes Marcus Webb a procurement risk" is
            not — that requires evidence beyond the supplied result. Any interpretive or
            "why this matters"/risk-style framing — anywhere in "answer" or any section — must be
            tied to something concretely present in the evidence (a proportion, a count, a rate, a
            comparison actually in the returned rows); generic risk/urgency language that isn't
            backed by an actual number or comparison is not allowed anywhere in the response.

            Do not add causal, evaluative, interpretive, or business-significance language unless
            the supplied evidence explicitly establishes that relationship. State evidence as facts.
            "Three orders are partially received." is valid. "Three orders are partially received,
            indicating progress in fulfillment." is not valid unless the supplied evidence
            explicitly establishes that partially_received represents progress in fulfillment. Do
            not infer risk, urgency, performance, causality, business impact, or intent from a
            status, category, count, or comparison unless that relationship is explicitly supported
            by the supplied evidence.

            CRITICAL — DO NOT FABRICATE: use only the evidence actually shown to you below. Do not
            assume industry, business terminology, business rules, urgency, risk, causality,
            intent, or importance unless established by the supplied evidence — this may be retail,
            finance, healthcare, manufacturing, HR, or any other domain, and no domain-specific
            assumption the data doesn't support is allowed. If the evidence cannot support a
            statement, do not make it — say so explicitly in "answer" rather than guessing. A
            section type existing in the schema is never a reason to use it.

            Do not confuse "complete investigation" with "maximum number of sections." A complete
            response may legitimately contain only "answer" and a DATASET section; another may
            legitimately contain "answer", metrics, a DATASET, FINDINGS, and FOLLOW_UP_QUESTIONS.
            The correct shape depends entirely on the evidence and the question — never on filling
            every available slot.

            """;

    /**
     * Chooses the composed-answer system prompt from the three mutually exclusive outcomes of an
     * investigation. Precedence: real data always wins (an earlier step's rows still answer the
     * question even if a later step in the same investigation failed/was blocked). Only when
     * there is no data at all do failure and blocked need to be told apart from a query that
     * simply ran clean and matched nothing — a failure must never be presented as "no records".
     *
     * <p>Pre-clarification signature, preserved byte-for-byte for existing callers/tests —
     * delegates to the 5-arg overload with {@code anyClarification=false}.
     */
    static String resultSystemPrompt(boolean anyRows, boolean anyError, boolean anyBlocked,
                                      boolean hasAttachment) {
        return resultSystemPrompt(anyRows, anyError, anyBlocked, false, hasAttachment);
    }

    /**
     * Semantic Reasoning Over Authoritative Value Domains: same precedence as the 4-arg overload,
     * with one addition — a step the planner explicitly declined (an unresolvable term against an
     * authoritative value domain) is told apart from both a governance "blocked" outcome and a
     * clean zero-row result, so the composed answer asks the clarifying question the planner
     * raised instead of reporting "no records" or "blocked by policy".
     */
    static String resultSystemPrompt(boolean anyRows, boolean anyError, boolean anyBlocked,
                                      boolean anyClarification, boolean hasAttachment) {
        return resultSystemPrompt(anyRows, anyError, anyBlocked, anyClarification, false, hasAttachment);
    }

    /**
     * Adds one more outcome: a step whose SQL was never run because PRO-33 deterministic literal
     * validation (LiteralValidator) rejected/blocked a filter value — a data-quality/format check,
     * never a governance or access-control decision. Told apart from {@code anyBlocked} (a genuine
     * governance/contract/approved-object gate) so the composed answer never mislabels an internal
     * validation limitation as "not permitted under current data access policy". Checked after
     * {@code anyClarification} (a more specific, already-honest outcome) and before {@code
     * anyBlocked} in precedence, since a literal-validation step's own {@code outcome} string
     * ("LITERAL_BLOCKED") would otherwise also match the generic blocked bucket.
     */
    static String resultSystemPrompt(boolean anyRows, boolean anyError, boolean anyBlocked,
                                      boolean anyClarification, boolean anyValidationFailed,
                                      boolean hasAttachment) {
        if (anyRows) return DATA_ANSWER_SYSTEM_PROMPT;
        if (anyError) return failedQuerySystemPrompt();
        if (anyClarification) return clarificationSystemPrompt();
        if (anyValidationFailed) return validationFailedSystemPrompt();
        if (anyBlocked) return blockedQuerySystemPrompt();
        return zeroRowSystemPrompt(hasAttachment);
    }

    /**
     * The system prompt when the planner itself asked for clarification rather than generating
     * SQL — distinct from a governance block (this is a semantic-reasoning outcome, not a policy
     * denial) and from a failure (nothing was attempted, nothing failed). The clarifying question
     * itself, including the actual legal values, is already in the "Clarification needed:" line
     * of the query-results context — this system prompt tells the composer to relay it as a
     * genuine question back to the user, not to answer on the term's behalf.
     */
    static String clarificationSystemPrompt() {
        return """
                You are Zevra, an enterprise operational intelligence AI.
                The investigation could not proceed because one of the user's terms does not
                match any of the actual, authoritative values for the column it would filter —
                and no existing business definition in your context supports a confident mapping
                to one of those values.
                Ask the user a short, specific clarifying question: name the term you could not
                resolve and list the actual legal values (from "Clarification needed:" above) so
                they can pick one, or restate what they meant.
                Do NOT guess which legal value the user probably meant. Do NOT generate or imply
                any SQL or filter value yourself — this is a question back to the user, not an
                answer.
                Keep the response to 1-2 sentences.
                """;
    }

    /** The composer's last-resort fallback text (used only if the LLM call itself fails) —
     *  same precedence and the same "never claim success on failure" rule as {@link #resultSystemPrompt}.
     *  Pre-clarification signature, preserved for existing callers/tests. */
    static String resultFallbackMessage(boolean anyRows, boolean anyError, boolean anyBlocked) {
        return resultFallbackMessage(anyRows, anyError, anyBlocked, false);
    }

    /** Semantic Reasoning Over Authoritative Value Domains: adds the clarification fallback,
     *  same precedence rule as the 4-arg overload. */
    static String resultFallbackMessage(boolean anyRows, boolean anyError, boolean anyBlocked,
                                         boolean anyClarification) {
        return resultFallbackMessage(anyRows, anyError, anyBlocked, anyClarification, false);
    }

    /** Adds the {@code anyValidationFailed} outcome (PRO-33 literal validation) — same precedence
     *  and the same "never a policy denial" distinction as {@link #resultSystemPrompt}. */
    static String resultFallbackMessage(boolean anyRows, boolean anyError, boolean anyBlocked,
                                         boolean anyClarification, boolean anyValidationFailed) {
        if (anyRows) return "Investigation completed. Results are shown in the table below.";
        if (anyError) return "The query could not be executed.";
        if (anyClarification) return "One of the terms in your question doesn't match an available value — could you clarify?";
        if (anyValidationFailed) return "The investigation could not be completed due to an internal technical limitation.";
        if (anyBlocked) return "The request was blocked by data governance policy.";
        return "Investigation completed. No data returned.";
    }

    private String buildContextSummary(String question, List<DocumentChunk> memChunks,
            PromptContext promptContext, SemanticService.SemanticContext semantic, List<OperationalFinding> findings,
            String anomalyCtx, boolean hasPrior, List<NexusRun> history, NexusAgent agent,
            ResolvedQuestion resolved, String executionGrounding) {
        return buildContextSummary(question, memChunks, promptContext, semantic, findings, anomalyCtx,
                hasPrior, history, agent, resolved, executionGrounding, false, java.util.Set.of());
    }

    /**
     * Downstream Context Boundary for Concept-Scoped Metadata Narrowing: identical to the
     * overload above, except the Knowledge Graph block is also bounded to {@code objectKeyScope}
     * when {@code conceptScoped} is true — see {@link ResolvedBusinessModel#conceptScoped()} and
     * {@link KnowledgeGraphService#buildGraphContext(List, java.util.Set)}. {@code conceptScoped
     * = false} (every pre-existing caller, via the overload above) reproduces the exact prior
     * domain-wide, keyword-filtered graph context — byte-identical.
     */
    private String buildContextSummary(String question, List<DocumentChunk> memChunks,
            PromptContext promptContext, SemanticService.SemanticContext semantic, List<OperationalFinding> findings,
            String anomalyCtx, boolean hasPrior, List<NexusRun> history, NexusAgent agent,
            ResolvedQuestion resolved, String executionGrounding,
            boolean conceptScoped, java.util.Set<String> objectKeyScope) {
        return buildContextSummary(question, memChunks, promptContext, semantic, findings, anomalyCtx,
                hasPrior, history, agent, resolved, executionGrounding, conceptScoped, objectKeyScope, List.of());
    }

    /**
     * Concept-Key Semantic Anchor design: identical to the overload above, additionally rendering
     * a concept-scoped learned-knowledge EVIDENCE section when {@code resolvedConceptKeys} is
     * non-empty — see the {@code LEARNED BUSINESS KNOWLEDGE} block below. {@code resolvedConceptKeys
     * = List.of()} (every pre-existing caller, via the overload above) reproduces the exact prior
     * context — byte-identical, zero-cost guarantee preserved.
     */
    private String buildContextSummary(String question, List<DocumentChunk> memChunks,
            PromptContext promptContext, SemanticService.SemanticContext semantic, List<OperationalFinding> findings,
            String anomalyCtx, boolean hasPrior, List<NexusRun> history, NexusAgent agent,
            ResolvedQuestion resolved, String executionGrounding,
            boolean conceptScoped, java.util.Set<String> objectKeyScope, List<String> resolvedConceptKeys) {
        StringBuilder sb = new StringBuilder();
        String semCtx = semantic != null ? semantic.contextText() : "";
        java.util.Set<String> expandedTokens = resolved != null
                ? resolved.expandedTokens() : java.util.Set.of();

        // Zevra Cognitive Runtime baseline — measurement only, no behavior change: records the
        // exact character delta each section below actually contributes to schemaCtx, purely by
        // reading sb.length() before/after each pre-existing append block. Nothing here changes
        // what gets appended, the order it's appended in, or any conditional — see the
        // CONTEXT_BREAKDOWN log line at the end of this method.
        java.util.Map<String, Integer> sectionChars = new java.util.LinkedHashMap<>();
        int mark = sb.length();

        // Execution Continuity: the previous execution's facts first, so the decision/planner
        // continue the same result set on a follow-up. Empty for single-turn questions.
        if (executionGrounding != null && !executionGrounding.isBlank()) {
            sb.append(executionGrounding).append('\n');
        }
        sectionChars.put("executionGrounding", sb.length() - mark); mark = sb.length();

        if (agent != null) {
            sb.append("Agent: ").append(agent.name())
              .append(" | Domain: ").append(agent.domainKeys()).append("\n\n");
        }
        sectionChars.put("agentDomainLine", sb.length() - mark); mark = sb.length();

        // ── RESOLUTIONS block (PRO-31, contract PRO-30 §6) — rendered only when
        // at least one resolution exists, so resolution-free questions produce
        // byte-identical context (the zero-cost guarantee). The original question
        // is never rewritten; these lines annotate it.
        if (resolved != null && !resolved.isEmpty()) {
            sb.append(resolved.renderPromptBlock()).append("\n");
        }
        sectionChars.put("resolutions", sb.length() - mark); mark = sb.length();

        // ── LITERAL CANDIDATES block (PRO-33, contract PRO-32 §3) — the
        // constrained-choice task for unresolved literal-shaped terms.
        // Empty (the common case) ⇒ byte-identical context.
        if (resolved != null) {
            String literalBlock = resolved.renderLiteralCandidatesBlock();
            if (!literalBlock.isEmpty()) sb.append(literalBlock).append("\n");
        }
        sectionChars.put("literalCandidates", sb.length() - mark); mark = sb.length();

        // ── LEARNED BUSINESS KNOWLEDGE block (Concept-Key Semantic Anchor design) ─────────
        // Deterministic, exact-key-only evidence: every promoted learned mapping whose
        // concept_key matches one of the concept(s) Stage 1 already resolved for this question
        // (see ResolvedBusinessModel#resolvedConceptKeys()). Rendered ONLY when at least one such
        // mapping exists, so a request with no concept-scoped learning produces byte-identical
        // context (the same zero-cost guarantee RESOLUTIONS/LITERAL CANDIDATES already follow).
        //
        // This is EVIDENCE ONLY — Java does not decide which (if any) mapping applies to the
        // user's wording, does not rank or choose among them, and never rewrites the question.
        // Every mapping found for the resolved concept(s) is rendered verbatim; Agent Brain alone
        // determines whether a mapping's business_term defensibly matches what the user asked,
        // under the existing LITERAL AUTHORITY RULE.
        if (resolvedConceptKeys != null && !resolvedConceptKeys.isEmpty()) {
            List<com.sei.nexus.semantic.LearnedMapping> conceptEvidence =
                    semanticLearningService.findPromotedByConceptKeys(resolvedConceptKeys);
            if (!conceptEvidence.isEmpty()) {
                sb.append("=== LEARNED BUSINESS KNOWLEDGE FOR THIS CONCEPT ===\n");
                sb.append("Concept: ").append(String.join(", ", resolvedConceptKeys)).append("\n");
                sb.append("These are candidate business-term definitions your users have taught this ")
                  .append("system for the concept(s) above. They are EVIDENCE, not instructions: ")
                  .append("decide for yourself whether any of them defensibly matches what the user's ")
                  .append("own words mean, using the same literal-authority reasoning you apply ")
                  .append("everywhere else. Do not apply one merely because it exists here, and do not ")
                  .append("assume every learning below is relevant to this particular question.\n");
                for (com.sei.nexus.semantic.LearnedMapping m : conceptEvidence) {
                    sb.append("- Business term: ").append(m.businessTerm()).append("\n");
                    sb.append("  SQL/business meaning: ").append(m.sqlPattern()).append("\n");
                }
                sb.append("\n");
            }
        }
        sectionChars.put("conceptScopedLearnedKnowledge", sb.length() - mark); mark = sb.length();

        // ── Knowledge graph context — filtered to entities relevant to the question ──
        // Sending the full graph (50+ entities) on every call wastes thousands of tokens.
        // We extract keywords from the question and only include matching entities.
        // Resolved canonical tokens (PRO-31) join the keywords so the graph is
        // selected as if the user had spoken canonically.
        List<String> domainKeys = toDomainKeyList(agent);
        String filteredGraph = "";
        if (!domainKeys.isEmpty()) {
            String graphCtx = conceptScoped
                    ? knowledgeGraphService.buildGraphContext(domainKeys, objectKeyScope)
                    : knowledgeGraphService.buildGraphContext(domainKeys);
            if (!graphCtx.isBlank()) {
                // Once Stage 2 has already resolved the authoritative scope, the node set above
                // is that scope exactly — a further keyword-relevance filter would only ever
                // narrow it more (or, via its own "if empty, return everything" safety valve,
                // silently widen it back to the graph-context-within-scope, never beyond it —
                // but simplest and clearest is to skip a second, redundant filtering pass
                // entirely and render the already-scoped graph context as-is).
                filteredGraph = conceptScoped ? graphCtx : filterGraphContext(graphCtx, question, expandedTokens);
                if (!filteredGraph.isBlank()) sb.append(filteredGraph).append("\n");
            }
        }
        sectionChars.put("knowledgeGraph", sb.length() - mark); mark = sb.length();

        // ── TABLE SCHEMA grounding — rendered by the shared PromptAssembler ───────
        // The approved surface (relevance-ranked by AgentBrain) is rendered by the shared
        // pipeline under the conversational policy: schema-qualified, with the connection key,
        // data types, and value domains, bounded by the entity-context budget. The budget caps
        // the render only; the ExecutionContract keeps the full approved surface. The empty-
        // schema branch is conversation routing policy (which decision mode to steer toward)
        // and stays here.
        boolean hasMemory = memChunks != null && !memChunks.isEmpty();
        if (!promptContext.isEmpty()) {
            sb.append(promptAssembler.assemble(promptContext,
                    new PromptAssembler.RenderOptions(true, true, true, maxEntityContextChars)))
              .append('\n');
        } else {
            sb.append("=== TABLE SCHEMA ===\n");
            sb.append("NO LIVE DATA SOURCES CONFIGURED. Do NOT generate SQL or use QUERY_LIVE_DATA.\n");
            if (hasMemory) {
                sb.append("Memory documents ARE available — use ANSWER_FROM_MEMORY.\n\n");
            } else {
                sb.append("No memory documents either — use KNOWLEDGE_GAP.\n\n");
            }
        }
        sectionChars.put("tableSchema", sb.length() - mark); mark = sb.length();

        // ── Supporting context ────────────────────────────────────────────────
        if (hasMemory) {
            sb.append("Knowledge memory chunks: ").append(memChunks.size()).append(" available\n");
        }
        // Semantic Reasoning Over Authoritative Value Domains: the tenant's curated business
        // entity/vocabulary definitions (SemanticService.buildSemanticContext — already computed
        // above as `semantic`, previously reduced to a bare "available" flag here and discarded)
        // now reach the SQL-generating planner itself, so it has a real chance to resolve a
        // business term (e.g. "open purchase orders") against an existing definition before
        // deciding whether a literal is defensible — no new resolver, this reuses the exact
        // existing entity/vocabulary text already used elsewhere in this class (answerFromMemory,
        // composeAnswer).
        if (!semCtx.isBlank()) sb.append(semCtx).append("\n");
        sectionChars.put("memoryChunksLineAndSemanticContext", sb.length() - mark); mark = sb.length();
        if (!findings.isEmpty()) sb.append("Prior findings: ").append(findings.size()).append("\n");
        if (!anomalyCtx.isBlank()) sb.append(anomalyCtx).append("\n");
        if (hasPrior) sb.append("Prior query result: available\n");
        sectionChars.put("findingsAndAnomaly", sb.length() - mark); mark = sb.length();

        // ── Conversation thread (prior user questions only) ───────────────────
        // The user's own earlier questions give lightweight conversational framing.
        // Execution continuity is NOT derived here — it comes exclusively from the
        // ExecutionReference grounding rendered above. Zevra's prior ANSWER PROSE is
        // deliberately excluded: per the approved architecture, answer prose is never
        // the execution-continuity substrate.
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 4);
            StringBuilder priorQuestions = new StringBuilder();
            for (int i = start; i < history.size(); i++) {
                String q = history.get(i).question();
                if (q != null && !q.isBlank()) priorQuestions.append("- ").append(q.trim()).append("\n");
            }
            if (priorQuestions.length() > 0) {
                sb.append("\nEarlier in this conversation the user asked:\n")
                  .append(priorQuestions).append("\n");
            }
        }
        sectionChars.put("priorQuestions", sb.length() - mark);

        try {
            log.info("CONTEXT_BREAKDOWN conceptScoped={} totalChars={} sections={}",
                    conceptScoped, sb.length(), sectionChars);
        } catch (Exception ignored) {
            // Measurement-only — never affect the returned context
        }

        return sb.toString();
    }

    // =========================================================================
    // Quick refinements
    // =========================================================================

    private List<Map<String, Object>> buildQuickRefinements(String decisionType, String question) {
        return switch (decisionType) {
            case "QUERY_LIVE_DATA", "HYBRID_DOC_AND_DATA" -> List.of(
                    Map.of("label", "Show exceptions only",
                            "prompt", question + " — show only exceptions or errors",
                            "requires_input", false),
                    Map.of("label", "Filter by date",
                            "prompt", question + " — for date:",
                            "requires_input", true,
                            "input_label", "Date",
                            "placeholder", "e.g. 2024-01-15"),
                    Map.of("label", "Run in background",
                            "prompt", "/async " + question,
                            "requires_input", false));
            case "ANSWER_FROM_MEMORY" -> List.of(
                    Map.of("label", "More detail",
                            "prompt", question + " — explain in more detail",
                            "requires_input", false),
                    Map.of("label", "Check live data",
                            "prompt", question + " — check the live data",
                            "requires_input", false));
            default -> List.of();
        };
    }

    // =========================================================================
    // Slash command handlers
    // =========================================================================

    private ChatResponse handleKnowledgeProposal(String text, String userEmail) {
        String gapKey = Keys.uniqueKey("gap");
        KnowledgeGap gap = new KnowledgeGap(gapKey, null, "KNOWLEDGE_PROPOSAL", null, text,
                "User-submitted knowledge proposal awaiting review.", text, "OPEN", null, null, null, null);
        knowledgeGapRepository.save(gap);
        String convId = Keys.conversationKey();
        String runKey = Keys.runKey();
        NexusRun run = new NexusRun(runKey, convId, null, null, userEmail, "/knowledge " + text,
                "Knowledge proposal submitted for review.", "KNOWLEDGE_PROPOSAL", "COMPLETE", null, null, null);
        runRepository.save(run);
        runRepository.update(runKey, "Knowledge proposal submitted for domain owner review.",
                "KNOWLEDGE_PROPOSAL", "COMPLETE", null);
        return buildResponse(convId, runKey,
                "Your knowledge proposal has been submitted for review by the domain owner. Ref: " + gapKey,
                "KNOWLEDGE_PROPOSAL", null, 1.0, false, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private ChatResponse handleSourceRequest(String text, String userEmail) {
        String gapKey = Keys.uniqueKey("gap");
        KnowledgeGap gap = new KnowledgeGap(gapKey, null, "SOURCE_REQUEST", null, text,
                "User-submitted source request awaiting review.", text, "OPEN", null, null, null, null);
        knowledgeGapRepository.save(gap);
        String convId = Keys.conversationKey();
        String runKey = Keys.runKey();
        NexusRun run = new NexusRun(runKey, convId, null, null, userEmail, "/request-source " + text,
                "Source request submitted.", "SOURCE_REQUEST", "COMPLETE", null, null, null);
        runRepository.save(run);
        runRepository.update(runKey, "Source request submitted.", "SOURCE_REQUEST", "COMPLETE", null);
        return buildResponse(convId, runKey,
                "Your source request has been submitted for review. Ref: " + gapKey,
                "SOURCE_REQUEST", null, 1.0, false, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    // =========================================================================
    // Execution strategy dispatch (Unified Answer Engine front door)
    // =========================================================================

    /**
     * The one gate that keeps the Agent Router from being the front door (Invariant 2): the router
     * is invoked only after the selector has chosen {@link ExecutionStrategy#AGENT}. Package-private
     * and static so the architectural conformance test can pin it without constructing ChatService —
     * a regression guard against reintroducing unconditional (data-ownership-driven) routing.
     */
    static boolean shouldInvokeAgentRouter(RequestAnalysis analysis) {
        return analysis != null && analysis.strategy() == ExecutionStrategy.AGENT;
    }

    // =========================================================================
    // Response building
    // =========================================================================

    private ChatResponse buildResponse(String conversationId, String runKey, String answer,
            String decisionType, NexusAgent agent, double confidence, boolean needsKnowledge,
            List<Map<String, Object>> quickRefs, List<Map<String, Object>> asyncOps,
            List<Map<String, Object>> queryData, List<Map<String, Object>> reasoningSteps,
            List<String> learningsApplied) {
        // Auxiliary callers (read-only boundary, source request, knowledge gap) keep the historical
        // intent label — behaviour unchanged. The main conversational path uses the overload below
        // to surface the canonical, front-door intentType (computed once, reused — never re-derived).
        // None of these auxiliary callers ever produced structured LLM semantics (they're plain
        // administrative/text answers), so llmSemantics is null — ResponseArtifactsBuilder's
        // legacy fallback applies, which is honest here since there's no evidence to decompose.
        // None of them run the reasoning loop either, so there is nothing to preserve per-step.
        return buildResponse(conversationId, runKey, answer, decisionType, agent, confidence,
                needsKnowledge, quickRefs, asyncOps, queryData, List.of(), reasoningSteps, learningsApplied,
                IntentType.OPERATIONAL_INVESTIGATION, null, List.of());
    }

    private ChatResponse buildResponse(String conversationId, String runKey, String answer,
            String decisionType, NexusAgent agent, double confidence, boolean needsKnowledge,
            List<Map<String, Object>> quickRefs, List<Map<String, Object>> asyncOps,
            List<Map<String, Object>> queryData, List<Map<String, Object>> investigationDatasets,
            List<Map<String, Object>> reasoningSteps,
            List<String> learningsApplied, IntentType intentType, StructuredAnswer llmSemantics,
            List<ResponseArtifacts.Section> resolvedSections) {
        String evidenceMode = (decisionType.contains("QUERY") || decisionType.contains("HYBRID"))
                ? "LIVE_DATA" : "MEMORY";
        OrchestratorDecision decision = new OrchestratorDecision(
                decisionType,
                intentType.name(),
                evidenceMode,
                decisionType.contains("QUERY") || decisionType.contains("HYBRID"),
                !decisionType.contains("QUERY"),
                "ASK_CLARIFICATION".equals(decisionType));
        ResponseArtifacts artifacts = ResponseArtifactsBuilder.build(
                null, answer, reasoningSteps, queryData, investigationDatasets, quickRefs, null, llmSemantics,
                resolvedSections);
        return new ChatResponse(
                conversationId, runKey, answer, List.of(), decision,
                agent != null ? agent.agentKey() : null,
                agent != null ? agent.name() : null,
                agent != null ? agent.domainKeys() : null,
                confidence, needsKnowledge, "",
                quickRefs, asyncOps,
                queryData        != null ? queryData        : List.of(),
                investigationDatasets != null ? investigationDatasets : List.of(),
                reasoningSteps   != null ? reasoningSteps   : List.of(),
                learningsApplied != null ? learningsApplied : List.of(),
                null, artifacts);
    }

    /** Converts {@link com.sei.nexus.reasoning.InvestigationDataset}s (the reasoning engine's
     *  internal, per-step transport shape) into the {@code List<Map<String,Object>>} shape used
     *  by {@code ChatResponse}/{@code ResponseArtifactsBuilder} — the same convention every other
     *  list on those contracts already uses (see {@code reasoningSteps}). Package-private static
     *  seam: pure, mechanical field copy — {@code stepNo}/{@code description} verbatim, {@code
     *  rows} verbatim, {@code rowCount} a plain size() — no interpretation of any kind. */
    static List<Map<String, Object>> investigationDatasetsToMaps(
            List<com.sei.nexus.reasoning.InvestigationDataset> datasets) {
        if (datasets == null || datasets.isEmpty()) return List.of();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.sei.nexus.reasoning.InvestigationDataset d : datasets) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("stepNo", d.stepNo());
            m.put("description", d.description() != null ? d.description() : "");
            m.put("rows", d.rows());
            m.put("rowCount", d.rows().size());
            // Optional LLM-declared chart hint (see ReasoningPlanner.StepPlan / InvestigationDataset)
            // — carried through verbatim, mechanical field copy only.
            m.put("chartType", d.chartType());
            m.put("categoryKey", d.categoryKey());
            m.put("valueKeys", d.valueKeys() != null ? d.valueKeys() : List.of());
            // Optional, purely presentational LLM-authored labels (see ReasoningPlanner.
            // SYSTEM_PROMPT's OPTIONAL HUMAN-READABLE LABELS guidance) — carried through verbatim,
            // mechanical field copy only, exactly like chartType/categoryKey/valueKeys above.
            m.put("categoryLabel", d.categoryLabel());
            m.put("valueLabels", d.valueLabels() != null ? d.valueLabels() : List.of());
            m.put("metricLabel", d.metricLabel());
            out.add(m);
        }
        return out;
    }

    /** Converts EvidenceStore steps to the execResults format expected by composeAnswer.
     *  Package-private static seam: pure function of evidence, no instance state.
     *
     *  <p>Every step whose query actually returned rows is included — regardless of what a
     *  LATER step went on to do, and regardless of the evaluator's "keep investigating" verdict
     *  on this or any step (Investigation-Step Semantics: a step's own success is independent of
     *  whether reasoning continued past it). Checks {@code outcome} — this step's own result —
     *  never {@code evaluatorDecision}, which does not describe this step's own outcome at all
     *  for a non-evaluated step (metadata, clarification, rejection, error). */
    static List<Map<String, Object>> evidenceToExecResults(EvidenceStore evidence) {
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        for (EvidenceStore.StepEvidence s : evidence.getSteps()) {
            if (!s.rows().isEmpty()) {
                results.add(Map.of("step", s.stepNo(), "rows", s.rows(),
                        "sql", s.sql() != null ? s.sql() : ""));
            } else if ("ERROR".equals(s.outcome())) {
                results.add(Map.of("step", s.stepNo(), "error",
                        s.evaluatorRationale() != null ? s.evaluatorRationale() : "Step failed"));
            } else if ("CLARIFICATION_NEEDED".equals(s.outcome())) {
                // Semantic Reasoning Over Authoritative Value Domains: the planner declined to
                // generate SQL for a term it could not defensibly resolve — distinct from a
                // governance "blocked" outcome, so composeAnswer can phrase it as a genuine
                // clarifying question rather than a policy-denial message.
                results.add(Map.of("step", s.stepNo(), "clarification", true, "question",
                        s.evaluatorRationale() != null ? s.evaluatorRationale() : "Clarification needed"));
            } else if ("LITERAL_REJECTED".equals(s.outcome()) || "LITERAL_BLOCKED".equals(s.outcome())) {
                // PRO-33 deterministic literal validation (LiteralValidator) is a data-quality/
                // format check, never a governance or access-control decision — kept out of the
                // generic "blocked" bucket below (checked before it, since "LITERAL_BLOCKED" also
                // contains "BLOCK") so composeAnswer never tells the user a technical validation
                // outcome is "not permitted under current data access policy".
                results.add(Map.of("step", s.stepNo(), "validationFailed", true, "reason",
                        s.evaluatorRationale() != null ? s.evaluatorRationale() : "Literal validation failed"));
            } else if (s.outcome() != null && s.outcome().contains("BLOCK")) {
                results.add(Map.of("step", s.stepNo(), "blocked", true, "reason",
                        s.evaluatorRationale() != null ? s.evaluatorRationale() : "Step blocked"));
            }
        }
        return results;
    }

    /**
     * Persists the outcome of a live-data investigation: concludes the reasoning session and,
     * when the investigation was backed by real query results, records an OperationalFinding.
     * Findings are created only from data-backed, substantive conclusions (never empty/error
     * answers), so the homepage surfaces real intelligence rather than noise. Best-effort:
     * a persistence failure never breaks the user's answer.
     */
    private void persistInvestigationOutcome(String sessionKey, NexusAgent agent, String question,
                                             String answer, List<Map<String, Object>> queryData,
                                             String conversationId) {
        try {
            boolean dataBacked  = queryData != null && !queryData.isEmpty();
            boolean substantive = answer != null && answer.trim().length() > 40;
            Double  confidence  = dataBacked ? 0.8 : 0.6;   // evidence-strength annotation, not a business figure
            Instant now         = Instant.now();

            // The session is no longer running — conclude it with the composed answer.
            reasoningRepository.updateSessionStatus(sessionKey, "CONCLUDED",
                    substantive ? answer : null, substantive ? confidence : null, now);

            // Only data-backed, substantive investigations become findings.
            if (!dataBacked || !substantive) return;

            String domainKey = (agent != null && agent.domainKeys() != null && !agent.domainKeys().isBlank())
                    ? agent.domainKeys() : "PLATFORM";
            String agentKey  = agent != null ? agent.agentKey() : null;

            // evidence_summary is left null — never raw query JSON or internal snapshots. The
            // description carries the analysis; related_entity_keys carries the investigation
            // lineage (the conversation) so the Executive Brief can open the exact investigation.
            OperationalFinding finding = new OperationalFinding(
                    Keys.uniqueKey("finding"), domainKey, agentKey, "INVESTIGATION",
                    findingTitle(question, answer), answer, null, conversationId,
                    confidence, "OPEN", now, now, null);
            reasoningRepository.saveFinding(finding);
        } catch (Exception e) {
            log.warn("Failed to persist investigation outcome for session {}: {}", sessionKey, e.getMessage());
        }
    }

    /**
     * When a routed-agent chat produces a substantive, data-backed answer, record it as an
     * OperationalFinding so the homepage reflects real intelligence. Best-effort — a persistence
     * failure never affects the user's answer.
     */
    /**
     * The agent's query results, pulled from its recorded step log — the rows the agent already
     * produced during execution (each query_database TOOL_CALL's output). Returns the most
     * data-rich result set, capped at 100 rows, matching the reasoning path's query_data.
     * Presentation/persistence only — nothing is re-executed. Empty when no query ran.
     */
    @SuppressWarnings("unchecked")
    /** Response-assembly correction only: AgentRunner already computes and persists a full
     *  step trace (ZevraSession.stepsJson) — CONTEXT_RESOLVE / TOOL_CALL / FINAL_ANSWER — that
     *  was previously discarded when building ChatResponse.reasoningSteps for agent-routed
     *  answers. This projects that already-existing trace into the same field the
     *  conversational path populates, so ReasoningTrace has something real to show. No new
     *  data is computed here — every field below is read straight from what AgentRunner
     *  already recorded (tool name/args, business-object count, step duration). */
    private List<Map<String, Object>> agentReasoningSteps(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) return List.of();
        try {
            List<Map<String, Object>> steps = objectMapper.readValue(stepsJson, List.class);
            List<Map<String, Object>> out = new ArrayList<>();
            int stepNo = 0;
            for (Map<String, Object> step : steps) {
                String type = String.valueOf(step.get("type"));
                stepNo++;
                String description = switch (type) {
                    case "CONTEXT_RESOLVE" -> {
                        Object bo = step.get("businessObjects");
                        int n = bo instanceof List<?> l ? l.size() : 0;
                        yield "Resolved business context (" + n + " business object" + (n == 1 ? "" : "s") + ")";
                    }
                    case "TOOL_CALL"    -> "Called " + step.get("tool");
                    case "FINAL_ANSWER" -> "Composed final answer";
                    default             -> type;
                };
                Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("stepNo", stepNo);
                entry.put("type", type);
                entry.put("description", description);
                if (step.get("input") instanceof Map<?, ?> input && input.get("sql") != null) {
                    entry.put("sql", String.valueOf(input.get("sql")));
                }
                if (step.get("durationMs") instanceof Number n) {
                    entry.put("executionMs", n.longValue());
                }
                out.add(entry);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> extractAgentQueryRows(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) return List.of();
        try {
            List<Map<String, Object>> steps = objectMapper.readValue(stepsJson, List.class);
            List<Map<String, Object>> best = List.of();
            for (Map<String, Object> step : steps) {
                if (!"TOOL_CALL".equals(step.get("type"))) continue;
                Object output = step.get("output");
                if (output instanceof List<?> rows && rows.size() > best.size()
                        && !rows.isEmpty() && rows.get(0) instanceof Map) {
                    best = (List<Map<String, Object>>) rows;
                }
            }
            return best.size() > 100 ? new java.util.ArrayList<>(best.subList(0, 100)) : best;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Reads the Zevra Agent's own semantic decomposition straight from its FINAL_ANSWER step
     * (see AgentRunner.extractFinalSemantics / AgentToolRegistry's final_answer schema) — never
     * computed here. Returns {@link StructuredAnswer#plain} when the agent didn't populate the
     * optional fields (e.g. a plain-text termination, or an older session predating this
     * contract), which downstream (ResponseArtifactsBuilder) means the legacy fallback applies.
     */
    @SuppressWarnings("unchecked")
    private StructuredAnswer extractAgentSemantics(String stepsJson, String fallbackAnswer) {
        if (stepsJson == null || stepsJson.isBlank()) return StructuredAnswer.plain(fallbackAnswer);
        try {
            List<Map<String, Object>> steps = objectMapper.readValue(stepsJson, List.class);
            for (Map<String, Object> step : steps) {
                if (!"FINAL_ANSWER".equals(step.get("type"))) continue;
                String understanding = step.get("understanding") instanceof String s ? s : null;
                List<String> keyFindings = toStringList(step.get("key_findings"));
                List<String> relatedFacts = toStringList(step.get("related_facts"));
                String recommendation = step.get("recommendation") instanceof String s ? s : null;
                List<String> followUpQuestions = toStringList(step.get("follow_up_questions"));
                return new StructuredAnswer(fallbackAnswer, understanding, keyFindings,
                        relatedFacts, recommendation, followUpQuestions);
            }
            return StructuredAnswer.plain(fallbackAnswer);
        } catch (Exception e) {
            return StructuredAnswer.plain(fallbackAnswer);
        }
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (Object o : list) { if (o != null) out.add(String.valueOf(o)); }
        return out;
    }

    /** Phrases that mark an answer as a non-finding (missing data/schema/knowledge). */
    private static final java.util.List<String> NON_ANSWER_MARKERS = java.util.List.of(
            "does not contain", "no tables", "not available", "cannot find", "couldn't find",
            "could not find", "i don't have", "i do not have", "no data", "schema provided",
            "unable to", "no approved", "not present in", "does not include", "no relevant");

    private void persistAgentFinding(ZevraAgent agent, String question, String answer,
                                     ZevraSession session, String runKey, String conversationId) {
        try {
            if (answer == null || answer.trim().length() < 40) return;
            String lower = answer.toLowerCase();
            // A finding must be a real, data-grounded conclusion — not an "I couldn't find it"
            // non-answer. Skip responses that signal missing data/schema/knowledge.
            for (String marker : NON_ANSWER_MARKERS) {
                if (lower.contains(marker)) return;
            }
            // Data-backed when the agent actually executed a query that returned rows.
            boolean dataBacked = session != null && session.stepsJson() != null
                    && session.stepsJson().contains("\"rowCount\"")
                    && !session.stepsJson().contains("\"rowCount\":0");
            double  confidence = dataBacked ? 0.8 : 0.6;
            Instant now        = Instant.now();
            String  title      = findingTitle(question, answer);
            String  agentName  = agent != null && agent.name() != null ? agent.name() : "Zevra";

            // Record the investigation as a concluded reasoning session so the homepage
            // "Investigations" panel reflects real, varied agent activity (not test noise).
            try {
                reasoningRepository.saveSession(new com.sei.nexus.reasoning.ReasoningSession(
                        Keys.uniqueKey("rsession"), runKey, conversationId, agentName, "PLATFORM",
                        question, null, "CONCLUDED", title, confidence, now, now));
            } catch (Exception ignore) { /* session recording is best-effort */ }

            // evidence_summary is left null: the description already carries the full analysis,
            // and this field must never hold raw query JSON or internal text — the UI renders it
            // verbatim across every tenant. related_entity_keys carries the investigation lineage
            // (the conversation that produced this finding) so the Executive Brief can always open
            // the exact investigation — deterministic traceability, not inference.
            OperationalFinding finding = new OperationalFinding(
                    Keys.uniqueKey("finding"), "PLATFORM",
                    agent != null ? agent.id() : null, "INVESTIGATION",
                    title, answer, null, conversationId,
                    confidence, "OPEN", now, now, null);
            reasoningRepository.saveFinding(finding);
        } catch (Exception e) {
            log.warn("Failed to persist agent finding: {}", e.getMessage());
        }
    }

    /**
     * A clean finding/investigation title: the answer's opening statement, unless that opener is a
     * list intro (ends with ':' or "as follows"/"are:") — in which case the question reads better.
     */
    private static String findingTitle(String question, String answer) {
        String t = answer.trim().replaceAll("\\s+", " ");
        int dot = t.indexOf(". ");
        String first = dot > 15 ? t.substring(0, dot + 1) : (t.length() <= 120 ? t : null);
        if (first != null) {
            String fl = first.toLowerCase();
            boolean listIntro = first.endsWith(":") || fl.contains("as follows")
                    || fl.contains("are:") || fl.contains("the following");
            if (!listIntro && first.length() <= 140) return first;
        }
        String q = question == null ? "Investigation" : question.trim();
        return q.length() <= 140 ? q : q.substring(0, 140).trim() + "…";
    }

    // =========================================================================
    // Utility helpers
    // =========================================================================

    /**
     * PRO-33: the literal validator's scope — every domain-bearing column the
     * resolver found on the entity-bound tables, keyed by qualified
     * {@code table.column} and, when unambiguous, by bare column name (SQL
     * aliases hide the real table, so the bare key is the alias fallback).
     * Empty map ⇒ validation is a no-op (zero-cost).
     */
    static Map<String, com.sei.nexus.semanticmodel.ColumnValueDomain>
            buildLiteralScope(ResolvedQuestion resolved) {
        // Unified Answer Engine, Phase 2: AgentBrain owns this derivation. Chat still resolves
        // its own question (the grounding swap is Phase 3), but it no longer keeps a second copy
        // of the rule — behaviour is identical.
        return com.sei.nexus.agentbrain.AgentBrain.literalScopeOf(resolved);
    }

    private List<String> toDomainKeyList(NexusAgent agent) {
        if (agent == null || agent.domainKeys() == null || agent.domainKeys().isBlank()) return List.of();
        return List.of(agent.domainKeys().split(",\\s*"));
    }

    /**
     * Filters the full knowledge graph context string to lines/sections that contain
     * at least one keyword from the user's question. Avoids sending 50+ entities
     * when only 2-3 are relevant to the question.
     *
     * Falls back to the full context if filtering produces nothing (safety net).
     */
    /**
     * PRO-31 form: resolved canonical tokens join the question keywords so the
     * graph filter keeps lines the user referenced through business language
     * (e.g. "TX" keeps the line mentioning "texas"/"state_province"). An empty
     * token set reproduces the pre-BLR behavior exactly.
     */
    private String filterGraphContext(String fullGraph, String question,
            java.util.Set<String> expandedTokens) {
        if (question == null || question.isBlank()) return fullGraph;

        // Shared keyword helper — same extraction the entity-block ranking uses.
        java.util.Set<String> keywords = new java.util.HashSet<>(
                com.sei.nexus.common.QuestionKeywords.extract(question));
        if (expandedTokens != null) keywords.addAll(expandedTokens);

        if (keywords.isEmpty()) return fullGraph;

        // Keep lines that mention at least one keyword, plus header/footer lines
        StringBuilder filtered = new StringBuilder();
        for (String line : fullGraph.split("\n")) {
            String lower = line.toLowerCase();
            boolean isStructural = lower.startsWith("===") || lower.startsWith("---")
                    || lower.startsWith("[group") || lower.isBlank();
            boolean hasKeyword = keywords.stream().anyMatch(lower::contains);
            if (isStructural || hasKeyword) {
                filtered.append(line).append("\n");
            }
        }

        String result = filtered.toString().trim();
        // Safety: if filtering removed everything meaningful, return the full context
        return result.isBlank() || result.length() < 50 ? fullGraph : result;
    }

    private List<String> toConnKeyList(NexusAgent agent) {
        if (agent == null || agent.connectionKeys() == null || agent.connectionKeys().isBlank()) return List.of();
        return List.of(agent.connectionKeys().split(",\\s*"));
    }

    /** Extracts the first JSON object or array from a potentially padded LLM response. */
    private String extractJson(String text) {
        if (text == null) return "{}";
        int startArr = text.indexOf('[');
        int startObj = text.indexOf('{');
        if (startArr >= 0 && (startObj < 0 || startArr < startObj)) {
            int end = text.lastIndexOf(']');
            return end > startArr ? text.substring(startArr, end + 1) : "[]";
        }
        if (startObj >= 0) {
            int end = text.lastIndexOf('}');
            return end > startObj ? text.substring(startObj, end + 1) : "{}";
        }
        return text;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
