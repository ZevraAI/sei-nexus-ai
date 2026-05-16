package com.sei.nexus.domain;

import java.time.OffsetDateTime;

public record Domain(
        String domainKey,
        String name,
        String description,
        String ownerTeam,
        String ownerEmail,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
