package com.sei.nexus.chat;

import com.sei.nexus.artifacts.ResponseArtifacts;

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
        // LEGACY COMPATIBILITY PATH — a single dataset chosen by a Java-side heuristic (see
        // ReasoningEngine#reason), kept only for existing consumers (DataTable/DataViz/
        // SuggestedQuestions, ScheduledReportService). The authoritative UI-content mechanism is
        // `artifacts.sections` (the model's own plan, each DATASET entry carrying its resolved
        // rows) — new capability must be built on that, never on this field. Capped at 100 rows;
        // null when no live query ran.
        List<Map<String, Object>> queryData,
        // Every row-bearing investigation step's own rows, preserved independently (each entry:
        // {stepNo, description, rows, rowCount}) — never merged, never ranked, never reduced to
        // one "winning" step the way `queryData` above is. Additive, for backward compatibility:
        // `queryData` is unchanged and remains the primary single-dataset field; this field is the
        // complete set of successful business-data results the investigation produced. Empty
        // (never null) when the run produced no row-bearing steps (e.g. the Zevra Agent path,
        // which does not use this reasoning engine).
        List<Map<String, Object>> investigationDatasets,
        // Reasoning steps produced by the iterative engine (Phase 2).
        // Each entry: {stepNo, description, rowCount, rowSummary, evaluatorDecision,
        //              evaluatorRationale, sql, executionMs}
        // Empty list when the session used single-shot planning.
        List<Map<String, Object>> reasoningSteps,
        // Business terms Zevra learned from this team and applied to this query.
        // Shown as a subtle badge in the chat UI for transparency.
        // Empty when no learned context was available.
        List<String> learningsApplied,
        // Zevra Agent session id when the answer came from an autonomous agent.
        // Lets the UI fetch and display the executed tool-call trace.
        // Null for all other decision types.
        String agentSessionId,
        // Generalized, optional decomposition of what Zevra actually produced while answering —
        // understanding, findings, evidence, metrics, recommendations, and a normalized
        // investigation/execution trail. Shared by every execution path (direct chat, Zevra
        // Agent, future workflows); null when nothing meaningful was produced to report (e.g.
        // a knowledge-gap acknowledgement). See ResponseArtifactsBuilder — nothing here is
        // fabricated to fill a UI section.
        ResponseArtifacts artifacts
) {}
