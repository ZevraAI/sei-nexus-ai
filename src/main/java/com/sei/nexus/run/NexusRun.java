package com.sei.nexus.run;

import java.time.OffsetDateTime;

public record NexusRun(
        String runKey,
        String conversationId,
        String agentKey,
        String domainKey,
        String userEmail,
        String question,
        String answer,
        String decisionType,
        String status,
        String resultSnapshot,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {
}
