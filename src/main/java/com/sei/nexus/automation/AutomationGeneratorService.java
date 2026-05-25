package com.sei.nexus.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.connection.NexusConnection;
import com.sei.nexus.sql.DynamicSqlService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AutomationGeneratorService {

    private final AzureOpenAiClient openAi;
    private final ConnectionRepository connectionRepository;
    private final DynamicSqlService dynamicSql;
    private final AutomationService automationService;
    private final AutomationRepository automationRepository;
    private final ObjectMapper mapper;

    public AutomationGeneratorService(AzureOpenAiClient openAi,
                                      ConnectionRepository connectionRepository,
                                      DynamicSqlService dynamicSql,
                                      AutomationService automationService,
                                      AutomationRepository automationRepository,
                                      ObjectMapper mapper) {
        this.openAi = openAi;
        this.connectionRepository = connectionRepository;
        this.dynamicSql = dynamicSql;
        this.automationService = automationService;
        this.automationRepository = automationRepository;
        this.mapper = mapper;
    }

    /**
     * Step 1 — Analyse the requirement.
     * Fetches real table names from each available connection so the LLM
     * can suggest accurate tables rather than hallucinating names.
     */
    public Map<String, Object> analyze(String requirement) {
        List<NexusConnection> connections = connectionRepository.findAll();

        StringBuilder ctx = new StringBuilder();
        if (connections.isEmpty()) {
            ctx.append("No connections configured yet.");
        } else {
            for (NexusConnection c : connections) {
                ctx.append("Connection key=").append(c.connectionKey())
                   .append(" type=").append(c.connectionType());
                if (c.usageDescription() != null)
                    ctx.append(" desc=").append(c.usageDescription());
                ctx.append("\n");

                // Append real table names from this connection
                String tables = fetchTableNames(c.connectionKey());
                if (!tables.isBlank())
                    ctx.append("  Tables: ").append(tables).append("\n");
            }
        }

        String systemPrompt = """
                You are a workflow design assistant for the Zevra automation platform.
                Analyse the user's automation requirement and suggest how to build it.

                Available connections and their real table names:
                """ + ctx + """

                Return ONLY a JSON object with these exact fields:
                {
                  "summary": "1-2 sentence description of what the workflow will do",
                  "suggestedConnections": ["connection-key"],
                  "suggestedTables": ["exact_table_name_from_the_list_above"],
                  "proposedSteps": [
                    "1. Trigger: receive webhook with order_id",
                    "2. DB_QUERY: fetch row from demo_order by order_id",
                    "3. RESPONSE: return result"
                  ],
                  "clarifications": []
                }
                Use ONLY table names that appear in the connections list above.
                Return only valid JSON — no markdown, no extra text.
                """;

        String raw = openAi.chatWithJson(
                List.of(ChatMessage.user("Analyse this automation requirement:\n\n" + requirement)),
                systemPrompt);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mapper.readValue(raw, Map.class);
            return result;
        } catch (Exception e) {
            return Map.of(
                    "summary", "Ready to generate a workflow for: " + requirement,
                    "suggestedConnections", List.of(),
                    "suggestedTables", List.of(),
                    "proposedSteps", List.of("Trigger → Query → Respond"),
                    "clarifications", List.of());
        }
    }

    /**
     * Step 2 — Generate the full workflow graph.
     * If {@code workflowId} is non-null, replaces the graph of the existing workflow
     * (keeping its name, slug, and status).  Otherwise creates a new DRAFT workflow.
     * Fetches real column names for the selected tables so the LLM generates
     * SQL that references columns that actually exist.
     */
    public AutomationWorkflow generate(String requirement,
                                       String summary,
                                       List<String> selectedConnections,
                                       List<String> selectedTables,
                                       String workflowId,
                                       String tenantSchema,
                                       String createdBy) {
        // Build rich schema context: table names + actual column names
        StringBuilder schemaCtx = new StringBuilder();
        for (String connKey : selectedConnections) {
            connectionRepository.findByKey(connKey).ifPresent(c -> {
                schemaCtx.append("Connection key=").append(connKey)
                         .append(" type=").append(c.connectionType()).append("\n");

                try {
                    List<Map<String, Object>> tables =
                            dynamicSql.listTablesWithColumnCounts(connKey, "public");
                    for (Map<String, Object> row : tables) {
                        String tableName = String.valueOf(row.get("table_name"));
                        String columns   = String.valueOf(row.getOrDefault("column_names", ""));
                        // Only include tables the user actually selected (if any were selected)
                        if (selectedTables.isEmpty() || selectedTables.contains(tableName)) {
                            schemaCtx.append("  Table: ").append(tableName)
                                     .append(" — columns: ").append(columns).append("\n");
                        }
                    }
                } catch (Exception ignored) {
                    // If catalog fails, still attempt generation without column hints
                    if (!selectedTables.isEmpty())
                        schemaCtx.append("  Tables: ").append(String.join(", ", selectedTables)).append("\n");
                }
            });
        }

        String userMessage = """
                Requirement: %s

                Summary: %s

                Database schema (use ONLY these exact table and column names in SQL):
                %s

                Generate a complete, executable workflow.
                """.formatted(requirement, summary,
                schemaCtx.isEmpty() ? "No schema provided — use trigger fields only." : schemaCtx);

        String raw = openAi.chatWithJson(
                List.of(ChatMessage.user(userMessage)),
                buildGenerationSystemPrompt());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mapper.readValue(raw, Map.class);

            String name        = String.valueOf(result.getOrDefault("name", "Generated Workflow"));
            String description = String.valueOf(result.getOrDefault("description", requirement));
            String slug        = uniqueSlug(slugify(String.valueOf(result.getOrDefault("slug", name))), tenantSchema);
            Object graphObj    = result.get("graph");

            if (graphObj == null)
                throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "LLM did not return a graph object");

            String graphJson = mapper.writeValueAsString(graphObj);

            if (workflowId != null && !workflowId.isBlank()) {
                // Update only the graph and description — keep existing name, slug, status
                AutomationWorkflow existing = automationService.getWorkflow(workflowId, tenantSchema);
                return automationService.updateWorkflow(
                        workflowId, tenantSchema,
                        existing.name(), description, existing.slug(),
                        existing.status(), existing.triggerType(), graphJson);
            }

            return automationService.createWorkflow(
                    tenantSchema, name, description, slug,
                    "WEBHOOK", graphJson, createdBy);

        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Workflow generation failed: " + e.getMessage());
        }
    }

    // ── System prompt ─────────────────────────────────────────────────────────

    private String buildGenerationSystemPrompt() {
        return """
You are a workflow generation engine for the Zevra automation platform.
Generate a complete, executable workflow graph as JSON.

## Available Node Types

### TRIGGER  (always the first node — no incoming edges)
data: { "label": "string" }

### DB_QUERY  (query a database)
data: {
  "label": "string",
  "connectionKey": "exact-connection-key",
  "sql": "SELECT ... WHERE col = '{{trigger.field}}'",
  "maxRows": 100
}
IMPORTANT: Use ONLY table and column names from the schema provided in the user message.
Output: list of row maps. Access a field as: {{nodes.NODEID[0].column_name}}

### AI_REASON  (call GPT-4o)
data: {
  "label": "string",
  "systemPrompt": "You are a ...",
  "userPrompt": "Analyse this: {{nodes.NODEID[0].status}}",
  "outputFormat": "text"
}
outputFormat: "text" → String  |  "json" → Map

### CONDITION  (boolean branch — two outgoing edges required)
data: {
  "label": "string",
  "leftValue": "{{nodes.NODEID[0].status}}",
  "operator": "eq",
  "rightValue": "ACTIVE"
}
Operators: eq | neq | gt | lt | gte | lte | contains | isEmpty | isNotEmpty
Outgoing edges MUST use sourceHandle "true" and "false".

### TRANSFORM  (reshape data)
data: {
  "label": "string",
  "mappings": [{ "key": "out_field", "value": "{{nodes.NODEID[0].col}}" }]
}

### IMAGE_ANALYSE  (vision model)
data: {
  "label": "string",
  "question": "Describe the damage.",
  "imageRef": "{{nodes.NODEID[0].image_base64}}",
  "mimeType": "image/jpeg"
}

### RESPONSE  (terminal node — no outgoing edges, required)
data: {
  "label": "string",
  "fields": [{ "key": "result", "value": "{{nodes.NODEID.output}}" }]
}
Use "fields": [] to pass the last node output through unchanged.

## Variable Syntax
- Trigger payload:    {{trigger.field_name}}
- DB_QUERY row:       {{nodes.NODEID[0].column_name}}
- Map/text output:    {{nodes.NODEID.field_name}}

## Graph JSON Structure
{
  "nodes": [
    { "id": "n1", "type": "TRIGGER",   "position": {"x": 250, "y": 50},  "data": {"label": "Trigger"} },
    { "id": "n2", "type": "DB_QUERY",  "position": {"x": 250, "y": 210}, "data": {"label": "...", "connectionKey": "...", "sql": "..."} },
    { "id": "n3", "type": "RESPONSE",  "position": {"x": 250, "y": 370}, "data": {"label": "Done", "fields": []} }
  ],
  "edges": [
    { "id": "e-n1-n2", "source": "n1", "target": "n2", "sourceHandle": null },
    { "id": "e-n2-n3", "source": "n2", "target": "n3", "sourceHandle": null }
  ]
}

Rules:
- Node IDs: n1, n2, n3, ... sequential
- y positions: 50, 210, 370, 530, ... (increment 160 per node)
- CONDITION edges: sourceHandle = "true" or "false"
- All other edges: sourceHandle = null (JSON null, not the string "null")
- Must start with TRIGGER, must end with RESPONSE

## Response Format
Return ONLY this JSON object (no markdown fences, no extra text):
{
  "name": "Human-Readable Name",
  "slug": "kebab-case-slug",
  "description": "What this workflow does",
  "graph": { "nodes": [...], "edges": [...] }
}
""";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns a comma-separated list of table names for a connection, or "" on failure. */
    private String fetchTableNames(String connectionKey) {
        try {
            List<Map<String, Object>> rows =
                    dynamicSql.listTablesWithColumnCounts(connectionKey, "public");
            return rows.stream()
                    .map(r -> String.valueOf(r.get("table_name")))
                    .collect(Collectors.joining(", "));
        } catch (Exception e) {
            return "";
        }
    }

    private String slugify(String input) {
        return input.toLowerCase()
                .replaceAll("[^a-z0-9 -]", "")
                .trim()
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-");
    }

    /** Appends -2, -3, ... until a slug is not already taken in this tenant. */
    private String uniqueSlug(String base, String tenantSchema) {
        if (!automationRepository.slugExists(base, tenantSchema)) return base;
        int n = 2;
        while (true) {
            String candidate = base + "-" + n++;
            if (!automationRepository.slugExists(candidate, tenantSchema)) return candidate;
        }
    }
}
