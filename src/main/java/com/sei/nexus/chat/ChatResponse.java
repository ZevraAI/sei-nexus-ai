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
        List<Map<String, Object>> asyncOperations,
        // Raw rows from the most data-rich sync step — used by the frontend
        // for data visualisation. Capped at 100 rows; null when no live query ran.
        List<Map<String, Object>> queryData,
        // Reasoning steps produced by the iterative engine (Phase 2).
        // Each entry: {stepNo, description, rowCount, rowSummary, evaluatorDecision,
        //              evaluatorRationale, sql, executionMs}
        // Empty list when the session used single-shot planning.
        List<Map<String, Object>> reasoningSteps,
        // Business terms Zevra learned from this team and applied to this query.
        // Shown as a subtle badge in the chat UI for transparency.
        // Empty when no learned context was available.
        List<String> learningsApplied
) {}
