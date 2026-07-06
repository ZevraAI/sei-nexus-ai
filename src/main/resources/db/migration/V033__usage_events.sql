-- V033: AI usage metering
-- Stores one row per LLM call for aggregated usage reporting and billing.
-- Lives in public schema (cross-tenant) so platform admins can see all tenants.

SET search_path = public;

CREATE TABLE IF NOT EXISTS nexus_usage_event (
    id                VARCHAR(120)   PRIMARY KEY,
    tenant_schema     TEXT           NOT NULL,
    user_email        TEXT,
    feature           TEXT           NOT NULL   -- 'chat' | 'agent' | 'brief' | 'report'
                          CHECK (feature IN ('chat','agent','brief','report','routing')),
    agent_name        TEXT,                     -- name of Zevra Agent, null for plain chat
    model             TEXT           NOT NULL,
    prompt_tokens     INTEGER        NOT NULL DEFAULT 0,
    completion_tokens INTEGER        NOT NULL DEFAULT 0,
    cost_usd          NUMERIC(12,8)  NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_usage_tenant_date
    ON nexus_usage_event(tenant_schema, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_usage_feature
    ON nexus_usage_event(feature, created_at DESC);
