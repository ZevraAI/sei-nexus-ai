package com.sei.nexus.tenant;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a SaaS tenant.
 * Each tenant has an isolated PostgreSQL schema identified by {@code schemaName}.
 */
public record Tenant(
        UUID   tenantId,
        String slug,
        String name,
        String schemaName,
        String plan,
        String status,
        String contactEmail,
        int    maxUsers,
        Instant createdAt,
        Instant updatedAt
) {}
