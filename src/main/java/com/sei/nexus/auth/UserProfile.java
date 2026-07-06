package com.sei.nexus.auth;

import java.time.OffsetDateTime;

public record UserProfile(
        String         email,
        String         tenantSchema,
        String         role,
        String         status,
        String         displayName,
        String         invitedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
