-- V030: Tenant domain registry for SSO auto-provisioning
-- Maps an email domain (e.g. acme.com) to a tenant schema.
-- When a user signs in via Google/Azure SSO for the first time and their
-- email domain is registered here, they are automatically provisioned into
-- the matching tenant with the default role (ANALYST) — no pre-invite needed.

SET search_path = public;

CREATE TABLE IF NOT EXISTS nexus_tenant_domain (
    domain        VARCHAR(255) PRIMARY KEY,          -- e.g. acme.com (lowercase, no @)
    tenant_schema VARCHAR(64)  NOT NULL REFERENCES nexus_tenant(schema_name) ON DELETE CASCADE,
    default_role  VARCHAR(40)  NOT NULL DEFAULT 'ANALYST'
                      CHECK (default_role IN ('ADMIN','ANALYST','DOMAIN_OWNER')),
    created_by    VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tenant_domain_schema ON nexus_tenant_domain(tenant_schema);
