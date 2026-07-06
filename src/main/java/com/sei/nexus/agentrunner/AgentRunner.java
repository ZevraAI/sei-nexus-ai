package com.sei.nexus.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AgentMessage;
import com.sei.nexus.ai.AgentToolResponse;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.graph.KnowledgeGraphService;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.sql.DynamicSqlService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ReAct loop: given an agent and a user message, the LLM iteratively
 * calls tools (query_database, describe_schema, analyze_image) until it
 * has enough information to call final_answer.
 */
@Service
public class AgentRunner {

    private final AzureOpenAiClient    openAi;
    private final AgentToolRegistry    toolRegistry;
    private final ZevraAgentRepository repository;
    private final DynamicSqlService    dynamicSql;
    private final ObjectMapper         mapper;
    private final SemanticService      semanticService;
    private final KnowledgeGraphService graphService;
    private final JdbcTemplate         jdbc;

    public AgentRunner(AzureOpenAiClient openAi,
                       AgentToolRegistry toolRegistry,
                       ZevraAgentRepository repository,
                       DynamicSqlService dynamicSql,
                       ObjectMapper mapper,
                       SemanticService semanticService,
                       KnowledgeGraphService graphService,
                       JdbcTemplate jdbc) {
        this.openAi          = openAi;
        this.toolRegistry    = toolRegistry;
        this.repository      = repository;
        this.dynamicSql      = dynamicSql;
        this.mapper          = mapper;
        this.semanticService = semanticService;
        this.graphService    = graphService;
        this.jdbc            = jdbc;
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
            // 1. Build context — semantic vocabulary first, raw schema as compact fallback.
            //    CRITICAL: We filter both to keywords from the question so we never dump
            //    the full graph (50+ entities) or full schema into every iteration.
            long schemaStart = System.currentTimeMillis();
            List<String> domainKeys  = loadActiveDomainKeys();
            Set<String>  keywords    = extractKeywords(inputMessage);

            // Semantic vocabulary: only terms matching the question keywords.
            // Graph entity descriptions are included for business meaning but
            // table name references are stripped — the Schema section is authoritative
            // for actual table names and prevents mismatches with knowledge graph data.
            String semanticCtx = buildFilteredVocabulary(domainKeys, keywords);

            // Raw schema: only business tables, filtered to relevant ones, used as
            // fallback for tables not covered by vocabulary
            String schemaContext = buildSchemaContext(agent.connectionKeys(), inputMessage);

            steps.add(step("SCHEMA_LOAD", Map.of("connections", agent.connectionKeys()),
                    null, System.currentTimeMillis() - schemaStart));

            // 2. Build system prompt
            String systemPrompt = buildSystemPrompt(agent, semanticCtx, schemaContext);

            // 3. Build tool definitions
            List<Map<String, Object>> tools =
                    toolRegistry.getToolDefinitions(agent.connectionKeys());

            // 4. Initialize conversation
            List<AgentMessage> messages = new ArrayList<>();
            messages.add(AgentMessage.user(inputMessage));

            // 5. ReAct loop — set usage context so every LLM call is attributed
            com.sei.nexus.usage.UsageContext.set("agent", null, agent.name());
            int iterations = 0;
            while (iterations < agent.maxIterations()) {
                iterations++;
                long callStart = System.currentTimeMillis();

                // Prune history to keep only the user message + last 2 tool-call pairs.
                // Without pruning the prompt grows with every iteration: by step 4 the LLM
                // re-reads all previous queries and results, doubling token cost each time.
                AgentToolResponse response =
                        openAi.chatWithTools(pruneHistory(messages, 2), systemPrompt, tools);

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

    // Column names that typically hold a small set of string values —
    // we fetch DISTINCT values for these so the LLM uses the exact strings stored.
    private static final java.util.Set<String> STATUS_COLUMNS = java.util.Set.of(
            "status", "state", "severity", "type", "category", "priority",
            "stage", "condition", "level", "kind");

    // Max tables to include per connection in the schema context.
    // Sending every table bloats the prompt — we only include the most relevant ones.
    private static final int MAX_SCHEMA_TABLES = 8;

    private String buildSchemaContext(List<String> connectionKeys, String question) {
        Set<String> keywords = extractKeywords(question);
        StringBuilder sb = new StringBuilder();

        for (String connKey : connectionKeys) {
            sb.append("Connection: ").append(connKey).append("\n");
            try {
                List<Map<String, Object>> allTables =
                        dynamicSql.listTablesWithColumnCounts(connKey, "public");

                // Strip Zevra platform tables — agents should only see business data.
                // Without this filter the context includes 40+ nexus_* system tables
                // which bloats every prompt by thousands of tokens.
                List<Map<String, Object>> businessTables = allTables.stream()
                        .filter(row -> {
                            String n = String.valueOf(row.get("table_name"));
                            return !n.startsWith("nexus_") && !n.startsWith("flyway_");
                        })
                        .toList();

                // Score each remaining table by relevance to the question — include top N.
                // A table scores +3 if its name contains a keyword, +1 per column match.
                List<Map<String, Object>> ranked = businessTables.stream()
                        .sorted((a, b) -> {
                            int sa = tableRelevance(a, keywords);
                            int sb2 = tableRelevance(b, keywords);
                            if (sb2 != sa) return sb2 - sa; // higher score first
                            // tie-break: prefer tables whose names appear in the question
                            return String.valueOf(a.get("table_name"))
                                         .compareTo(String.valueOf(b.get("table_name")));
                        })
                        .limit(MAX_SCHEMA_TABLES)
                        .toList();

                for (Map<String, Object> row : ranked) {
                    String tableName = String.valueOf(row.get("table_name"));
                    sb.append("  - ").append(tableName)
                      .append(" (").append(row.get("column_names")).append(")\n");

                    // Append distinct values for status/category columns so the LLM
                    // uses exact stored values and never guesses wrong case or spelling.
                    String colNames = String.valueOf(row.getOrDefault("column_names", ""));
                    for (String col : colNames.split(",")) {
                        String colName = col.trim().replaceAll("\\s*\\(.*?\\)", "").trim();
                        if (STATUS_COLUMNS.contains(colName.toLowerCase())) {
                            try {
                                List<Map<String, Object>> vals = dynamicSql.executeQuery(
                                        connKey,
                                        "SELECT DISTINCT " + colName + " FROM " + tableName +
                                        " WHERE " + colName + " IS NOT NULL ORDER BY " + colName + " LIMIT 15",
                                        15);
                                if (!vals.isEmpty()) {
                                    String sample = vals.stream()
                                            .map(v -> "'" + v.get(colName) + "'")
                                            .collect(java.util.stream.Collectors.joining(", "));
                                    sb.append("      ").append(colName)
                                      .append(" values: ").append(sample).append("\n");
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception e) {
                sb.append("  (schema unavailable)\n");
            }
        }
        return sb.toString();
    }

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

    /** Scores a table row by how many question keywords appear in its name or columns. */
    private int tableRelevance(Map<String, Object> row, Set<String> keywords) {
        if (keywords.isEmpty()) return 0;
        String name = String.valueOf(row.get("table_name")).toLowerCase();
        String cols = String.valueOf(row.getOrDefault("column_names", "")).toLowerCase();
        int score = 0;
        for (String kw : keywords) {
            if (name.contains(kw))  score += 3;
            if (cols.contains(kw))  score += 1;
        }
        return score;
    }

    /** Extracts meaningful keywords from the user question (strips stop words + short tokens). */
    private Set<String> extractKeywords(String question) {
        if (question == null || question.isBlank()) return Set.of();
        Set<String> stops = Set.of(
                "show", "me", "all", "the", "a", "an", "is", "are", "was", "were",
                "what", "which", "how", "many", "list", "get", "find", "give", "tell",
                "and", "or", "of", "in", "on", "at", "to", "for", "with", "from");
        return java.util.Arrays.stream(question.toLowerCase().split("[\\s,?!.;:]+"))
                .filter(w -> w.length() >= 3 && !stops.contains(w))
                .collect(java.util.stream.Collectors.toSet());
    }

    /** Fetches active domain keys for the current tenant (TenantContext already set). */
    private List<String> loadActiveDomainKeys() {
        try {
            return jdbc.queryForList(
                    "SELECT domain_key FROM nexus_domain WHERE status = 'ACTIVE' LIMIT 20",
                    String.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Returns only vocabulary terms whose term or SQL matches the question keywords.
     * Sending the full vocabulary (100+ terms) wastes tokens on every iteration.
     * A question about "open orders" needs only the "open orders" entry, not all terms.
     */
    private String buildFilteredVocabulary(List<String> domainKeys, Set<String> keywords) {
        if (domainKeys.isEmpty() || keywords.isEmpty()) return "";
        try {
            String[] arr = domainKeys.toArray(new String[0]);
            List<Map<String, Object>> rows = jdbc.query(
                    "SELECT term, sql_equivalent FROM nexus_operational_vocabulary " +
                    "WHERE domain_key = ANY(?::text[]) AND status = 'ACTIVE' LIMIT 100",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", arr)),
                    (rs, i) -> Map.<String, Object>of(
                            "term", rs.getString("term"),
                            "sql",  rs.getString("sql_equivalent") != null
                                        ? rs.getString("sql_equivalent") : ""));

            // Keep only terms where at least one keyword appears in the term text.
            // From the SQL pattern we extract only the WHERE/filter logic — not the
            // full SELECT with a table name that may be stale or from a different schema.
            // Table names come from the Schema section (authoritative); vocabulary
            // provides the filter condition to apply to those tables.
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> row : rows) {
                String term = String.valueOf(row.get("term")).toLowerCase();
                boolean relevant = keywords.stream().anyMatch(term::contains);
                if (relevant) {
                    sb.append("  ").append(row.get("term"));
                    String sql = String.valueOf(row.get("sql"));
                    if (!sql.isBlank()) {
                        // Extract only the WHERE clause — drop the SELECT/FROM with table name
                        String upperSql = sql.toUpperCase();
                        int whereIdx = upperSql.indexOf("WHERE");
                        String filter = whereIdx >= 0 ? sql.substring(whereIdx) : sql;
                        sb.append(" → ").append(filter);
                    }
                    sb.append("\n");
                }
            }
            return sb.length() > 0 ? "Business vocabulary:\n" + sb : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String buildSystemPrompt(ZevraAgent agent,
                                     String semanticCtx,
                                     String schemaContext) {
        // Concise — sent on every ReAct iteration, every extra token multiplies cost.
        StringBuilder sb = new StringBuilder();
        sb.append(agent.persona()).append(" ").append(agent.goal()).append("\n\n");

        // Vocabulary comes first — if the term has a known SQL pattern,
        // the agent uses it directly without extra schema queries.
        if (semanticCtx != null && !semanticCtx.isBlank()) {
            sb.append(semanticCtx).append("\n");
        }

        // Compact schema fallback for tables not covered by vocabulary.
        if (schemaContext != null && !schemaContext.isBlank()) {
            sb.append("Schema:\n").append(schemaContext).append("\n");
        }

        sb.append("CRITICAL: The Schema section lists the ACTUAL table names in the database. " +
                  "Always use those exact table names when writing SQL. " +
                  "Vocabulary patterns show SQL logic — adapt them to use the Schema table names. " +
                  "SELECT only. Query data, call final_answer with findings. Be factual.");
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
