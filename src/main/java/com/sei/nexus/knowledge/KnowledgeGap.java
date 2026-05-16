package com.sei.nexus.knowledge;

import java.time.OffsetDateTime;

public record KnowledgeGap(
        String gapKey,
        String domainKey,
        String gapType,
        String runKey,
        String question,
        String gapDescription,
        String proposalText,
        String status,
        String resolvedBy,
        String resolutionNote,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
