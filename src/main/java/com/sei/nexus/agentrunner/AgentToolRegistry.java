package com.sei.nexus.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentmemory.BusinessWorldToolAdapter;
import com.sei.nexus.agentmemory.ConversationMemoryService;
import com.sei.nexus.agentmemory.ConversationRosterEntry;
import com.sei.nexus.agentmemory.RelationshipExplorationAdapter;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.graph.GraphEdge;
import com.sei.nexus.runtime.ExecutionReference;
import com.sei.nexus.runtime.ExecutionReferenceRepository;
import com.sei.nexus.runtime.GovernedSqlRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of generic tools available to every Zevra Agent.
 * Tools are industry-agnostic — the LLM's reasoning supplies domain intelligence.
 *
 * <p><b>Conversation Memory + Business World capabilities (Phase 2).</b> Six additional tools —
 * {@code memory_list}, {@code memory_get}, {@code memory_get_execution_reference},
 * {@code get_business_object}, {@code list_business_objects}, {@code explore_related} — are
 * registered and dispatched through this exact same mechanism as the original four tools. They
 * are ordinary Agent tools, not a second execution path: every one is a deterministic, exact-key
 * lookup or an idempotent mechanical registration. None of them inspect the user's question, rank
 * entities, or perform relevance selection — the LLM decides what to call and when.
 *
 * <p>Gated behind {@code zevra.agent.memory-tools.enabled} (default {@code false}). When disabled,
 * the six tools are absent from {@link #getToolDefinitions} (the LLM never sees them) and
 * {@link #execute}/{@link #executeWithReference} reject their names exactly like any unknown tool
 * — a config-only, instant rollback with no change to the original four tools' behavior.
 */
@Service
public class AgentToolRegistry {

    private final AzureOpenAiClient   openAi;
    private final ObjectMapper        mapper;
    private final GovernedSqlRuntime  runtime;

    // Conversation Memory + Business World collaborators (Phase 2). Null when this registry is
    // constructed via the original 3-arg constructor (preserved for backward compatibility —
    // see AgentToolRegistryTest); harmless because memoryCapabilitiesEnabled is false in that case
    // and the six new tool names are rejected before any of these fields are touched.
    private final ConversationMemoryService        memoryService;
    private final BusinessWorldToolAdapter         businessWorldAdapter;
    private final RelationshipExplorationAdapter   relationshipAdapter;
    private final ExecutionReferenceRepository     executionReferenceRepository;

    /**
     * Feature flag. {@code false} (default) reproduces exactly today's Agent behavior — the six
     * new capabilities are invisible and unreachable. {@code true} exposes them additively. An
     * instant, config-only rollback (same pattern as {@code LlmExecutionStrategySelector}).
     */
    private final boolean memoryCapabilitiesEnabled;

    /** Preserved verbatim for backward compatibility (AgentToolRegistryTest and any other caller
     *  that only wires the original three collaborators). The six new capabilities are disabled. */
    public AgentToolRegistry(AzureOpenAiClient openAi,
                              ObjectMapper mapper,
                              GovernedSqlRuntime runtime) {
        this(openAi, mapper, runtime, null, null, null, null, false);
    }

    @Autowired
    public AgentToolRegistry(AzureOpenAiClient openAi,
                              ObjectMapper mapper,
                              GovernedSqlRuntime runtime,
                              ConversationMemoryService memoryService,
                              BusinessWorldToolAdapter businessWorldAdapter,
                              RelationshipExplorationAdapter relationshipAdapter,
                              ExecutionReferenceRepository executionReferenceRepository,
                              @Value("${zevra.agent.memory-tools.enabled:false}") boolean memoryCapabilitiesEnabled) {
        this.openAi                        = openAi;
        this.mapper                        = mapper;
        this.runtime                       = runtime;
        this.memoryService                 = memoryService;
        this.businessWorldAdapter          = businessWorldAdapter;
        this.relationshipAdapter           = relationshipAdapter;
        this.executionReferenceRepository  = executionReferenceRepository;
        this.memoryCapabilitiesEnabled     = memoryCapabilitiesEnabled;
    }

    // ── Tool definitions sent to the LLM ─────────────────────────────────────

    public List<Map<String, Object>> getToolDefinitions(List<String> allowedConnections) {
        String connList = String.join(", ", allowedConnections);
        List<Map<String, Object>> tools = new java.util.ArrayList<>(List.of(
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
        ));

        if (memoryCapabilitiesEnabled) {
            tools.addAll(memoryAndBusinessWorldToolDefinitions());
        }
        return List.copyOf(tools);
    }

    private List<Map<String, Object>> memoryAndBusinessWorldToolDefinitions() {
        return List.of(
                tool("memory_list",
                        "List the Business Objects already discovered in this conversation (their reference "
                        + "keys only — no physical metadata). Call this when you want to know what you already "
                        + "know before deciding whether to look something up. Takes no arguments.",
                        Map.of("type", "object", "properties", Map.of(), "required", List.of())),
                tool("memory_get",
                        "Retrieve the current authoritative Business Object for an entity_key ONLY IF it was "
                        + "already discovered in this conversation (check memory_list first, or just try — an "
                        + "entity not yet discovered returns a deterministic not-found result, never a guess). "
                        + "Use get_business_object instead to discover a new entity for the first time.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("entity_key", Map.of("type", "string",
                                        "description", "The exact entity_key to retrieve, as returned by memory_list, "
                                                + "get_business_object, list_business_objects, or explore_related.")),
                                "required", List.of("entity_key")
                        )),
                tool("memory_get_execution_reference",
                        "Retrieve the exact facts of a previous successful query_database execution in this "
                        + "conversation, by the execution_id shown in that earlier tool result (e.g. to compare "
                        + "a new result against an earlier one). Exact lookup only — supply the exact ID.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("reference_id", Map.of("type", "string",
                                        "description", "The execution_id from an earlier query_database tool result in this conversation.")),
                                "required", List.of("reference_id")
                        )),
                tool("get_business_object",
                        "Authoritatively retrieve one Business Object by its exact entity_key from the tenant's "
                        + "governed Business World. Use this to discover an object you don't already have (check "
                        + "memory_list / memory_get first if you think you already retrieved it). A successful "
                        + "retrieval is automatically remembered for the rest of this conversation.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("entity_key", Map.of("type", "string",
                                        "description", "The exact entity_key of the Business Object to retrieve.")),
                                "required", List.of("entity_key")
                        )),
                tool("list_business_objects",
                        "List the Business Objects belonging to an exact business domain/group in the tenant's "
                        + "governed Business World — reference keys and names only, not full metadata. This is a "
                        + "listing, not a discovery: none of the returned objects become conversation memory "
                        + "until you explicitly retrieve one with get_business_object.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("group", Map.of("type", "string",
                                        "description", "The exact domain/group key to list Business Objects for.")),
                                "required", List.of("group")
                        )),
                tool("explore_related",
                        "Show the authoritative relationships (if any) for a Business Object — what it connects "
                        + "to, the relationship type, the join columns, and cardinality. Returns every known "
                        + "relationship unfiltered; you decide which one is relevant. This does not retrieve or "
                        + "remember the related objects themselves — call get_business_object for any you need.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("entity_key", Map.of("type", "string",
                                        "description", "The exact entity_key to find relationships for.")),
                                "required", List.of("entity_key")
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
     * The result of one tool execution: {@code content} is exactly the JSON string a caller of the
     * original {@link #execute} would have received (unchanged shape — in particular,
     * {@code query_database}'s successful result stays a bare JSON array of rows, so
     * {@code ChatService.extractAgentQueryRows} keeps working unmodified). {@code executionId} is
     * populated ONLY for a successful {@code query_database} execution and is auxiliary — it rides
     * alongside {@code content} rather than inside it, so it can be surfaced to the LLM without
     * changing the persisted/parsed shape of {@code content} itself.
     */
    public record ToolExecutionResult(String content, String executionId) {}

    /**
     * Executes a tool call from the LLM and returns the result as a JSON string.
     * Security: validates connection_key is within the agent's allowed list.
     *
     * <p>Unchanged behavior and unchanged signature — existing callers/tests are unaffected. This
     * simply returns {@link ToolExecutionResult#content} of {@link #executeWithReference}.
     */
    public String execute(String toolName, Map<String, Object> args,
                           List<String> allowedConnections,
                           String userEmail, String runKey, int stepNo,
                           ExecutionContract contract,
                           String conversationId, String parentExecutionId) {
        return executeWithReference(toolName, args, allowedConnections, userEmail, runKey, stepNo,
                contract, conversationId, parentExecutionId).content();
    }

    /**
     * Same dispatch as {@link #execute}, but also returns the execution_id of a successful
     * {@code query_database} call (null for every other tool, and null on any non-EXECUTED
     * outcome) — the additive mechanism identified during design review for exposing execution
     * references to the LLM without changing {@code query_database}'s persisted result shape.
     */
    public ToolExecutionResult executeWithReference(String toolName, Map<String, Object> args,
                           List<String> allowedConnections,
                           String userEmail, String runKey, int stepNo,
                           ExecutionContract contract,
                           String conversationId, String parentExecutionId) {
        try {
            return switch (toolName) {
                case "query_database"  -> execQueryDatabase(args, allowedConnections, userEmail, runKey, stepNo,
                        contract, conversationId, parentExecutionId);
                case "describe_schema" -> plain(execDescribeSchema(args, allowedConnections, contract));
                case "analyze_image"   -> plain(execAnalyzeImage(args));
                case "final_answer"    -> plain(String.valueOf(args.get("answer")));

                case "memory_list"                     -> requireEnabled(toolName, () -> plain(execMemoryList(conversationId)));
                case "memory_get"                       -> requireEnabled(toolName, () -> plain(execMemoryGet(args, conversationId)));
                case "memory_get_execution_reference"   -> requireEnabled(toolName, () -> plain(execMemoryGetExecutionReference(args, conversationId)));
                case "get_business_object"              -> requireEnabled(toolName, () -> plain(execGetBusinessObject(args, conversationId)));
                case "list_business_objects"            -> requireEnabled(toolName, () -> plain(execListBusinessObjects(args)));
                case "explore_related"                  -> requireEnabled(toolName, () -> plain(execExploreRelated(args)));

                default -> throw new NexusException(HttpStatus.BAD_REQUEST,
                        "Unknown tool: " + toolName);
            };
        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            return plain("{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    private static ToolExecutionResult plain(String content) {
        return new ToolExecutionResult(content, null);
    }

    private interface ThrowingSupplier { ToolExecutionResult get() throws Exception; }

    private ToolExecutionResult requireEnabled(String toolName, ThrowingSupplier body) throws Exception {
        if (!memoryCapabilitiesEnabled) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Unknown tool: " + toolName);
        }
        return body.get();
    }

    private ToolExecutionResult execQueryDatabase(Map<String, Object> args,
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
            case UNAPPROVED_OBJECTS -> plain(mapper.writeValueAsString(Map.of(
                    "error", "Query references table(s) not in the approved execution contract for connection '"
                            + connKey + "': " + outcome.unapprovedTables() + ". Approved physical tables: ["
                            + outcome.approvedTables() + "]. Use an approved business object, or state via "
                            + "final_answer that the requested data is not available.")));

            case GOVERNANCE_BLOCKED, CONTRACT_BLOCKED -> plain(mapper.writeValueAsString(Map.of(
                    "error", "Query rejected: " + outcome.message()
                            + ". Only read-only SELECT queries within your governed scope are permitted — revise the query.")));

            // The agent loop is synchronous; surface a narrow-the-query observation.
            case ASYNC -> plain(mapper.writeValueAsString(Map.of(
                    "error", "Query too large to run inline (" + outcome.governance().classification()
                            + "). Add a filter or narrow the range, then try again.")));

            // A query that reached execution and failed (e.g. the model referenced a column
            // that does not exist on an otherwise-approved table) is a RECOVERABLE tool
            // observation, not a fatal fault. Hand the database error back to the model so the
            // ReAct loop can correct the SQL and retry. Throwing here surfaces as a
            // NexusException, which execute() re-throws — aborting the whole run and, for the
            // morning brief, zeroing the agent's entire contribution.
            case FAILED -> plain(mapper.writeValueAsString(Map.of(
                    "error", (outcome.failure() != null && outcome.failure().getMessage() != null
                                    ? outcome.failure().getMessage()
                                    : "Query failed.")
                            + " — Verify every column exists on the exact table you referenced: use only "
                            + "the columns listed for that table in your grounding (a column on one table "
                            + "may not exist on another). Then revise the SQL and try again.")));

            // EXECUTED: content is unchanged (still the bare rows JSON array — ChatService's
            // extractAgentQueryRows keeps working unmodified); the execution_id rides alongside,
            // auxiliary, never merged into content's shape.
            case EXECUTED -> new ToolExecutionResult(outcome.rowsJson(),
                    outcome.executionReference() != null ? outcome.executionReference().executionId() : null);

            // Literal validation is not engaged on the agent path (no domain scope today).
            default -> plain(mapper.writeValueAsString(Map.of("error", String.valueOf(outcome.message()))));
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

    // ── Conversation Memory + Business World capabilities (Phase 2) ────────────

    private String execMemoryList(String conversationId) throws Exception {
        List<Map<String, Object>> roster = memoryService.list(conversationId).stream()
                .map(this::rosterEntryJson)
                .toList();
        return mapper.writeValueAsString(roster);
    }

    private String execMemoryGet(Map<String, Object> args, String conversationId) throws Exception {
        String entityKey = getString(args, "entity_key");

        if (!memoryService.isMember(conversationId, entityKey)) {
            return mapper.writeValueAsString(Map.of("error",
                    "'" + entityKey + "' is not in this conversation's memory yet. Call get_business_object "
                            + "to discover it explicitly if you need it, or memory_list to see what is already known."));
        }

        Optional<DataObject> object = businessWorldAdapter.getBusinessObject(entityKey);
        if (object.isEmpty()) {
            // In memory but no longer authoritative — do not fabricate; report exactly this.
            return mapper.writeValueAsString(Map.of("error",
                    "'" + entityKey + "' is in this conversation's memory but is no longer available in the "
                            + "Business World."));
        }
        return mapper.writeValueAsString(businessObjectJson(object.get()));
    }

    private String execMemoryGetExecutionReference(Map<String, Object> args, String conversationId) throws Exception {
        String referenceId = getString(args, "reference_id");

        Optional<ExecutionReference> found = executionReferenceRepository.findByExecutionId(referenceId);
        if (found.isEmpty()) {
            return mapper.writeValueAsString(Map.of("error",
                    "No execution found for reference_id '" + referenceId + "'."));
        }

        ExecutionReference ref = found.get();
        // Exact validation only — never inferred, never fuzzy: the reference must belong to THIS
        // conversation. (Tenant isolation is already enforced ambiently — a row from another
        // tenant's schema can never be returned by this query in the first place.)
        boolean sameConversation = conversationId != null && conversationId.equals(ref.conversationId());
        if (!sameConversation) {
            return mapper.writeValueAsString(Map.of("error",
                    "reference_id '" + referenceId + "' does not belong to the current conversation."));
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("execution_id", ref.executionId());
        result.put("row_count", ref.rowCount());
        result.put("result_columns", ref.resultColumns());
        result.put("result_json", ref.resultJson());
        result.put("business_object_bindings", ref.businessObjectBindings());
        result.put("retrieval_targets", ref.retrievalTargets());
        return mapper.writeValueAsString(result);
    }

    private String execGetBusinessObject(Map<String, Object> args, String conversationId) throws Exception {
        String entityKey = getString(args, "entity_key");

        Optional<DataObject> object = businessWorldAdapter.getBusinessObject(entityKey);
        if (object.isEmpty()) {
            // No registration on failure — mechanical side effect only follows success.
            return mapper.writeValueAsString(Map.of("error",
                    "No Business Object found for entity_key '" + entityKey + "'."));
        }

        DataObject obj = object.get();
        // Mechanical, non-semantic side effect: a successful authoritative retrieval is
        // remembered for the rest of this conversation. There is no memory.add() tool — this is
        // the ONLY path (besides successful query execution — not wired in this phase's registry
        // switch beyond query_database itself, which does not resolve named business objects here)
        // by which an entity enters the roster.
        memoryService.registerDiscovery(conversationId, entityKey, obj.businessName(),
                BusinessWorldToolAdapter.OBJECT_TYPE_ENTITY);

        return mapper.writeValueAsString(businessObjectJson(obj));
    }

    private String execListBusinessObjects(Map<String, Object> args) throws Exception {
        String group = getString(args, "group");

        // Listing is NOT discovery: no roster interaction here at all.
        List<Map<String, Object>> objects = businessWorldAdapter.listBusinessObjects(group).stream()
                .map(this::listedObjectJson)
                .toList();
        return mapper.writeValueAsString(objects);
    }

    private String execExploreRelated(Map<String, Object> args) throws Exception {
        String entityKey = getString(args, "entity_key");

        // Unfiltered, unranked — every relationship row touching entityKey. No registration of
        // related entities: relationship discovery only tells the LLM what exists.
        List<Map<String, Object>> edges = relationshipAdapter.exploreRelated(entityKey).stream()
                .map(this::relationshipEdgeJson)
                .toList();
        return mapper.writeValueAsString(edges);
    }

    private Map<String, Object> rosterEntryJson(ConversationRosterEntry e) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("entity_key", e.entityKey());
        m.put("business_name", e.businessName());
        m.put("object_type", e.objectType());
        return m;
    }

    private Map<String, Object> listedObjectJson(DataObject o) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("entity_key", o.objectKey());
        m.put("business_name", o.businessName() != null ? o.businessName() : o.entityName());
        m.put("object_type", BusinessWorldToolAdapter.OBJECT_TYPE_ENTITY);
        return m;
    }

    private Map<String, Object> businessObjectJson(DataObject o) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("entity_key", o.objectKey());
        m.put("business_name", o.businessName());
        m.put("object_type", BusinessWorldToolAdapter.OBJECT_TYPE_ENTITY);
        m.put("purpose", o.purpose());
        m.put("domain_key", o.domainKey());
        m.put("connection_key", o.connectionKey());
        m.put("schema", o.schemaName());
        m.put("table", o.tableName());
        return m;
    }

    private Map<String, Object> relationshipEdgeJson(GraphEdge e) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("source_entity", e.source());
        m.put("target_entity", e.target());
        m.put("relationship_type", e.relationshipType());
        m.put("source_column", e.sourceColumn());
        m.put("target_column", e.targetColumn());
        m.put("join_guidance", e.joinGuidance());
        m.put("cardinality", e.cardinality());
        return m;
    }

    private String getString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null)
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Missing required argument: " + key);
        return val.toString();
    }
}
