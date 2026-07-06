package com.sei.nexus.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.sql.DynamicSqlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure LLM-based agent dispatcher.
 * Given a user's message and the list of active Zevra Agents,
 * asks the LLM which agent (if any) should handle the request.
 * No keywords, no hardcoded rules — semantic matching only.
 */
@Service
public class ZevraAgentRouter {

    private static final Logger log = LoggerFactory.getLogger(ZevraAgentRouter.class);
    private static final double MIN_CONFIDENCE = 0.65;

    private final ZevraAgentRepository repository;
    private final AzureOpenAiClient    openAi;
    private final ObjectMapper         mapper;
    private final DynamicSqlService    dynamicSql;

    public ZevraAgentRouter(ZevraAgentRepository repository,
                             AzureOpenAiClient openAi,
                             ObjectMapper mapper,
                             DynamicSqlService dynamicSql) {
        this.repository = repository;
        this.openAi     = openAi;
        this.mapper     = mapper;
        this.dynamicSql = dynamicSql;
    }

    /**
     * Returns the ZevraAgent that should handle this message, or empty if none is appropriate.
     * Only ACTIVE agents are considered.
     */
    public java.util.Optional<ZevraAgent> route(String userMessage, String tenantSchema) {
        List<ZevraAgent> active = repository.findByTenant(tenantSchema).stream()
                .filter(a -> "ACTIVE".equals(a.status()))
                .toList();

        if (active.isEmpty()) return java.util.Optional.empty();

        // Single active agent — still let the LLM decide if it's appropriate
        // (avoids always forcing routing when agent is unrelated to the question)
        try {
            StringBuilder agentList = new StringBuilder();
            for (ZevraAgent a : active) {
                agentList.append("- id: ").append(a.id())
                         .append(" | name: ").append(a.name())
                         .append(" | goal: ").append(a.goal());

                if (a.description() != null && !a.description().isBlank()) {
                    agentList.append(" | ").append(a.description());
                }

                // Include the actual table names from each connection so the router
                // can match data-retrieval questions even when the goal text doesn't
                // explicitly mention the data type.
                // "get me all orders" → agent has demo_order table → confident match.
                // Table names are loaded dynamically — no hardcoding per tenant.
                if (!a.connectionKeys().isEmpty()) {
                    String tables = a.connectionKeys().stream()
                            .flatMap(connKey -> {
                                try {
                                    return dynamicSql.listTablesWithColumnCounts(connKey, "public")
                                            .stream()
                                            .map(row -> String.valueOf(row.get("table_name")))
                                            .filter(t -> !t.startsWith("nexus_") && !t.startsWith("flyway_"));
                                } catch (Exception e) {
                                    return java.util.stream.Stream.of(connKey);
                                }
                            })
                            .collect(Collectors.joining(", "));
                    if (!tables.isBlank()) {
                        agentList.append(" | data tables: ").append(tables);
                    }
                }
                agentList.append("\n");
            }

            String systemPrompt = """
                    You are an intelligent agent dispatcher for Zevra.
                    Given a user's message and a list of available AI agents, route to the most
                    appropriate agent. Consider BOTH the agent's stated goal AND its data connections.

                    An agent is appropriate when:
                    - Its goal aligns with the user's intent, OR
                    - Its data connections contain the data the user is asking about
                      (e.g. a question about orders should route to an agent with an orders database)

                    Return ONLY valid JSON — no markdown, no explanation:
                    {
                      "agent_id": "<id from the list above, or null if no agent is appropriate>",
                      "confidence": <0.0 to 1.0>,
                      "reasoning": "<one sentence>"
                    }

                    Return null for agent_id only if:
                    - The question is a greeting or meta question about Zevra itself
                    - No agent has relevant data connections or goal alignment
                    """;


            String userPrompt = "User message: " + userMessage + "\n\nAvailable agents:\n" + agentList;

            String raw = openAi.chatWithJsonFast(
                    List.of(ChatMessage.user(userPrompt)), systemPrompt);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = mapper.readValue(raw, Map.class);

            Object agentIdObj  = result.get("agent_id");
            double confidence  = ((Number) result.getOrDefault("confidence", 0.0)).doubleValue();
            String reasoning   = String.valueOf(result.getOrDefault("reasoning", ""));

            if (agentIdObj == null || "null".equals(agentIdObj.toString())) {
                log.debug("Router: no agent match ({})", reasoning);
                return java.util.Optional.empty();
            }

            if (confidence < MIN_CONFIDENCE) {
                log.debug("Router: confidence {:.2f} below threshold for agent {} — falling through",
                        confidence, agentIdObj);
                return java.util.Optional.empty();
            }

            String agentId = agentIdObj.toString();
            return active.stream()
                    .filter(a -> a.id().equals(agentId))
                    .findFirst()
                    .map(a -> {
                        log.info("Router: → {} (confidence={}, reason={})",
                                a.name(), confidence, reasoning);
                        return a;
                    });

        } catch (Exception e) {
            log.warn("ZevraAgentRouter failed, falling through to normal chat: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
