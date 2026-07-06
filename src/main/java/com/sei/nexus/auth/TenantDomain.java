package com.sei.nexus.auth;

import java.time.OffsetDateTime;

public record TenantDomain(
        String         domain,
        String         tenantSchema,
        String         defaultRole,
        String         createdBy,
        OffsetDateTime createdAt
) {}
