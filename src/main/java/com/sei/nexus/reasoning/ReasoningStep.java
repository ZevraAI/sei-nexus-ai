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
        Instant executedAt,
        // Persisted so a step's actual SUFFICIENT/NEED_MORE_DATA/DEAD_END verdict and rationale
        // are observable after the fact — the columns already existed in nexus_reasoning_step
        // but were never populated by any writer.
        String evaluatorDecision,
        String evaluatorRationale
) {}
