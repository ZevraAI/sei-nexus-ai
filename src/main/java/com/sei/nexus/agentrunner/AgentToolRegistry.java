package com.sei.nexus.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.runtime.GovernedSqlRuntime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Registry of generic tools available to every Zevra Agent.
 * Tools are industry-agnostic — the LLM's reasoning supplies domain intelligence.
 */
@Service
public class AgentToolRegistry {

    private final AzureOpenAiClient   openAi;
    private final ObjectMapper        mapper;
    private final GovernedSqlRuntime  runtime;

    public AgentToolRegistry(AzureOpenAiClient openAi,
                              ObjectMapper mapper,
                              GovernedSqlRuntime runtime) {
        this.openAi  = openAi;
        this.mapper  = mapper;
        this.runtime = runtime;
    }

    // ── Tool definitions sent to the LLM ─────────────────────────────────────

    public List<Map<String, Object>> getToolDefinitions(List<String> allowedConnections) {
        String connList = String.join(", ", allowedConnections);
        return List.of(
                tool("query_database",
                        "Execute a SQL SELECT query against a connected database to retrieve business data. " +
                        "Use this to fetch actual records — orders, shipments, claims, statuses, etc. " +
                        "Always query for specific IDs or entities the user mentioned. " +
                        "Allowed connections: " + connList,
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "connection_key", Map.of("type", "string",
                                                "description", "The connection key to query. Must be one of: " + connList),
                                        "sql", Map.of("type", "string",
                                                "description", "A read-only SELECT SQL statement. Use exact table and column names from describe_schema.")
                                ),
                                "required", List.of("connection_key", "sql")
                        )),
                tool("describe_schema",
                        "Get detailed table and column definitions for a connection. " +
                        "Use only when you need specific column details not visible in your schema context. " +
                        "Do not call this if the schema context already shows the columns you need — go straight to query_database.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "connection_key", Map.of("type", "string",
                                                "description", "The connection key to inspect. Must be one of: " + connList)
                                ),
                                "required", List.of("connection_key")
                        )),
                tool("analyze_image",
                        "Analyze a base64-encoded image and answer a question about its visual content.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "image_base64", Map.of("type", "string",
                                                "description", "Base64-encoded image data (no data URI prefix)"),
                                        "question", Map.of("type", "string",
                                                "description", "What to assess or describe in the image"),
                                        "mime_type", Map.of("type", "string",
                                                "description", "Image MIME type, e.g. image/jpeg", "default", "image/jpeg")
                                ),
                                "required", List.of("image_base64", "question")
                        )),
                tool("final_answer",
                        "Provide the complete final response once you have gathered sufficient information. " +
                        "Call this when you have enough data to answer the user's request.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "answer", Map.of("type", "string",
                                                "description", "The complete response, analysis, or recommendation")
                                ),
                                "required", List.of("answer")
                        ))
        );
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> parameters) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", parameters
                )
        );
    }

    // ── Tool execution ────────────────────────────────────────────────────────

    /**
     * Executes a tool call from the LLM and returns the result as a JSON string.
     * Security: validates connection_key is within the agent's allowed list.
     */
    public String execute(String toolName, Map<String, Object> args,
                           List<String> allowedConnections,
                           String userEmail, String runKey, int stepNo,
                           ExecutionContract contract,
                           String conversationId, String parentExecutionId) {
        try {
            return switch (toolName) {
                case "query_database"  -> execQueryDatabase(args, allowedConnections, userEmail, runKey, stepNo,
                        contract, conversationId, parentExecutionId);
                case "describe_schema" -> execDescribeSchema(args, allowedConnections, contract);
                case "analyze_image"   -> execAnalyzeImage(args);
                case "final_answer"    -> String.valueOf(args.get("answer"));
                default -> throw new NexusException(HttpStatus.BAD_REQUEST,
                        "Unknown tool: " + toolName);
            };
        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private String execQueryDatabase(Map<String, Object> args,
                                      List<String> allowedConnections,
                                      String userEmail, String runKey, int stepNo,
                                      ExecutionContract contract,
                                      String conversationId, String parentExecutionId) throws Exception {
        String connKey = getString(args, "connection_key");
        String sql     = getString(args, "sql");

        if (!allowedConnections.contains(connKey))
            throw new NexusException(HttpStatus.FORBIDDEN,
                    "Agent is not permitted to access connection: " + connKey);

        // Deterministic execution (Unified Answer Engine, Phase 1): the shared
        // GovernedSqlRuntime owns the business-object gate (ADR-0003 A14), the governance
        // chain (ADR-0003 A2, SqlGovernancePipeline unchanged), read-only execution, and
        // audit. This registry keeps only the agent-facing presentation: each deterministic
        // verdict becomes a physical tool observation the ReAct loop can re-plan against;
        // the business explanation is the model's final_answer, not the runtime's.
        GovernedSqlRuntime.Outcome outcome = runtime.execute(
                GovernedSqlRuntime.Request.forAgent(runKey, stepNo, connKey, sql, userEmail, contract,
                        conversationId, parentExecutionId));

        return switch (outcome.status()) {
            case UNAPPROVED_OBJECTS -> mapper.writeValueAsString(Map.of(
                    "error", "Query references table(s) not in the approved execution contract for connection '"
                            + connKey + "': " + outcome.unapprovedTables() + ". Approved physical tables: ["
                            + outcome.approvedTables() + "]. Use an approved business object, or state via "
                            + "final_answer that the requested data is not available."));

            case GOVERNANCE_BLOCKED, CONTRACT_BLOCKED -> mapper.writeValueAsString(Map.of(
                    "error", "Query rejected: " + outcome.message()
                            + ". Only read-only SELECT queries within your governed scope are permitted — revise the query."));

            // The agent loop is synchronous; surface a narrow-the-query observation.
            case ASYNC -> mapper.writeValueAsString(Map.of(
                    "error", "Query too large to run inline (" + outcome.governance().classification()
                            + "). Add a filter or narrow the range, then try again."));

            // Preserves the prior behaviour: the failure propagates to execute()'s handler,
            // which renders it as {"error": ...} for the model.
            case FAILED -> throw outcome.failure();

            case EXECUTED -> outcome.rowsJson();

            // Literal validation is not engaged on the agent path (no domain scope today).
            default -> mapper.writeValueAsString(Map.of("error", String.valueOf(outcome.message())));
        };
    }

    private String execDescribeSchema(Map<String, Object> args,
                                       List<String> allowedConnections,
                                       ExecutionContract contract) throws Exception {
        String connKey = getString(args, "connection_key");

        if (!allowedConnections.contains(connKey))
            throw new NexusException(HttpStatus.FORBIDDEN,
                    "Agent is not permitted to access connection: " + connKey);

        // Scoped to the approved ExecutionContract (ADR-0003 A14) — the runtime never
        // exposes the raw information_schema catalog to the model; it returns only the
        // business objects Agent Brain approved for this connection.
        List<Map<String, Object>> objects = contract.executionBindings().objectBindings().values().stream()
                .filter(t -> connKey.equals(t.connectionKey()))
                .map(t -> Map.<String, Object>of(
                        "table",  t.table(),
                        "schema", t.schema() == null ? "" : t.schema()))
                .toList();
        return mapper.writeValueAsString(objects);
    }

    private String execAnalyzeImage(Map<String, Object> args) {
        String imageBase64 = getString(args, "image_base64");
        String question    = getString(args, "question");
        String mimeType    = args.getOrDefault("mime_type", "image/jpeg").toString();
        return openAi.analyzeImage(question, imageBase64, mimeType,
                "You are a visual inspection AI. Analyse the image accurately.");
    }

    private String getString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null)
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Missing required argument: " + key);
        return val.toString();
    }
}
