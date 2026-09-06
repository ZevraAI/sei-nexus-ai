package com.sei.nexus.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.AgentBrain;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.ExecutionContractBuilder;
import com.sei.nexus.agentbrain.PromptAssembler;
import com.sei.nexus.agentbrain.PromptContext;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.ResolvedBusinessModel;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.ai.AgentMessage;
import com.sei.nexus.ai.AgentToolResponse;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.run.NexusRun;
import com.sei.nexus.run.RunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ReAct loop: given an agent and a user message, the LLM iteratively
 * calls tools (query_database, describe_schema, analyze_image) until it
 * has enough information to call final_answer.
 */
@Service
public class AgentRunner {

    private final AzureOpenAiClient        openAi;
    private final AgentToolRegistry        toolRegistry;
    private final ZevraAgentRepository     repository;
    private final ObjectMapper             mapper;
    private final RunRepository            runRepository;
    private final AgentBrain               agentBrain;
    private final ExecutionContractBuilder contractBuilder;
    private final PromptContextBuilder     promptContextBuilder;
    private final PromptAssembler          promptAssembler;

    public AgentRunner(AzureOpenAiClient openAi,
                       AgentToolRegistry toolRegistry,
                       ZevraAgentRepository repository,
                       ObjectMapper mapper,
                       RunRepository runRepository,
                       AgentBrain agentBrain,
                       ExecutionContractBuilder contractBuilder,
                       PromptContextBuilder promptContextBuilder,
                       PromptAssembler promptAssembler) {
        this.openAi               = openAi;
        this.toolRegistry         = toolRegistry;
        this.repository           = repository;
        this.mapper               = mapper;
        this.runRepository        = runRepository;
        this.agentBrain           = agentBrain;
        this.contractBuilder      = contractBuilder;
        this.promptContextBuilder = promptContextBuilder;
        this.promptAssembler      = promptAssembler;
    }

    /**
     * Runs the agent for one user message with no prior conversation context (autonomous path —
     * Executive Brief, direct agent chat).
     */
    public ZevraSession run(ZevraAgent agent, String inputMessage, String userEmail,
                            String existingRunKey) {
        return run(agent, inputMessage, userEmail, existingRunKey, null, null, null);
    }

