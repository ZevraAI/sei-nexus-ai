package com.sei.nexus.chat;

import java.util.List;
import java.util.Map;

public record ChatResponse(
        String conversationId,
        String runKey,
        String answer,
        List<Map<String, Object>> sources,
        OrchestratorDecision decision,
        String routedAgentKey,
        String routedAgentName,
        String domainKey,
        double routingConfidence,
        boolean needsKnowledge,
        String suggestedAction,
        List<Map<String, Object>> quickRefinements,
        List<Map<String, Object>> asyncOperations
) {}
