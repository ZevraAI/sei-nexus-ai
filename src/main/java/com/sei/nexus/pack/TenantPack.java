package com.sei.nexus.pack;

import java.time.Instant;
import java.util.Map;

/**
 * Records that a tenant has applied a specific industry pack.
 * Stored in each tenant's schema in {@code nexus_tenant_pack}.
 */
public record TenantPack(
        String              packKey,
        String              packVersion,
        String              displayName,
        String              status,         // ACTIVE | DISABLED
        Map<String, String> entityMapping,  // pack entity name → actual table name
        Double              coverageScore,  // 0.0–1.0
        Instant             appliedAt,
        String              appliedBy
) {}