    /**
     * Runs the agent for one user message.
     * Creates a session record, executes the ReAct loop, persists the result.
     *
     * @param conversationContext prior-execution grounding (facts from the previous
     *        {@code ExecutionReference}) so follow-ups continue the same execution; blank for a
     *        fresh conversation.
     * @param conversationId     carried so each execution's {@code ExecutionReference} records it.
     * @param parentExecutionId  the previous execution this turn continues from (AgentBrain's
     *        lineage decision); recorded verbatim by Runtime. Nullable.
     */
    public ZevraSession run(ZevraAgent agent, String inputMessage, String userEmail,
                            String existingRunKey, String conversationContext,
                            String conversationId, String parentExecutionId) {
        String sessionId = Keys.uniqueKey("ses");
        ZevraSession session = new ZevraSession(
                sessionId, agent.id(), agent.tenantSchema(),
                inputMessage, "RUNNING", "[]",
                null, null, 0, null, null);
        repository.insertSession(session);

        // Governance run (ADR-0003 A2): the parent nexus_run for this session's governed
        // query executions and audit events. For AUTONOMOUS execution (direct agent chat,
        // Executive Brief) the runtime creates its own run. When invoked from the
        // conversational path (routed chat), the caller already created the request's run
        // and passes its key — reuse it so routed chat produces exactly one nexus_run and
        // never a duplicate insert.
        String governanceRunKey;
        if (existingRunKey != null && !existingRunKey.isBlank()) {
            governanceRunKey = existingRunKey;
        } else {
            governanceRunKey = Keys.runKey();
            runRepository.save(new NexusRun(governanceRunKey, sessionId, agent.slug(), null,
                    userEmail, inputMessage, null, null, "RUNNING", null, null, null));
        }

        List<Map<String, Object>> steps = new ArrayList<>();

        try {
            // 1. Business-object resolution + grounding (ADR-0003 A11/A12). Agent Brain
            //    resolves the request against the Enterprise Map and produces a
            //    ResolvedBusinessModel; the deterministic builder compiles the immutable
            //    ExecutionContract; the prompt pipeline grounds the model in approved
            //    business objects only (no information_schema). The contract is the agent's
            //    execution surface for the whole run and drives runtime enforcement below.
            long resolveStart = System.currentTimeMillis();
            ResolvedBusinessModel businessModel = agentBrain.resolve(agent, inputMessage);
            ExecutionContract contract = contractBuilder.compile(businessModel);
            PromptContext promptContext = promptContextBuilder.build(contract);
            String grounding = promptAssembler.assemble(promptContext);

            steps.add(step("CONTEXT_RESOLVE", Map.of(
                    "contractId",      contract.contractId(),
                    "connections",     agent.connectionKeys(),
                    "businessObjects", contract.semanticView().businessObjects().stream()
                            .map(BusinessObject::businessName).toList()),
                    null, System.currentTimeMillis() - resolveStart));

            // 2. Build system prompt (grounded only in approved business objects).
            String systemPrompt = buildSystemPrompt(agent, grounding);

            // 3. Build tool definitions
            List<Map<String, Object>> tools =
                    toolRegistry.getToolDefinitions(agent.connectionKeys());

            // 4. Initialize conversation. A follow-up prepends recent turns to the first user
            //    message so the model can resolve referents introduced in an earlier answer
            //    (e.g. "that region"). Kept in messages[0] so pruneHistory (which always keeps
            //    messages[0]) never drops the question or its context mid-loop.
            List<AgentMessage> messages = new ArrayList<>();
            messages.add(AgentMessage.user(composeFirstMessage(conversationContext, inputMessage)));

            // Cache of tool results for this session, keyed by tool + args.
            // If the LLM repeats an identical call (typically because pruning
            // dropped the earlier result from its context), we return the cached
            // result instead of re-executing — re-injecting it into the recent
            // window, which breaks repeat loops at their cause.
            Map<String, AgentToolRegistry.ToolExecutionResult> toolResultCache = new LinkedHashMap<>();

            // 5. ReAct loop — set usage context so every LLM call is attributed
            com.sei.nexus.usage.UsageContext.set("agent", userEmail, agent.name());
            int iterations = 0;
            while (iterations < agent.maxIterations()) {
                iterations++;
                long callStart = System.currentTimeMillis();

                // Prune history to keep only the user message + last 2 tool-call pairs.
                // Without pruning the prompt grows with every iteration: by step 4 the LLM
                // re-reads all previous queries and results, doubling token cost each time.
                AgentToolResponse response =
                        openAi.chatWithTools(pruneHistory(messages, HISTORY_KEEP_PAIRS),
                                systemPrompt, tools);

                String answer = extractFinalAnswer(response);
                if (answer != null) {
                    // The model's own semantic decomposition of `answer`, when it called
                    // final_answer with the optional fields (see AgentToolRegistry's schema) —
                    // never fabricated by the runtime; empty when the model didn't provide them
                    // (e.g. a plain-text termination, which carries no tool-call args at all).
                    Map<String, Object> stepArgs = new LinkedHashMap<>();
                    stepArgs.put("answer", answer);
                    stepArgs.putAll(extractFinalSemantics(response));
                    steps.add(step("FINAL_ANSWER",
                            stepArgs,
                            null, System.currentTimeMillis() - callStart));

                    String stepsJson = mapper.writeValueAsString(steps);
                    repository.completeSession(sessionId, "COMPLETED",
                            stepsJson, answer, null, iterations);

                    return repository.findSessionById(sessionId, agent.tenantSchema())
                            .orElseThrow();
                }

                // LLM called a tool
                String toolName   = response.toolName();
                Map<String, Object> args = response.args();
                String argsJson   = mapper.writeValueAsString(args);

                String cacheKey = toolName + ":" + argsJson;
                AgentToolRegistry.ToolExecutionResult cached = toolResultCache.get(cacheKey);

                AgentToolRegistry.ToolExecutionResult result;
                String llmContent;
                if (cached != null) {
                    result = cached;
                    llmContent = "NOTE: You already made this exact call — the result is " +
                            "repeated below. Do not run it again; use the data you have " +
                            "to progress toward final_answer.\n" + withExecutionIdNote(cached);
                } else {
                    result = toolRegistry.executeWithReference(toolName, args,
                            agent.connectionKeys(), userEmail, governanceRunKey, iterations, contract,
                            conversationId, parentExecutionId);
                    toolResultCache.put(cacheKey, result);
                    llmContent = withExecutionIdNote(result);
                }

                long callMs = System.currentTimeMillis() - callStart;
                steps.add(step("TOOL_CALL",
                        cached != null
                                ? Map.of("tool", toolName, "input", args, "cached", true)
                                : Map.of("tool", toolName, "input", args),
                        cached != null ? null : result.content(), callMs));

                // Append to conversation: assistant tool_call + tool result. The persisted step
                // output above is always result.content() unchanged (so ChatService's
                // extractAgentQueryRows keeps parsing query_database's rows as a List exactly as
                // before); the execution_id — when present — is appended only to the message the
                // LLM itself sees, via withExecutionIdNote, never merged into content's shape.
                messages.add(AgentMessage.assistantToolCall(
                        response.toolCallId(), toolName, argsJson));
                messages.add(AgentMessage.toolResult(
                        response.toolCallId(), llmContent));
            }

            // Max iterations reached — force one last best-effort answer from the
            // information already gathered, with no further tool use allowed.
            long salvageStart = System.currentTimeMillis();
            String salvaged = salvageAnswer(messages, systemPrompt, tools);

            String stepsJson;
            if (salvaged != null) {
                steps.add(step("FINAL_ANSWER",
                        Map.of("answer", salvaged, "forcedAtMaxIterations", true),
                        null, System.currentTimeMillis() - salvageStart));
                stepsJson = mapper.writeValueAsString(steps);
                repository.completeSession(sessionId, "COMPLETED", stepsJson,
                        salvaged, null, iterations);
            } else {
                stepsJson = mapper.writeValueAsString(steps);
                repository.completeSession(sessionId, "MAX_ITER", stepsJson,
                        "I couldn't complete this request within the allowed number of steps ("
                                + agent.maxIterations() + "). Try narrowing the question, "
                                + "or increase this agent's max iterations.",
                        "Maximum iterations reached without a final answer", iterations);
            }

        } catch (Exception e) {
            try {
                String stepsJson = mapper.writeValueAsString(steps);
                repository.completeSession(sessionId, "FAILED", stepsJson,
                        null, e.getMessage(), 0);
            } catch (Exception ignored) {}
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Agent execution failed: " + e.getMessage());
        }

        return repository.findSessionById(sessionId, agent.tenantSchema()).orElseThrow();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the final answer text if this LLM response is terminal, else null.
     * Terminal means either plain text (no tool call) or a call to the
     * {@code final_answer} tool — both end the ReAct loop.
     */
    private String extractFinalAnswer(AgentToolResponse response) {
        if (response.finalAnswer()) {
            return response.answer() != null ? response.answer() : "";
        }
        if ("final_answer".equals(response.toolName())) {
            Object answer = response.args() != null ? response.args().get("answer") : null;
            return answer != null ? answer.toString() : "";
        }
        return null;
    }

    /**
     * The model's own semantic decomposition, read straight from the {@code final_answer}
     * tool-call arguments (see AgentToolRegistry's schema) — only present when the model actually
     * called the tool with those optional fields populated; empty for a plain-text termination
     * (no tool-call args exist in that branch) or when the model chose not to populate them.
     * Never computed/inferred here — this is a read, not a derivation.
     */
    private Map<String, Object> extractFinalSemantics(AgentToolResponse response) {
        if (response.args() == null) return Map.of();
        Map<String, Object> semantics = new LinkedHashMap<>();
        Object understanding = response.args().get("understanding");
        if (understanding instanceof String s && !s.isBlank()) semantics.put("understanding", s);
        Object keyFindings = response.args().get("key_findings");
        if (keyFindings instanceof List<?> l && !l.isEmpty()) semantics.put("key_findings", keyFindings);
        Object relatedFacts = response.args().get("related_facts");
        if (relatedFacts instanceof List<?> l && !l.isEmpty()) semantics.put("related_facts", relatedFacts);
        Object recommendation = response.args().get("recommendation");
        if (recommendation instanceof String s && !s.isBlank()) semantics.put("recommendation", s);
        Object nextSteps = response.args().get("next_steps");
        if (nextSteps instanceof List<?> l && !l.isEmpty()) semantics.put("next_steps", nextSteps);
        return semantics;
    }

    /**
     * Called when the iteration budget is exhausted: makes one final LLM call
     * instructing the model to answer from what it has already gathered.
     * Returns the answer, or null if the model still didn't produce one.
     */
    private String salvageAnswer(List<AgentMessage> messages, String systemPrompt,
                                  List<Map<String, Object>> tools) {
        try {
            // Prune first, then append — appending before pruning could split an
            // assistant tool_call from its tool result, which the API rejects.
            List<AgentMessage> salvageMessages =
                    new ArrayList<>(pruneHistory(messages, HISTORY_KEEP_PAIRS));
            salvageMessages.add(AgentMessage.user(
                    "You have reached the maximum number of steps. Do not call any more tools. " +
                    "Give your best final answer now using only the information already gathered, " +
                    "and state clearly anything you could not verify."));

            AgentToolResponse response =
                    openAi.chatWithTools(salvageMessages, systemPrompt, tools);
            String answer = extractFinalAnswer(response);
            return (answer != null && !answer.isBlank()) ? answer : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Tool-call/result pairs kept in the LLM's context window. Must comfortably
    // exceed the number of distinct queries a typical task needs at once —
    // a window smaller than the task's working set makes the model re-run
    // queries whose results were pruned, looping until max iterations.
    private static final int HISTORY_KEEP_PAIRS = 6;

    /**
     * Keeps the original user message plus the last {@code keepPairs} tool-call/result
     * pairs, dropping older pairs. This caps the prompt size so it doesn't grow
     * linearly with each ReAct iteration.
     *
     * <p>OpenAI requires every assistant tool_call message to be immediately followed
     * by its tool_result — so pairs are never split.
     */
    /**
     * Composes the agent's first user message. On a follow-up, recent conversation turns are
     * prepended so the model can resolve referents introduced in an earlier answer (e.g. "that
     * region"). Kept in a single message so {@code pruneHistory} (which always keeps messages[0])
     * never drops the question or its context mid-loop.
     */
    /**
     * The LLM-facing decoration of a tool result: {@code content} unchanged, plus a trailing
     * execution_id note when one is present (query_database EXECUTED only). This is the ONLY
     * place execution_id reaches the LLM — never merged into content's own shape, so the
     * persisted step output and any downstream parser of it (ChatService.extractAgentQueryRows)
     * see exactly the same bare rows JSON as before this phase.
     */
    static String withExecutionIdNote(AgentToolRegistry.ToolExecutionResult result) {
        if (result.executionId() == null) return result.content();
        return result.content() + "\n\n[execution_id: " + result.executionId()
                + " — use memory_get_execution_reference to recall this exact result on a later turn]";
    }

    static String composeFirstMessage(String conversationContext, String inputMessage) {
        if (conversationContext == null || conversationContext.isBlank()) return inputMessage;
        return conversationContext + "\n\nCurrent question: " + inputMessage;
    }

    private List<AgentMessage> pruneHistory(List<AgentMessage> messages, int keepPairs) {
        if (messages.size() <= 1) return messages;

        // messages[0] is always the user question — always keep it.
        List<AgentMessage> tail = messages.subList(1, messages.size());

        // Each tool-call round-trip = 2 messages (assistant tool_call + tool result).
        int keep = keepPairs * 2;
        if (tail.size() > keep) {
            tail = tail.subList(tail.size() - keep, tail.size());
        }

        List<AgentMessage> pruned = new ArrayList<>();
        pruned.add(messages.get(0));
        pruned.addAll(tail);
        return pruned;
    }

    /**
     * Builds the system prompt from persona/goal and the Agent-Brain-approved grounding.
     * The grounding is authored by the prompt pipeline from the ExecutionContract's
     * SemanticView; the runtime performs no business grounding of its own and never reads
     * information_schema (ADR-0003 A12).
     */
    static String buildSystemPrompt(ZevraAgent agent, String grounding) {
        StringBuilder sb = new StringBuilder();
        sb.append(agent.persona()).append(' ').append(agent.goal()).append("\n\n");
        sb.append(grounding).append('\n');
        sb.append("CRITICAL: You may ONLY query the business objects listed above, using their exact "
                + "physical table names. Do NOT invent tables or business objects. If the data the user "
                + "asks about is not among these business objects, say so plainly via final_answer instead "
                + "of guessing. SELECT only. Query data, then call final_answer with findings. Be factual.");
        return sb.toString();
    }

    private Map<String, Object> step(String type, Map<String, Object> input,
                                      String outputJson, long durationMs) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        if (input != null) s.putAll(input);
        if (outputJson != null) {
            try {
                s.put("output", mapper.readValue(outputJson, Object.class));
            } catch (Exception e) {
                s.put("output", outputJson);
            }
        }
        s.put("durationMs", durationMs);
        return s;
    }
}
