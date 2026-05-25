package com.sei.nexus.automation;

import java.util.Map;

/**
 * Immutable snapshot of one node's execution within a workflow run.
 * Serialized to JSON and stored in nexus_automation_execution.step_traces.
 */
public record StepTrace(
        String nodeId,
        String nodeType,
        String nodeLabel,
        String status,                  // SUCCESS | FAILED | SKIPPED
        Map<String, Object> inputVars,  // resolved inputs fed into this step
        Object output,                  // whatever the step produced (String, List, Map…)
        String sqlExecuted,             // only for DB_QUERY nodes — nullable
        String errorMessage,            // only on FAILED — nullable
        long durationMs
) {}
