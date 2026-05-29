package com.sei.nexus.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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

    public ZevraAgentRouter(ZevraAgentRepository repository,
                             AzureOpenAiClient openAi,
                             ObjectMapper mapper) {
        this.repository = repository;
        this.openAi     = openAi;
        this.mapper     = mapper;
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
                         .append(" | goal: ").append(a.goal())
                         .append("\n");
            }

            String systemPrompt = """
                    You are an intelligent agent dispatcher for Zevra.
                    Given a user's message and a list of available AI agents with their stated goals,
                    determine which agent is best suited to handle the request.

                    Return ONLY valid JSON — no markdown, no explanation:
                    {
                      "agent_id": "<id from the list above, or null if no agent is appropriate>",
                      "confidence": <0.0 to 1.0>,
                      "reasoning": "<one sentence>"
                    }

                    Return null for agent_id if:
                    - The question is general knowledge, a greeting, or a meta question about Zevra
                    - No agent's goal meaningfully relates to the user's request
                    - You are genuinely uncertain

                    Do NOT force a match. A confident null is better than a wrong agent.
                    """;

            String userPrompt = "User message: " + userMessage + "\n\nAvailable agents:\n" + agentList;

            String raw = openAi.chatWithJson(
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
