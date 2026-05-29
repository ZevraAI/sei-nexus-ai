package com.sei.nexus.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AgentMessage;
import com.sei.nexus.ai.AgentToolResponse;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.sql.DynamicSqlService;
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

    private final AzureOpenAiClient  openAi;
    private final AgentToolRegistry  toolRegistry;
    private final ZevraAgentRepository repository;
    private final DynamicSqlService  dynamicSql;
    private final ObjectMapper       mapper;

    public AgentRunner(AzureOpenAiClient openAi,
                       AgentToolRegistry toolRegistry,
                       ZevraAgentRepository repository,
                       DynamicSqlService dynamicSql,
                       ObjectMapper mapper) {
        this.openAi     = openAi;
        this.toolRegistry = toolRegistry;
        this.repository  = repository;
        this.dynamicSql  = dynamicSql;
        this.mapper      = mapper;
    }

    /**
     * Runs the agent for one user message.
     * Creates a session record, executes the ReAct loop, persists the result.
     */
    public ZevraSession run(ZevraAgent agent, String inputMessage) {
        String sessionId = Keys.uniqueKey("ses");
        ZevraSession session = new ZevraSession(
                sessionId, agent.id(), agent.tenantSchema(),
                inputMessage, "RUNNING", "[]",
                null, null, 0, null, null);
        repository.insertSession(session);

        List<Map<String, Object>> steps = new ArrayList<>();

        try {
            // 1. Build schema context from all allowed connections
            long schemaStart = System.currentTimeMillis();
            String schemaContext = buildSchemaContext(agent.connectionKeys());
            steps.add(step("SCHEMA_LOAD", Map.of("connections", agent.connectionKeys()),
                    null, System.currentTimeMillis() - schemaStart));

            // 2. Build system prompt
            String systemPrompt = buildSystemPrompt(agent, schemaContext);

            // 3. Build tool definitions
            List<Map<String, Object>> tools =
                    toolRegistry.getToolDefinitions(agent.connectionKeys());

            // 4. Initialize conversation
            List<AgentMessage> messages = new ArrayList<>();
            messages.add(AgentMessage.user(inputMessage));

            // 5. ReAct loop
            int iterations = 0;
            while (iterations < agent.maxIterations()) {
                iterations++;
                long callStart = System.currentTimeMillis();

                AgentToolResponse response =
                        openAi.chatWithTools(messages, systemPrompt, tools);

                if (response.finalAnswer()) {
                    steps.add(step("FINAL_ANSWER",
                            Map.of("answer", response.answer()),
                            null, System.currentTimeMillis() - callStart));

                    String stepsJson = mapper.writeValueAsString(steps);
                    repository.completeSession(sessionId, "COMPLETED",
                            stepsJson, response.answer(), null, iterations);

                    return repository.findSessionById(sessionId, agent.tenantSchema())
                            .orElseThrow();
                }

                // LLM called a tool
                String toolName   = response.toolName();
                Map<String, Object> args = response.args();

                String toolResult = toolRegistry.execute(toolName, args,
                        agent.connectionKeys());

                long callMs = System.currentTimeMillis() - callStart;
                steps.add(step("TOOL_CALL",
                        Map.of("tool", toolName, "input", args),
                        toolResult, callMs));

                // Append to conversation: assistant tool_call + tool result
                String argsJson = mapper.writeValueAsString(args);
                messages.add(AgentMessage.assistantToolCall(
                        response.toolCallId(), toolName, argsJson));
                messages.add(AgentMessage.toolResult(
                        response.toolCallId(), toolResult));
            }

            // Max iterations reached
            String stepsJson = mapper.writeValueAsString(steps);
            repository.completeSession(sessionId, "MAX_ITER", stepsJson,
                    null, "Maximum iterations reached without a final answer", iterations);

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

    private String buildSchemaContext(List<String> connectionKeys) {
        StringBuilder sb = new StringBuilder();
        for (String connKey : connectionKeys) {
            sb.append("Connection: ").append(connKey).append("\n");
            try {
                List<Map<String, Object>> tables =
                        dynamicSql.listTablesWithColumnCounts(connKey, "public");
                for (Map<String, Object> row : tables) {
                    sb.append("  - ").append(row.get("table_name"))
                      .append(" (").append(row.get("column_names")).append(")\n");
                }
            } catch (Exception e) {
                sb.append("  (schema unavailable)\n");
            }
        }
        return sb.toString();
    }

    private String buildSystemPrompt(ZevraAgent agent, String schemaContext) {
        return agent.persona() + "\n\n" +
               "Goal: " + agent.goal() + "\n\n" +
               "Available data connections and schemas:\n" +
               schemaContext + "\n" +
               "CRITICAL RULES — you must follow these without exception:\n" +
               "1. ALWAYS query the database for every specific ID, order, entity, or number " +
               "mentioned in the user's request before forming any answer. " +
               "Never answer from memory, assumptions, or training knowledge.\n" +
               "2. Query ALL relevant tables — if an order is mentioned, check the orders table, " +
               "the shipments table, any claims table, any status history table. " +
               "Be thorough; one query is almost never enough.\n" +
               "3. If a query returns 0 rows, try alternative column names or related tables " +
               "before concluding the record does not exist.\n" +
               "4. Only call final_answer once you have retrieved actual data from the database.\n" +
               "5. Use exact table and column names from the schema above.\n" +
               "6. Only use SELECT statements — never modify data.\n\n" +
               "Response format:\n" +
               "- Lead with the key facts (status, dates, amounts, issues found).\n" +
               "- Cite the actual values you retrieved — order ID, dates, amounts, statuses.\n" +
               "- Be direct and factual. Do NOT end with generic offers like " +
               "'Let me know if you need anything else'. End when the analysis is complete.\n" +
               "- Write for a business user reading an operational report, not a chat assistant.";
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
