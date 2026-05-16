package com.sei.nexus.auth;

import java.time.OffsetDateTime;

public record UserAccount(
        String email,
        String displayName,
        String passwordHash,
        String role,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
