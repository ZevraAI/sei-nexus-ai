package com.sei.nexus.pack;

import java.time.Instant;
import java.util.Map;

/**
 * Records that a tenant has applied a specific industry pack.
 * Stored in each tenant's schema in {@code nexus_tenant_pack}.
 *
 * <p>Global Pack Foundation: {@code connectionKey} is additive — the approved model
 * scopes a Pack assignment to a connection ("each connection has exactly one active
 * Industry Pack"), enforced at the database level by a partial unique index on
 * {@code (connection_key) WHERE status = 'ACTIVE'} (see the V041 migration). No
 * current caller of {@link com.sei.nexus.pack.IndustryPackService#applyPack} supplies
 * a connection key yet — this field exists so the storage model and constraint are
 * ready for a future step to wire one through; every pack applied today continues to
 * record {@code connectionKey = null} exactly as before this change.
 */
public record TenantPack(
        String              packKey,
        String              connectionKey,  // additive; null until a future step supplies one
        String              packVersion,
        String              displayName,
        String              status,         // ACTIVE | DISABLED
        Map<String, String> entityMapping,  // pack entity name → actual table name
        Double              coverageScore,  // 0.0–1.0
        Instant             appliedAt,
        String              appliedBy
) {}
