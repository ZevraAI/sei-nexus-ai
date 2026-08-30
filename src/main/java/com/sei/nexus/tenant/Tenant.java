package com.sei.nexus.tenant;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a SaaS tenant.
 * Each tenant has an isolated PostgreSQL schema identified by {@code schemaName}.
 *
 * <p>The {@code aiKnowledge*} fields are Phase 1 of the Persistent Tenant Knowledge
 * migration: at most one OpenAI Vector Store per tenant. {@code aiKnowledgeVectorStoreId}
 * is {@code null} until provisioned — existing tenants are never backfilled, so {@code null}
 * simply means "not yet provisioned" and is a fully valid, operational state. See
 * {@link TenantRepository#updateAiKnowledgeReady} / {@link TenantRepository#updateAiKnowledgeFailed}
 * for how these fields are updated, and {@link TenantProvisioningService#provisionAiKnowledgeStore}
 * for how provisioning is triggered.
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
        Instant updatedAt,
        String  aiKnowledgeVectorStoreId,
        String  aiKnowledgeStatus,
        String  aiKnowledgeError,
        Instant aiKnowledgeProvisionedAt
) {}
