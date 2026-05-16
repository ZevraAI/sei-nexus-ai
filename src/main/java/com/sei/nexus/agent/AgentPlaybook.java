package com.sei.nexus.agent;

import java.time.Instant;

public record AgentPlaybook(
    String playbookKey,
    String agentKey,
    String name,
    String triggerConditions,
    String investigationSteps,
    String escalationRules,
    Double confidenceThreshold,
    String preferredEvidenceOrder,
    Integer maxInvestigationSteps,
    String status,
    Instant createdAt,
    Instant updatedAt
) {}
