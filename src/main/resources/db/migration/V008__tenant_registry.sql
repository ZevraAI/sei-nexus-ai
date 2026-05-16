-- V008: Tenant registry
-- Mirrors sei-nexus-db/003_tenant_registry.sql
-- Creates the shared public-schema tables for multi-tenancy.
-- All subsequent tenant-specific DDL lives in per-tenant schemas
-- provisioned by TenantProvisioningService.

SET search_path = public;

CREATE TABLE IF NOT EXISTS nexus_tenant (
    tenant_id      UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    slug           VARCHAR(64)   NOT NULL UNIQUE,
    name           VARCHAR(255)  NOT NULL,
    schema_name    VARCHAR(64)   NOT NULL UNIQUE,
    plan           VARCHAR(32)   NOT NULL DEFAULT 'STANDARD'
                       CHECK (plan IN ('TRIAL', 'STANDARD', 'PROFESSIONAL', 'ENTERPRISE')),
    status         VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE'
                       CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEPROVISIONED')),
    contact_email  VARCHAR(255),
    max_users      INT           NOT NULL DEFAULT 50,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tenant_slug   ON nexus_tenant(slug)   WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_tenant_schema ON nexus_tenant(schema_name);

CREATE TABLE IF NOT EXISTS nexus_session_index (
    token_hash     VARCHAR(255)  PRIMARY KEY,
    tenant_schema  VARCHAR(64)   NOT NULL REFERENCES nexus_tenant(schema_name) ON DELETE CASCADE,
    user_email     VARCHAR(255)  NOT NULL,
    expires_at     TIMESTAMPTZ   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_session_index_expires ON nexus_session_index(expires_at);
CREATE INDEX IF NOT EXISTS idx_session_index_schema  ON nexus_session_index(tenant_schema);

-- Default tenant: existing public-schema data belongs to this tenant.
INSERT INTO nexus_tenant (tenant_id, slug, name, schema_name, plan, status, max_users)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'default',
    'SEI Nexus Platform',
    'public',
    'ENTERPRISE',
    'ACTIVE',
    9999
) ON CONFLICT (slug) DO NOTHING;
