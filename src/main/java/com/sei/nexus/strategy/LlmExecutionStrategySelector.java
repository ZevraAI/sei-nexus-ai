package com.sei.nexus.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentrunner.ZevraAgentRepository;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM-based {@link ExecutionStrategySelector}. Classifies a request's execution strategy from its
 * characteristics alone, using the lightweight routing model — one fast JSON call. It reuses the
 * dormant intent axis the pipeline already recognised, promoting it to the first-class
 * {@link IntentType} carried on {@link RequestAnalysis}.
 *
 * <p>Cost: on the CHAT path this call <em>replaces</em> today's unconditional agent-router call
 * (the router now runs only for AGENT), so no LLM pass is added to the dominant path. For tenants
 * with no active agents, AGENT is unreachable and the strategy is decided deterministically with
 * no LLM call at all.
 */
@Component
public class LlmExecutionStrategySelector implements ExecutionStrategySelector {

    private static final Logger log = LoggerFactory.getLogger(LlmExecutionStrategySelector.class);

    /**
     * Below this confidence, an AGENT classification is treated as uncertain and the selector's
     * single decision resolves to CHAT — the deterministic, safe default ("when uncertain, CHAT").
     */
    static final double MIN_AGENT_CONFIDENCE = 0.6;

    private final ZevraAgentRepository agents;
    private final AzureOpenAiClient     openAi;
    private final ObjectMapper          mapper;

    /**
     * Feature flag. {@code true} (default) makes strategy selection the front door. {@code false}
     * reproduces the legacy behaviour: the agent router is the front door (AGENT whenever the
     * tenant has an active agent), so strategy selection is effectively bypassed — an instant,
     * config-only rollback.
     */
    @Value("${zevra.strategy.selector.enabled:true}")
    private boolean enabled;

    public LlmExecutionStrategySelector(ZevraAgentRepository agents,
                                        AzureOpenAiClient openAi,
                                        ObjectMapper mapper) {
        this.agents = agents;
        this.openAi = openAi;
        this.mapper = mapper;
    }

    @Override
    public RequestAnalysis analyze(String question, String tenantSchema) {
        // AGENT is only reachable when the tenant has an ACTIVE agent. With none, the request can
        // only be CHAT — decide deterministically and spend no LLM call (mirrors the router's own
        // active.isEmpty() short-circuit; agent-less tenants stay free).
        if (!hasActiveAgent(tenantSchema)) {
            return RequestAnalysis.chat("no active agents in tenant — only CHAT is reachable");
        }

        // Legacy rollback: bypass strategy selection so the agent router remains the front door.
        if (!enabled) {
            return new RequestAnalysis(ExecutionStrategy.AGENT, IntentType.UNKNOWN, 1.0,
                    "strategy selector disabled — legacy router-first behaviour");
        }

        try {
            String raw = openAi.chatWithJsonFast(
                    List.of(ChatMessage.user("Question: " + question)), SYSTEM_PROMPT);
            return parseAnalysis(raw, mapper);
        } catch (Exception e) {
            log.warn("Strategy selection failed, defaulting to CHAT: {}", e.getMessage());
            return RequestAnalysis.chat("selector failure — safe default");
        }
    }

    private boolean hasActiveAgent(String tenantSchema) {
        try {
            return agents.findByTenant(tenantSchema).stream()
                    .anyMatch(a -> "ACTIVE".equals(a.status()));
        } catch (Exception e) {
            log.warn("Active-agent lookup failed, assuming none: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Parses the selector's JSON into a {@link RequestAnalysis}. Package-private and pure so the
     * classification contract can be pinned without constructing the LLM client or the repository.
     * Applies the safe-default rules: malformed JSON, an unknown strategy, or an under-confident
     * AGENT all resolve to CHAT — the selector's single decision never silently escalates to AGENT.
     */
    static RequestAnalysis parseAnalysis(String rawJson, ObjectMapper mapper) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mapper.readValue(rawJson, Map.class);

            ExecutionStrategy strategy = ExecutionStrategy.fromLabel(str(result.get("strategy")));
            IntentType intent          = IntentType.fromLabel(str(result.get("intentType")));
            double confidence = result.get("confidence") instanceof Number n ? n.doubleValue() : 0.0;
            String reasoning  = str(result.getOrDefault("reasoning", ""));

            if (strategy == ExecutionStrategy.AGENT && confidence < MIN_AGENT_CONFIDENCE) {
                return new RequestAnalysis(ExecutionStrategy.CHAT, intent, confidence,
                        "AGENT confidence " + confidence + " below threshold; safe-defaulted to CHAT");
            }
            return new RequestAnalysis(strategy, intent, confidence,
                    reasoning == null ? "" : reasoning);
        } catch (Exception e) {
            return RequestAnalysis.chat("unparseable strategy response — safe default");
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    /**
     * Classifies HOW a request executes — its execution shape — never its topic, the data it
     * touches, or which agent owns that data.
     */
    static final String SYSTEM_PROMPT = """
            You are the Execution Strategy Selector for the Zevra Unified Answer Engine.
            Decide HOW a request must execute — its execution shape — NOT its topic, NOT which
            data it touches, NOT which agent owns it.

            Return ONLY JSON:
            { "strategy": "...", "intentType": "...", "confidence": 0.0-1.0, "reasoning": "<one sentence>" }

            strategy is one of:
            - CHAT  — a single business question answerable by one execution plan; deterministic;
                      no autonomous investigation.
                e.g. "Show open stores" | "List invoices from yesterday" |
                     "Inventory for item 123" | "What stores are in Texas?"
            - AGENT — unknown reasoning depth: multi-step investigation, hypothesis generation,
                      autonomous planning, multiple dependent tool calls.
                e.g. "Investigate why inventory dropped" | "Find the root cause of negative inventory" |
                     "Explain why sales increased while inventory decreased" |
                     "Identify stores with unusual shrink patterns"
            - REPORT | EXECUTIVE_BRIEF | WORKFLOW — structured report | narrative executive summary |
                     action/approval/process execution. (recognized; may not yet be wired)

            intentType is one of:
            OPERATIONAL_INVESTIGATION | ANALYTICAL | INFORMATIONAL | FOLLOW_UP

            Rules:
            1. Classify by execution characteristics, reasoning complexity, and autonomy — never by
               which table, schema, or data the request references.
            2. A question answerable by ONE query, however large, is CHAT — size is not investigation.
            3. Choose AGENT only when the answer genuinely requires autonomous, multi-step reasoning.
            4. When uncertain, choose CHAT.
            """;
}
