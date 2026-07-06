-- V031: Agent Morning Brief
-- Stores daily AI-generated executive briefings produced by Zevra Agents.
-- One brief per tenant per day; config stores per-tenant schedule settings.

SET search_path = public;

CREATE TABLE IF NOT EXISTS nexus_morning_brief (
    id             VARCHAR(120) PRIMARY KEY,
    tenant_schema  TEXT NOT NULL,
    brief_date     DATE NOT NULL,
    status         TEXT NOT NULL DEFAULT 'GENERATING'
                       CHECK (status IN ('GENERATING','READY','FAILED')),
    headline       TEXT,
    sections_json  JSONB NOT NULL DEFAULT '[]',
    agents_used    TEXT[] NOT NULL DEFAULT '{}',
    generated_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_schema, brief_date)
);

CREATE TABLE IF NOT EXISTS nexus_morning_brief_config (
    tenant_schema  TEXT PRIMARY KEY REFERENCES nexus_tenant(schema_name) ON DELETE CASCADE,
    schedule_time  TEXT NOT NULL DEFAULT '07:00',   -- HH:mm in tenant's timezone
    timezone       TEXT NOT NULL DEFAULT 'UTC',
    enabled        BOOLEAN NOT NULL DEFAULT true,
    email_to       TEXT,                             -- comma-separated recipients
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_morning_brief_tenant_date
    ON nexus_morning_brief(tenant_schema, brief_date DESC);
