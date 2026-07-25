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
     * Runs the agent for one user message.
     * Creates a session record, executes the ReAct loop, persists the result.
     */
    public ZevraSession run(ZevraAgent agent, String inputMessage, String userEmail,
                            String existingRunKey) {
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

            // 4. Initialize conversation
            List<AgentMessage> messages = new ArrayList<>();
            messages.add(AgentMessage.user(inputMessage));

            // Cache of tool results for this session, keyed by tool + args.
            // If the LLM repeats an identical call (typically because pruning
            // dropped the earlier result from its context), we return the cached
            // result instead of re-executing — re-injecting it into the recent
            // window, which breaks repeat loops at their cause.
            Map<String, String> toolResultCache = new LinkedHashMap<>();

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
                    steps.add(step("FINAL_ANSWER",
                            Map.of("answer", answer),
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
                String cached   = toolResultCache.get(cacheKey);

                String toolResult;
                if (cached != null) {
                    toolResult = "NOTE: You already made this exact call — the result is " +
                            "repeated below. Do not run it again; use the data you have " +
                            "to progress toward final_answer.\n" + cached;
                } else {
                    toolResult = toolRegistry.execute(toolName, args,
                            agent.connectionKeys(), userEmail, governanceRunKey, iterations, contract);
                    toolResultCache.put(cacheKey, toolResult);
                }

                long callMs = System.currentTimeMillis() - callStart;
                steps.add(step("TOOL_CALL",
                        cached != null
                                ? Map.of("tool", toolName, "input", args, "cached", true)
                                : Map.of("tool", toolName, "input", args),
                        cached != null ? null : toolResult, callMs));

                // Append to conversation: assistant tool_call + tool result
                messages.add(AgentMessage.assistantToolCall(
                        response.toolCallId(), toolName, argsJson));
                messages.add(AgentMessage.toolResult(
                        response.toolCallId(), toolResult));
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
