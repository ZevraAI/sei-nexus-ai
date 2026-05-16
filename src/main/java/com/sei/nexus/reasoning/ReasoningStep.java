package com.sei.nexus.reasoning;

import java.time.Instant;

public record ReasoningStep(
        String stepKey,
        String sessionKey,
        int stepNo,
        String stepType,
        String instruction,
        String evidenceUsed,
        String outcome,
        Double confidenceDelta,
        String executionKey,
        Instant executedAt
) {}
