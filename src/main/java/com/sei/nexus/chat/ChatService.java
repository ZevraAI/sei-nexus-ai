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
import com.sei.nexus.enterprise.EnterpriseMapService;
import com.sei.nexus.knowledge.KnowledgeGap;
import com.sei.nexus.knowledge.KnowledgeGapRepository;
import com.sei.nexus.memory.DocumentChunk;
import com.sei.nexus.memory.DocumentMemoryService;
import com.sei.nexus.query.QueryExecutionRepository;
import com.sei.nexus.query.QueryGovernanceService;
import com.sei.nexus.reasoning.Hypothesis;
import com.sei.nexus.reasoning.OperationalFinding;
import com.sei.nexus.reasoning.ReasoningRepository;
import com.sei.nexus.reasoning.ReasoningSession;
import com.sei.nexus.reasoning.ReasoningStep;
import com.sei.nexus.run.NexusRun;
import com.sei.nexus.run.RunRepository;
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
    private final QueryGovernanceService queryGovernanceService;
    private final QueryExecutionRepository queryExecutionRepository;
    private final DynamicSqlService dynamicSqlService;
    private final ReasoningRepository reasoningRepository;
    private final BaselineService baselineService;
    private final KnowledgeGapRepository knowledgeGapRepository;
    private final KnowledgeGraphService knowledgeGraphService;
    private final AzureOpenAiClient aiClient;
    private final ObjectMapper objectMapper;

    public ChatService(RunRepository runRepository,
                       DocumentMemoryService documentMemoryService,
                       EnterpriseMapService enterpriseMapService,
                       SemanticService semanticService,
                       AgentRepository agentRepository,
                       QueryGovernanceService queryGovernanceService,
                       QueryExecutionRepository queryExecutionRepository,
                       DynamicSqlService dynamicSqlService,
                       ReasoningRepository reasoningRepository,
                       BaselineService baselineService,
                       KnowledgeGapRepository knowledgeGapRepository,
                       KnowledgeGraphService knowledgeGraphService,
                       AzureOpenAiClient aiClient,
                       ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.documentMemoryService = documentMemoryService;
        this.enterpriseMapService = enterpriseMapService;
        this.semanticService = semanticService;
        this.agentRepository = agentRepository;
        this.queryGovernanceService = queryGovernanceService;
        this.queryExecutionRepository = queryExecutionRepository;
        this.dynamicSqlService = dynamicSqlService;
        this.reasoningRepository = reasoningRepository;
        this.baselineService = baselineService;
        this.knowledgeGapRepository = knowledgeGapRepository;
        this.knowledgeGraphService = knowledgeGraphService;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public ChatResponse ask(ChatRequest request, String userEmail) {
        String raw = request.question() != null ? request.question().trim() : "";
        boolean forceAsync = false;

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
        final String question = raw;

        // STEP 2: Conversation
        String conversationId = (request.conversationId() != null && !request.conversationId().isBlank())
                ? request.conversationId() : Keys.conversationKey();

        // STEP 3: Recent history
        List<NexusRun> history = runRepository.findConversationRuns(conversationId, 8);

        // STEP 4: Route agent
        NexusAgent agent = resolveAgent(request.agentKey(), question, history);
        double routingConfidence = agent != null ? 0.9 : 0.5;

        // STEP 5: Save run
        String runKey = Keys.runKey();
        NexusRun run = new NexusRun(runKey, conversationId,
                agent != null ? agent.agentKey() : null,
                agent != null ? agent.domainKeys() : null,
                userEmail, question, null, null, "RUNNING", null, null, null);
        runRepository.save(run);

        try {
            List<String> domainKeys = toDomainKeyList(agent);
            List<String> connKeys = toConnKeyList(agent);

            // STEP 6: Memory retrieval
            List<DocumentChunk> memChunks = documentMemoryService.retrieveContext(question, domainKeys);

            // STEP 7: Enterprise + Semantic + Anomaly + Findings context
            Map<String, Object> entCtx = enterpriseMapService.operationalContext(domainKeys, connKeys, question);
            String semCtx = semanticService.buildSemanticContext(domainKeys, question);
            List<OperationalFinding> findings = reasoningRepository.findRecentFindings(domainKeys, 5);
            String anomalyCtx = baselineService.getAnomalyContext(domainKeys);

            // STEP 8: Write intent boundary
            if (isWriteIntent(question)) {
                String ans = "SEI Nexus is a read-only operational reasoning system. I can help you " +
                        "investigate and understand enterprise data, but cannot perform modifications. " +
                        "Use /request-source to request workflow integrations.";
                runRepository.update(runKey, ans, "READ_ONLY_BOUNDARY", "COMPLETE", null);
                return buildResponse(conversationId, runKey, ans, "READ_ONLY_BOUNDARY",
                        agent, routingConfidence, false, List.of(), List.of());
            }

            // STEP 9: Prior result check
            Optional<String> priorSnapshot = runRepository.latestResultSnapshot(conversationId);

            // STEP 10: LLM decision
            Map<String, Object> decision = getLlmDecision(question, memChunks, entCtx, semCtx,
                    findings, anomalyCtx, history, priorSnapshot.isPresent(), agent);
            String decisionType = (String) decision.getOrDefault("type", "ANSWER_FROM_MEMORY");

            String answer;
            List<Map<String, Object>> asyncOps = new ArrayList<>();
            String resultSnapshot = null;

            switch (decisionType) {
                case "ANSWER_FROM_PRIOR_RESULTS" -> {
                    answer = answerFromPriorResults(question, priorSnapshot.get(), memChunks, history, agent);
                }
                case "ANSWER_FROM_MEMORY" -> {
                    answer = answerFromMemory(question, memChunks, semCtx, entCtx, agent);
                }
                case "ASK_CLARIFICATION" -> {
                    answer = (String) decision.getOrDefault("clarification_question",
                            "Could you provide more context about what you're looking for?");
                }
                case "KNOWLEDGE_GAP" -> {
                    String gapKey = Keys.uniqueKey("gap");
                    KnowledgeGap gap = new KnowledgeGap(gapKey,
                            agent != null ? agent.domainKeys() : null,
                            "MISSING_KNOWLEDGE", runKey, question,
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
                            question, null, "ACTIVE", null, null, Instant.now(), null);
                    reasoningRepository.saveSession(session);

                    // Playbook context
                    String playbookCtx = "";
                    if (agent != null) {
                        List<AgentPlaybook> playbooks = agentRepository.findPlaybooksByAgent(agent.agentKey());
                        if (!playbooks.isEmpty()) {
                            playbookCtx = "Playbook steps: " + playbooks.get(0).investigationSteps();
                        }
                    }

                    // Generate investigation plan
                    String planJson = generateInvestigationPlan(question, entCtx, semCtx,
                            memChunks, findings, anomalyCtx, playbookCtx, history, agent);
                    List<Map<String, Object>> steps = parsePlan(planJson);

                    // Initial hypothesis
                    String hypText = generateHypothesisText(question, agent);
                    Hypothesis hyp = new Hypothesis(Keys.uniqueKey("hyp"), sessionKey, hypText,
                            0.5, "[]", "[]", "ACTIVE", Instant.now(), null);
                    reasoningRepository.saveHypothesis(hyp);

                    List<Map<String, Object>> execResults = new ArrayList<>();
                    int stepNo = 1;

                    for (Map<String, Object> step : steps) {
                        String sql = (String) step.get("sql");
                        String connKey = (String) step.get("connection_key");
                        String objKeys = step.containsKey("object_keys") ? (String) step.get("object_keys") : "";
                        String desc = step.containsKey("description") ? (String) step.get("description") : "Step " + stepNo;

                        if (sql == null || connKey == null || connKey.isBlank()) {
                            stepNo++;
                            continue;
                        }

                        var gov = queryGovernanceService.govern(runKey, stepNo,
                                agent != null ? agent.agentKey() : "", connKey, objKeys, sql, forceAsync);

                        // Record reasoning step
                        ReasoningStep rStep = new ReasoningStep(Keys.uniqueKey("rstep"), sessionKey, stepNo,
                                "DATA_CHECK", desc, "[]", null, 0.0, gov.executionKey(), Instant.now());
                        reasoningRepository.saveStep(rStep);

                        if ("BLOCK".equals(gov.route())) {
                            execResults.add(Map.of("step", stepNo, "blocked", true, "reason", gov.decisionReason()));
                        } else if ("ASK_FOR_FILTER".equals(gov.route())) {
                            execResults.add(Map.of("step", stepNo, "needs_filter", true, "reason", gov.decisionReason()));
                        } else if ("EXECUTE_ASYNC".equals(gov.route())) {
                            queryExecutionRepository.updateStatus(gov.executionKey(), "QUEUED", null, null, null);
                            asyncOps.add(Map.of("execution_key", gov.executionKey(),
                                    "description", desc, "status", "QUEUED"));
                        } else {
                            // EXECUTE_SYNC
                            try {
                                queryExecutionRepository.updateStatus(gov.executionKey(), "RUNNING", Instant.now(), null, null);
                                List<Map<String, Object>> rows = dynamicSqlService.executeQuery(
                                        connKey, gov.approvedSql(), gov.rowLimit());
                                String rJson = objectMapper.writeValueAsString(rows);
                                queryExecutionRepository.updateResult(gov.executionKey(), rJson, "SUCCESS", Instant.now());
                                execResults.add(Map.of("step", stepNo, "rows", rows,
                                        "sql", gov.approvedSql(), "execution_key", gov.executionKey()));
                                resultSnapshot = rJson;
                            } catch (Exception ex) {
                                queryExecutionRepository.updateStatus(gov.executionKey(), "FAILED",
                                        null, Instant.now(), ex.getMessage());
                                execResults.add(Map.of("step", stepNo, "error", ex.getMessage()));
                            }
                        }
                        stepNo++;
                    }

                    answer = composeAnswer(question, execResults, memChunks, semCtx, findings, anomalyCtx,
                            agent, "HYBRID_DOC_AND_DATA".equals(decisionType));
                    reasoningRepository.updateSessionStatus(sessionKey, "CONCLUDED", answer, 0.8, Instant.now());
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

            List<Map<String, Object>> quickRefs = buildQuickRefinements(decisionType, question);
            return buildResponse(conversationId, runKey, answer, decisionType,
                    agent, routingConfidence, "KNOWLEDGE_GAP".equals(decisionType), quickRefs, asyncOps);

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
            String resp = aiClient.chat(List.of(ChatMessage.user(prompt)),
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
            String ctx = buildContextSummary(memChunks, entCtx, semCtx, findings, anomalyCtx, hasPrior, history, agent);
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
                    1. Use ANSWER_FROM_PRIOR_RESULTS if conversation history exists AND the question is a follow-up —
                       this includes questions like "how?", "why?", "explain", "source?", "how did you get that?",
                       "are you sure?", "what data?", "break it down", or any question referencing a prior answer.
                    2. Use QUERY_LIVE_DATA if approved data sources exist and fresh live data is needed.
                    3. Use ANSWER_FROM_MEMORY if document memory can answer without live data.
                    4. Use HYBRID_DOC_AND_DATA for complex questions needing both memory and live data.
                    5. Use KNOWLEDGE_GAP if no knowledge or data sources are available at all.
                    6. Use ASK_CLARIFICATION ONLY if the question is completely ambiguous AND there is no prior conversation context.
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
            String ctx = buildContextSummary(memChunks, entCtx, semCtx, findings, anomalyCtx, false, history, agent);
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

    private String composeAnswer(String question, List<Map<String, Object>> execResults,
            List<DocumentChunk> memChunks, String semCtx, List<OperationalFinding> findings,
            String anomalyCtx, NexusAgent agent, boolean includeMemory) {
        try {
            StringBuilder ctx = new StringBuilder();
            ctx.append("Query results:\n");
            for (Map<String, Object> r : execResults) {
                if (r.containsKey("rows")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("rows");
                    ctx.append("Step ").append(r.get("step")).append(": ").append(rows.size()).append(" rows\n");
                    rows.stream().limit(10).forEach(row -> ctx.append("  ").append(row).append("\n"));
                } else if (r.containsKey("error")) {
                    ctx.append("Step ").append(r.get("step")).append(" error: ").append(r.get("error")).append("\n");
                } else if (r.containsKey("blocked")) {
                    ctx.append("Step ").append(r.get("step")).append(" blocked: ").append(r.get("reason")).append("\n");
                } else if (r.containsKey("needs_filter")) {
                    ctx.append("Step ").append(r.get("step")).append(" needs filter: ").append(r.get("reason")).append("\n");
                }
            }
            if (!findings.isEmpty()) {
                ctx.append("\nPrior Findings:\n");
                findings.forEach(f -> ctx.append("- ").append(f.title()).append(": ").append(f.description()).append("\n"));
            }
            if (!anomalyCtx.isBlank()) ctx.append("\n").append(anomalyCtx);
            if (includeMemory) {
                memChunks.stream().limit(3).forEach(c -> {
                    int len = Math.min(500, c.chunkText().length());
                    ctx.append("\nKnowledge: ").append(c.chunkText(), 0, len);
                });
            }
            String prompt = "Question: " + question + "\n\nInvestigation results:\n" + ctx;
            return aiClient.chat(List.of(ChatMessage.user(prompt)),
                    "You are SEI Nexus, an enterprise operational reasoning platform. " +
                    "Compose a clear, business-focused answer based on the investigation results. " +
                    "Highlight key findings, identify patterns, and suggest next steps if appropriate. " +
                    "Format the answer with markdown headings and bullet points for clarity.");
        } catch (Exception e) {
            return "Investigation completed. " + execResults.size() + " step(s) executed. " +
                    (execResults.stream().anyMatch(r -> r.containsKey("rows")) ? "Results available." : "No data returned.");
        }
    }

    // =========================================================================
    // Context building
    // =========================================================================

    private String buildContextSummary(List<DocumentChunk> memChunks, Map<String, Object> entCtx,
            String semCtx, List<OperationalFinding> findings,
            String anomalyCtx, boolean hasPrior, List<NexusRun> history, NexusAgent agent) {
        StringBuilder sb = new StringBuilder();

        if (agent != null) {
            sb.append("Agent: ").append(agent.name())
              .append(" | Domain: ").append(agent.domainKeys()).append("\n\n");
        }

        // ── Knowledge graph context (entities + JOIN paths) ───────────────────
        List<String> domainKeys = toDomainKeyList(agent);
        if (!domainKeys.isEmpty()) {
            String graphCtx = knowledgeGraphService.buildGraphContext(domainKeys);
            if (!graphCtx.isBlank()) {
                sb.append(graphCtx).append("\n");
            }
        }

        // ── Enterprise map entity context (columns, scan data) ────────────────
        if (entCtx.containsKey("entityContext")) {
            String ec = (String) entCtx.get("entityContext");
            if (ec != null && !ec.isBlank()) {
                sb.append("=== TABLE SCHEMA ===\n").append(ec).append("\n");
            }
        }

        // ── Supporting context ────────────────────────────────────────────────
        if (memChunks != null && !memChunks.isEmpty()) {
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
                "KNOWLEDGE_PROPOSAL", null, 1.0, false, List.of(), List.of());
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
                "SOURCE_REQUEST", null, 1.0, false, List.of(), List.of());
    }

    // =========================================================================
    // Response building
    // =========================================================================

    private ChatResponse buildResponse(String conversationId, String runKey, String answer,
            String decisionType, NexusAgent agent, double confidence, boolean needsKnowledge,
            List<Map<String, Object>> quickRefs, List<Map<String, Object>> asyncOps) {
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
                quickRefs, asyncOps);
    }

    // =========================================================================
    // Utility helpers
    // =========================================================================

    private List<String> toDomainKeyList(NexusAgent agent) {
        if (agent == null || agent.domainKeys() == null || agent.domainKeys().isBlank()) return List.of();
        return List.of(agent.domainKeys().split(",\\s*"));
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
