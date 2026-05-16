package com.sei.nexus.chat;

public record OrchestratorDecision(
        String type,
        String intentType,
        String evidenceMode,
        boolean requiresExecution,
        boolean requiresMemory,
        boolean requiresClarification
) {}
