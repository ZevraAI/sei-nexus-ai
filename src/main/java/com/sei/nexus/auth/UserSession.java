package com.sei.nexus.auth;

import java.time.OffsetDateTime;

public record UserSession(
        String sessionKey,
        String userEmail,
        String sessionTokenHash,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt
) {
}
