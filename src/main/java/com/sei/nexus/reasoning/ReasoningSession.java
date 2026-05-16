package com.sei.nexus.reasoning;

import java.time.Instant;

public record ReasoningSession(
        String sessionKey,
        String runKey,
        String conversationId,
        String agentKey,
        String domainKey,
        String initialQuestion,
        String investigationPlan,
        String status,
        String conclusion,
        Double confidenceScore,
        Instant startedAt,
        Instant concludedAt
) {}
