package com.sei.nexus.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agent.AgentPlaybook;
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
import com.sei.nexus.enterprise.EnterpriseMapService;
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
import com.sei.nexus.agentrunner.AgentRunner;
import com.sei.nexus.agentrunner.ZevraAgent;
import com.sei.nexus.agentrunner.ZevraAgentRouter;
import com.sei.nexus.agentrunner.ZevraSession;
import com.sei.nexus.semantic.LearningContextBuilder;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final RunRepository runRepository;
    private final DocumentMemoryService documentMemoryService;
    private final EnterpriseMapService enterpriseMapService;
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
    // ── Zevra Agentic AI routing ──────────────────────────────────────────────
    private final ZevraAgentRouter         zevraAgentRouter;
    private final AgentRunner              agentRunner;

    // Max characters for entity schema context per LLM call.
    // Configurable via nexus.context.max-entity-chars — keeps prompts lean
    // without hardcoding tenant-specific table counts.
    @org.springframework.beans.factory.annotation.Value("${nexus.context.max-entity-chars:1500}")
    private int maxEntityContextChars;

    public ChatService(RunRepository runRepository,
                       DocumentMemoryService documentMemoryService,
                       EnterpriseMapService enterpriseMapService,
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
                       ZevraAgentRouter zevraAgentRouter,
                       AgentRunner agentRunner) {
        this.runRepository            = runRepository;
        this.documentMemoryService    = documentMemoryService;
        this.enterpriseMapService     = enterpriseMapService;
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
        this.zevraAgentRouter         = zevraAgentRouter;
        this.agentRunner              = agentRunner;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public ChatResponse ask(ChatRequest request, String userEmail) {
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
        String tenantSchemaForRouting = com.sei.nexus.tenant.TenantContext.getSchema();
        java.util.Optional<ZevraAgent> routedZevraAgent =
                zevraAgentRouter.route(raw, tenantSchemaForRouting);
        if (routedZevraAgent.isPresent()) {
            ZevraAgent za = routedZevraAgent.get();
            String convId  = (request.conversationId() != null && !request.conversationId().isBlank())
                    ? request.conversationId() : Keys.conversationKey();
            String runKey  = (request.clientRunKey() != null && !request.clientRunKey().isBlank())
                    ? request.clientRunKey() : Keys.runKey();

            NexusRun run = new NexusRun(runKey, convId, za.slug(), null,
                    userEmail, raw, null, null, "RUNNING", null, null, null);
            runRepository.save(run);
            try {
                ZevraSession session = agentRunner.run(za, raw);
                String answer = session.finalOutput() != null
                        ? session.finalOutput()
                        : "The agent completed but produced no response.";
                runRepository.update(runKey, answer, "ZEVRA_AGENT", "COMPLETE", null);

                OrchestratorDecision decision = new OrchestratorDecision(
                        "ZEVRA_AGENT", "OPERATIONAL_INVESTIGATION", "LIVE_DATA",
                        true, false, false);
                return new ChatResponse(convId, runKey, answer, List.of(), decision,
                        za.slug(), za.name(), null, 0.9, false, "",
                        List.of(), List.of(), List.of(), List.of(), List.of());
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

        // STEP 2: Conversation
        String conversationId = (request.conversationId() != null && !request.conversationId().isBlank())
                ? request.conversationId() : Keys.conversationKey();

        // STEP 3: Recent history
        List<NexusRun> history = runRepository.findConversationRuns(conversationId, 8);

        // STEP 4: Route agent — use raw question only (not file content)
        NexusAgent agent = resolveAgent(request.agentKey(), raw, history);
        double routingConfidence = agent != null ? 0.9 : 0.5;

        // STEP 5: Save run — use client-provided key when present (enables SSE pre-subscription)
        String runKey = (request.clientRunKey() != null && !request.clientRunKey().isBlank())
                ? request.clientRunKey() : Keys.runKey();
        NexusRun run = new NexusRun(runKey, conversationId,
                agent != null ? agent.agentKey() : null,
                agent != null ? agent.domainKeys() : null,
                userEmail, raw, null, null, "RUNNING", null, null, null);
        runRepository.save(run);

        try {
            List<String> domainKeys = toDomainKeyList(agent);
            List<String> connKeys = toConnKeyList(agent);

            // STEP 6: Memory retrieval — semantic search on the user's intent, not the file
            List<DocumentChunk> memChunks = documentMemoryService.retrieveContext(raw, domainKeys);

            // STEP 7: Enterprise + Semantic + Anomaly + Findings context
            Map<String, Object> entCtx = enterpriseMapService.operationalContext(domainKeys, connKeys, raw);
            String semCtx = semanticService.buildSemanticContext(domainKeys, raw);
            List<OperationalFinding> findings = reasoningRepository.findRecentFindings(domainKeys, 5);
            String anomalyCtx = baselineService.getAnomalyContext(domainKeys);

            // STEP 8: Write intent boundary — check user's question only
            if (isWriteIntent(raw)) {
                String ans = "Zevra is a read-only operational intelligence platform. I can help you " +
                        "investigate and understand your business data, but cannot perform modifications. " +
                        "Use /request-source to request workflow integrations.";
                runRepository.update(runKey, ans, "READ_ONLY_BOUNDARY", "COMPLETE", null);
                return buildResponse(conversationId, runKey, ans, "READ_ONLY_BOUNDARY",
                        agent, routingConfidence, false, List.of(), List.of(), List.of(), List.of(), List.of());
            }

            // STEP 9: Prior result check
            Optional<String> priorSnapshot = runRepository.latestResultSnapshot(conversationId);

            // STEP 10: LLM decision — routes on user intent (raw), not file content.
            // This is the key: the router sees "do these orders exist in the system?" and
            // naturally picks QUERY_LIVE_DATA. It doesn't need to see the CSV to decide that.
            Map<String, Object> decision = getLlmDecision(raw, memChunks, entCtx, semCtx,
                    findings, anomalyCtx, history, priorSnapshot.isPresent(), agent);
            String decisionType = (String) decision.getOrDefault("type", "ANSWER_FROM_MEMORY");

            String answer;
            List<Map<String, Object>> asyncOps        = new ArrayList<>();
            List<Map<String, Object>> queryData        = new ArrayList<>();
            List<Map<String, Object>> reasoningSteps   = new ArrayList<>();
            List<String>              learningsApplied  = new ArrayList<>();
            String resultSnapshot = null;

            switch (decisionType) {
                case "ANSWER_FROM_PRIOR_RESULTS" -> {
                    if (priorSnapshot.isPresent()) {
                        answer = answerFromPriorResults(raw, priorSnapshot.get(), memChunks, history, agent);
                    } else {
                        // enrichedQuestion so the file content is available if the question was about the file
                        answer = answerFromMemory(enrichedQuestion, memChunks, semCtx, entCtx, agent);
                    }
                }
                case "ANSWER_FROM_MEMORY" -> {
                    // enrichedQuestion: if the user uploaded a file and asked about it, this path
                    // has the file content available so the AI can summarise / translate / explain it.
                    answer = answerFromMemory(enrichedQuestion, memChunks, semCtx, entCtx, agent);
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
                case "QUERY_LIVE_DATA", "HYBRID_DOC_AND_DATA" -> {
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
                    String schemaCtx = buildContextSummary(raw, memChunks, entCtx, semCtx, findings,
                            anomalyCtx, false, history, agent);
                    if (!playbookCtx.isBlank()) schemaCtx = schemaCtx + "\nPlaybook:\n" + playbookCtx;

                    // ── Phase 3: inject learned business vocabulary into the planner context ──
                    String agentDomainKey = agent != null ? agent.domainKeys() : null;
                    LearningContextBuilder.LearningContext learningCtx =
                            learningContextBuilder.build(agentDomainKey, conversationId);
                    if (!learningCtx.isEmpty()) {
                        schemaCtx = schemaCtx + "\n\n" + learningCtx.contextText();
                        learningsApplied.addAll(learningCtx.termsApplied());
                    }

                    // Run the iterative reasoning loop (Phase 2).
                    // The engine generates one SQL step at a time, executes it through the
                    // governance chain, evaluates whether the evidence is sufficient, and
                    // continues until the evaluator says SUFFICIENT, DEAD_END, or MAX_STEPS.
                    ReasoningEngine.ReasoningResult reasonResult = reasoningEngine.reason(
                            raw, enrichedQuestion, sessionKey, schemaCtx, runKey, userEmail, forceAsync);

                    resultSnapshot = reasonResult.resultSnapshot();
                    queryData      = reasonResult.queryData();

                    // Convert EvidenceStore steps to the execResults format composeAnswer expects
                    List<Map<String, Object>> execResults = evidenceToExecResults(reasonResult.evidence());

                    answer = composeAnswer(raw, attachmentSummary, execResults, memChunks, semCtx,
                            findings, anomalyCtx, agent, "HYBRID_DOC_AND_DATA".equals(decisionType));

                    // Notify SSE clients the answer is ready, then close the stream
                    reasoningEventBus.publish(runKey, "answer_ready", Map.of("answer", answer));
                    reasoningEventBus.complete(runKey);

                    // ── Phase 3: fire-and-forget learning from this successful run ──
                    // Pick the SQL from the most data-rich step for term extraction.
                    String bestSql = reasonResult.evidence().getSteps().stream()
                            .filter(s -> !s.rows().isEmpty() && s.sql() != null)
                            .max(java.util.Comparator.comparingInt(s -> s.rows().size()))
                            .map(s -> s.sql())
                            .orElse(null);
                    if (bestSql != null) {
                        semanticLearningService.learnFromRun(
                                runKey, raw, bestSql, agentDomainKey, conversationId);
                    }

                    // Collect step summaries for the frontend reasoning trace
                    for (EvidenceStore.StepEvidence s : reasonResult.evidence().getSteps()) {
                        reasoningSteps.add(Map.of(
                                "stepNo",             s.stepNo(),
                                "description",        s.description() != null ? s.description() : "",
                                "sql",                s.sql()         != null ? s.sql()         : "",
                                "rowCount",           s.rows().size(),
                                "rowSummary",         s.rowSummary()          != null ? s.rowSummary()          : "",
                                "evaluatorDecision",  s.evaluatorDecision()   != null ? s.evaluatorDecision()   : "",
                                "evaluatorRationale", s.evaluatorRationale()  != null ? s.evaluatorRationale()  : "",
                                "executionMs",        s.executionMs()));
                    }
                }
                default -> {
                    answer = "I was unable to determine how to answer this question with available approved sources.";
                }
            }

            runRepository.update(runKey, answer, decisionType, "COMPLETE", resultSnapshot);
            runRepository.saveEvidence(Keys.uniqueKey("ev"), runKey, "ROUTING",
                    toJson(Map.of("decision_type", decisionType,
                            "agent", agent != null ? agent.agentKey() : "none",
                            "memory_chunks", memChunks.size())));

            List<Map<String, Object>> quickRefs = buildQuickRefinements(decisionType, raw);
            return buildResponse(conversationId, runKey, answer, decisionType,
                    agent, routingConfidence, "KNOWLEDGE_GAP".equals(decisionType),
                    quickRefs, asyncOps, queryData, reasoningSteps, learningsApplied);

        } catch (Exception e) {
            log.error("Chat orchestration failed for run {}: {}", runKey, e.getMessage(), e);
            runRepository.update(runKey, "Investigation encountered an error: " + e.getMessage(), "ERROR", "FAILED", null);
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Investigation failed: " + e.getMessage());
        }
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

    private Map<String, Object> getLlmDecision(String question, List<DocumentChunk> memChunks,
            Map<String, Object> entCtx, String semCtx, List<OperationalFinding> findings,
            String anomalyCtx, List<NexusRun> history, boolean hasPrior, NexusAgent agent) {
        try {
            String ctx = buildContextSummary(question, memChunks, entCtx, semCtx, findings, anomalyCtx, hasPrior, history, agent);
            String prompt = "Question: " + question + "\n\nContext:\n" + ctx;
            String sys = """
                    You are the SEI Nexus orchestration engine. Decide the best answer mode.
                    Return JSON only:
                    {
                      "type": "ANSWER_FROM_MEMORY|QUERY_LIVE_DATA|HYBRID_DOC_AND_DATA|ASK_CLARIFICATION|KNOWLEDGE_GAP|ANSWER_FROM_PRIOR_RESULTS",
                      "intentType": "OPERATIONAL_INVESTIGATION|ANALYTICAL|INFORMATIONAL|FOLLOW_UP",
                      "requiresExecution": true,
                      "requiresMemory": true,
                      "requiresClarification": false,
                      "clarification_question": ""
                    }

                    Routing rules (in priority order):
                    1. Use ANSWER_FROM_PRIOR_RESULTS ONLY when the question is specifically asking about
                       the previous answer itself — not new data. Examples: "explain that", "show me the SQL
                       you used", "how did you get that number", "why that result", "are you sure",
                       "what query ran", "break down that specific number".
                       DO NOT use this for any question that asks for new data, a different metric,
                       a different filter, or a different entity — even if it is in the same conversation.
                    2. Use QUERY_LIVE_DATA if the question needs fresh data from the database — including
                       follow-up questions that ask for different metrics, different filters, or different
                       entities than what was previously returned.
                    3. Use ANSWER_FROM_MEMORY if document memory can answer without live data.
                    4. Use HYBRID_DOC_AND_DATA for complex questions needing both memory and live data.
                    5. Use KNOWLEDGE_GAP if no knowledge or data sources are available at all.
                    6. Use ASK_CLARIFICATION ONLY if the question is completely ambiguous AND there is no prior conversation context.
                    Key rule: when in doubt between ANSWER_FROM_PRIOR_RESULTS and QUERY_LIVE_DATA,
                    always choose QUERY_LIVE_DATA. It is always better to query fresh data than to
                    give a wrong answer from stale results.
                    """;
            String resp = aiClient.chat(List.of(ChatMessage.user(prompt)), sys);
            return objectMapper.readValue(extractJson(resp),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("LLM decision failed, defaulting to ANSWER_FROM_MEMORY: {}", e.getMessage());
            return Map.of("type", "ANSWER_FROM_MEMORY", "requiresExecution", false, "requiresMemory", true);
        }
    }

    // =========================================================================
    // Investigation plan generation
    // =========================================================================

    private String generateInvestigationPlan(String question, Map<String, Object> entCtx,
            String semCtx, List<DocumentChunk> memChunks, List<OperationalFinding> findings,
            String anomalyCtx, String playbookCtx, List<NexusRun> history, NexusAgent agent) {
        try {
            String ctx = buildContextSummary(question, memChunks, entCtx, semCtx, findings, anomalyCtx, false, history, agent);
            log.info("Investigation plan context:\n{}", ctx);
            String prompt = "Question: " + question + "\n\nContext:\n" + ctx +
                    (playbookCtx.isBlank() ? "" : "\n\nPlaybook:\n" + playbookCtx);
            String sys = """
                    You are a SQL investigation planner for SEI Nexus.
                    The context contains approved data sources with their tables and columns.
                    ALWAYS generate SQL steps when approved tables are available — never return an empty array if tables are listed.
                    Generate 1–3 SQL steps that directly answer the question using those tables.
                    Rules:
                    - Use only the tables listed under "Approved data sources"
                    - Use the connection_key shown for each table
                    - Use exact column names from the schema
                    - Joins, aggregations, GROUP BY, ORDER BY are all valid
                    - Do not use SELECT *; always name columns explicitly

                    ATTACHED FILE RULE:
                    If the question contains "=== ATTACHED FILE ===" markers, the content between those
                    markers is reference data uploaded by the user. You MUST:
                    1. Extract the relevant identifiers from the file (IDs, codes, reference numbers,
                       names — whatever column in the file matches an identifier column in the database).
                    2. Embed those values directly as literals in a SQL WHERE ... IN (...) clause or
                       equivalent filter. Example: WHERE id IN ('REF-001','REF-002','REF-003')
                    3. SELECT the database columns that let the user verify existence and compare status
                       (e.g. id, name, status, date — not SELECT *).
                    This is a cross-reference query: the file provides the lookup keys, the database
                    provides the ground truth. Never skip the WHERE filter; without it the query returns
                    unrelated rows.

                    Return a JSON array only (no extra text):
                    [{"step":1,"description":"...","sql":"SELECT ...","connection_key":"...","object_keys":"..."}]
                    """;
            return aiClient.chat(List.of(ChatMessage.user(prompt)), sys);
        } catch (Exception e) {
            log.warn("Investigation plan generation failed: {}", e.getMessage());
            return "[]";
        }
    }

    private List<Map<String, Object>> parsePlan(String json) {
        log.info("Investigation plan raw response: {}", json);
        try {
            String extracted = extractJson(json);
            List<Map<String, Object>> steps = objectMapper.readValue(extracted,
                    new TypeReference<List<Map<String, Object>>>() {});
            log.info("Parsed {} investigation steps", steps.size());
            return steps;
        } catch (Exception e) {
            log.warn("Failed to parse investigation plan: {}", e.getMessage());
            return List.of();
        }
    }

    private String generateHypothesisText(String question, NexusAgent agent) {
        try {
            String prompt = "Based on this question, state the most likely initial hypothesis in one sentence: " + question;
            return aiClient.chat(List.of(ChatMessage.user(prompt)),
                    "You generate concise investigative hypotheses.");
        } catch (Exception e) {
            return "Investigating: " + question;
        }
    }

    // =========================================================================
    // Answer composition
    // =========================================================================

    private String answerFromMemory(String question, List<DocumentChunk> memChunks,
            String semCtx, Map<String, Object> entCtx, NexusAgent agent) {
        try {
            StringBuilder ctx = new StringBuilder();
            memChunks.forEach(c -> ctx.append(c.chunkText()).append("\n\n"));
            if (!semCtx.isBlank()) ctx.append("Entity Context:\n").append(semCtx).append("\n\n");
            String prompt = "Question: " + question + "\n\nKnowledge:\n" + ctx;
            return aiClient.chat(List.of(ChatMessage.user(prompt)),
                    "You are SEI Nexus. Answer using only the provided knowledge. Be concise and business-focused.");
        } catch (Exception e) {
            return "Unable to retrieve answer from memory at this time.";
        }
    }

    private String answerFromPriorResults(String question, String snapshot,
            List<DocumentChunk> memChunks, List<NexusRun> history, NexusAgent agent) {
        try {
            StringBuilder prompt = new StringBuilder();

            // Include conversation history so the AI understands what was discussed
            if (history != null && !history.isEmpty()) {
                prompt.append("Conversation history:\n");
                int start = Math.max(0, history.size() - 4);
                for (int i = start; i < history.size(); i++) {
                    NexusRun r = history.get(i);
                    prompt.append("User: ").append(r.question()).append("\n");
                    if (r.answer() != null && !r.answer().isBlank()) {
                        String ans = r.answer().length() > 800
                                ? r.answer().substring(0, 800) + "…" : r.answer();
                        prompt.append("Nexus: ").append(ans).append("\n");
                    }
                }
                prompt.append("\n");
            }

            if (snapshot != null && !snapshot.isBlank()) {
                prompt.append("Live data result from last query:\n").append(snapshot).append("\n\n");
            }

            prompt.append("Follow-up question: ").append(question);

            return aiClient.chat(List.of(ChatMessage.user(prompt.toString())), """
                    You are SEI Nexus, an enterprise investigation AI.
                    Answer the follow-up question using the conversation history and prior results above.
                    If asked how you arrived at an answer, explain the data source, the query logic, and
                    the key data points that led to the conclusion. Be concise and precise.
                    """);
        } catch (Exception e) {
            return answerFromMemory(question, memChunks, "", Map.of(), agent);
        }
    }

    private String composeAnswer(String question, String attachmentSummary,
            List<Map<String, Object>> execResults,
            List<DocumentChunk> memChunks, String semCtx, List<OperationalFinding> findings,
            String anomalyCtx, NexusAgent agent, boolean includeMemory) {
        try {
            StringBuilder ctx = new StringBuilder();

            boolean anyRows = false;

            for (Map<String, Object> r : execResults) {
                if (r.containsKey("rows")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("rows");
                    ctx.append(buildRowSummary(rows));
                    if (!rows.isEmpty()) anyRows = true;
                } else if (r.containsKey("error")) {
                    ctx.append("Query error: ").append(r.get("error")).append("\n");
                } else if (r.containsKey("blocked")) {
                    ctx.append("Query blocked: ").append(r.get("reason")).append("\n");
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

            String systemPrompt = anyRows
                    ? """
                    You are Zevra, an enterprise operational intelligence AI.
                    The full data is already shown to the user in a table and chart — do NOT list individual records or reproduce row-level data.
                    Write a concise analyst summary of 2-4 sentences covering:
                    1. Total count and headline distribution (e.g. "42 records: 28 active, 10 closed, 4 pending")
                    2. The single most important insight or anomaly
                    3. One actionable recommendation only if clearly warranted
                    Use plain prose. Bold key numbers. No markdown headings or bullet lists unless there are multiple distinct anomalies.
                    """
                    : """
                    You are Zevra, an enterprise operational intelligence AI.
                    The database query returned zero matching rows.
                    If the user uploaded a file, those values were used as lookup parameters — zero rows means
                    those records do NOT exist in the connected database.
                    State this clearly and concisely: what was searched for, what was found (nothing), and
                    what the user should check next (e.g. wrong table, different ID format, data not yet loaded).
                    Do NOT summarise or analyse the uploaded file content itself — it was input, not output.
                    Keep the response to 2-3 sentences.
                    """;

            return aiClient.chat(List.of(ChatMessage.user(prompt)), systemPrompt);
        } catch (Exception e) {
            return "Investigation completed. " +
                    (execResults.stream().anyMatch(r -> r.containsKey("rows")) ? "Results are shown in the table below." : "No data returned.");
        }
    }

    /**
     * Builds a compact statistical summary of query rows for the AI context.
     * Sends distributions and totals, never individual row values — the frontend
     * table handles row-level display.
     */
    private String buildRowSummary(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "Query returned 0 rows.\n";
        StringBuilder sb = new StringBuilder();
        sb.append("Total rows: ").append(rows.size()).append("\n");

        java.util.Set<String> cols = rows.get(0).keySet();
        sb.append("Columns: ").append(String.join(", ", cols)).append("\n");

        for (String col : cols) {
            // Distribution for low-cardinality string columns (likely categorical)
            java.util.List<String> strVals = rows.stream()
                    .map(r -> String.valueOf(r.getOrDefault(col, "")))
                    .filter(v -> !v.isBlank() && !v.equals("null"))
                    .collect(java.util.stream.Collectors.toList());
            java.util.Map<String, Long> dist = strVals.stream()
                    .collect(java.util.stream.Collectors.groupingBy(v -> v, java.util.stream.Collectors.counting()));
            boolean isLowCardinality = dist.size() >= 2 && dist.size() <= 8 && dist.size() < rows.size();
            boolean looksNumeric = strVals.stream().allMatch(v -> { try { Double.parseDouble(v); return true; } catch (Exception e) { return false; } });
            boolean isId = col.toLowerCase().endsWith("_id") || col.equalsIgnoreCase("id");

            if (isLowCardinality && !looksNumeric) {
                sb.append("  ").append(col).append(" distribution: ").append(dist).append("\n");
            } else if (looksNumeric && !isId) {
                // Sum and average for numeric non-ID columns
                try {
                    double sum = strVals.stream().mapToDouble(Double::parseDouble).sum();
                    double avg = sum / strVals.size();
                    sb.append("  ").append(col).append(": sum=").append(String.format("%.2f", sum))
                      .append(", avg=").append(String.format("%.2f", avg)).append("\n");
                } catch (Exception ignored) {}
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // Context building
    // =========================================================================

    private String buildContextSummary(String question, List<DocumentChunk> memChunks,
            Map<String, Object> entCtx, String semCtx, List<OperationalFinding> findings,
            String anomalyCtx, boolean hasPrior, List<NexusRun> history, NexusAgent agent) {
        StringBuilder sb = new StringBuilder();

        if (agent != null) {
            sb.append("Agent: ").append(agent.name())
              .append(" | Domain: ").append(agent.domainKeys()).append("\n\n");
        }

        // ── Knowledge graph context — filtered to entities relevant to the question ──
        // Sending the full graph (50+ entities) on every call wastes thousands of tokens.
        // We extract keywords from the question and only include matching entities.
        List<String> domainKeys = toDomainKeyList(agent);
        if (!domainKeys.isEmpty()) {
            String graphCtx = knowledgeGraphService.buildGraphContext(domainKeys);
            if (!graphCtx.isBlank()) {
                String filteredGraph = filterGraphContext(graphCtx, question);
                if (!filteredGraph.isBlank()) sb.append(filteredGraph).append("\n");
            }
        }

        // ── Enterprise map entity context — truncated to configurable limit ───────
        // Full table schema can be 2000-3000 tokens. We truncate to maxEntityContextChars
        // (default 1500 chars ≈ 375 tokens) to cap the cost on every reasoning call.
        // This is a per-deployment setting, not a hardcoded value.
        boolean hasMemory = memChunks != null && !memChunks.isEmpty();
        String ec = entCtx.containsKey("entityContext") ? (String) entCtx.get("entityContext") : null;
        if (ec != null && !ec.isBlank()) {
            String truncated = ec.length() > maxEntityContextChars
                    ? ec.substring(0, maxEntityContextChars) + "\n[schema truncated — use describe_schema for more detail]"
                    : ec;
            sb.append("=== TABLE SCHEMA ===\n").append(truncated).append("\n");
        } else {
            sb.append("=== TABLE SCHEMA ===\n");
            sb.append("NO LIVE DATA SOURCES CONFIGURED. Do NOT generate SQL or use QUERY_LIVE_DATA.\n");
            if (hasMemory) {
                sb.append("Memory documents ARE available — use ANSWER_FROM_MEMORY.\n\n");
            } else {
                sb.append("No memory documents either — use KNOWLEDGE_GAP.\n\n");
            }
        }

        // ── Supporting context ────────────────────────────────────────────────
        if (hasMemory) {
            sb.append("Knowledge memory chunks: ").append(memChunks.size()).append(" available\n");
        }
        if (!semCtx.isBlank()) sb.append("Semantic layer: available\n");
        if (!findings.isEmpty()) sb.append("Prior findings: ").append(findings.size()).append("\n");
        if (!anomalyCtx.isBlank()) sb.append(anomalyCtx).append("\n");
        if (hasPrior) sb.append("Prior query result: available\n");

        // ── Conversation history (last 4 turns) ───────────────────────────────
        if (history != null && !history.isEmpty()) {
            sb.append("\nConversation so far:\n");
            int start = Math.max(0, history.size() - 4);
            for (int i = start; i < history.size(); i++) {
                NexusRun r = history.get(i);
                sb.append("User: ").append(r.question()).append("\n");
                if (r.answer() != null && !r.answer().isBlank()) {
                    String ans = r.answer().length() > 600
                            ? r.answer().substring(0, 600) + "…" : r.answer();
                    sb.append("Nexus: ").append(ans).append("\n");
                }
            }
            sb.append("\n");
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
    // Response building
    // =========================================================================

    private ChatResponse buildResponse(String conversationId, String runKey, String answer,
            String decisionType, NexusAgent agent, double confidence, boolean needsKnowledge,
            List<Map<String, Object>> quickRefs, List<Map<String, Object>> asyncOps,
            List<Map<String, Object>> queryData, List<Map<String, Object>> reasoningSteps,
            List<String> learningsApplied) {
        String evidenceMode = (decisionType.contains("QUERY") || decisionType.contains("HYBRID"))
                ? "LIVE_DATA" : "MEMORY";
        OrchestratorDecision decision = new OrchestratorDecision(
                decisionType,
                "OPERATIONAL_INVESTIGATION",
                evidenceMode,
                decisionType.contains("QUERY") || decisionType.contains("HYBRID"),
                !decisionType.contains("QUERY"),
                "ASK_CLARIFICATION".equals(decisionType));
        return new ChatResponse(
                conversationId, runKey, answer, List.of(), decision,
                agent != null ? agent.agentKey() : null,
                agent != null ? agent.name() : null,
                agent != null ? agent.domainKeys() : null,
                confidence, needsKnowledge, "",
                quickRefs, asyncOps,
                queryData        != null ? queryData        : List.of(),
                reasoningSteps   != null ? reasoningSteps   : List.of(),
                learningsApplied != null ? learningsApplied : List.of());
    }

    /** Converts EvidenceStore steps to the execResults format expected by composeAnswer. */
    private List<Map<String, Object>> evidenceToExecResults(EvidenceStore evidence) {
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        for (EvidenceStore.StepEvidence s : evidence.getSteps()) {
            if (!s.rows().isEmpty()) {
                results.add(Map.of("step", s.stepNo(), "rows", s.rows(),
                        "sql", s.sql() != null ? s.sql() : ""));
            } else if (s.evaluatorDecision() != null &&
                    (s.evaluatorDecision().contains("BLOCK") || "ERROR".equals(s.evaluatorDecision()))) {
                results.add(Map.of("step", s.stepNo(), "error",
                        s.evaluatorRationale() != null ? s.evaluatorRationale() : "Step failed"));
            }
        }
        return results;
    }

    // =========================================================================
    // Utility helpers
    // =========================================================================

    /**
     * Parses a comma-separated string of object keys from an investigation plan step
     * into a List for use by the governance chain.
     */
    private List<String> parseObjectKeys(String objKeys) {
        if (objKeys == null || objKeys.isBlank()) return List.of();
        List<String> result = new java.util.ArrayList<>();
        for (String key : objKeys.split(",")) {
            String trimmed = key.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
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
    private String filterGraphContext(String fullGraph, String question) {
        if (question == null || question.isBlank()) return fullGraph;

        // Extract meaningful keywords (3+ chars, skip stop words)
        java.util.Set<String> stops = java.util.Set.of(
                "show", "me", "all", "get", "find", "list", "the", "a", "an",
                "what", "which", "how", "many", "give", "tell", "and", "or", "for");
        java.util.Set<String> keywords = java.util.Arrays.stream(
                        question.toLowerCase().split("[\\s,?!.;:]+"))
                .filter(w -> w.length() >= 3 && !stops.contains(w))
                .collect(java.util.stream.Collectors.toSet());

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
