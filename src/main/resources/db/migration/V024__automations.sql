-- Zevra Automations: visual workflow builder + execution history
-- Workflows are stored as JSONB node/edge graphs; executions record per-step traces.

CREATE TABLE IF NOT EXISTS nexus_automation_workflow (
    id              VARCHAR(120) PRIMARY KEY,
    tenant_schema   VARCHAR(120) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    slug            VARCHAR(120) NOT NULL,           -- URL-safe trigger key
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
    trigger_type    VARCHAR(40)  NOT NULL DEFAULT 'WEBHOOK'
                        CHECK (trigger_type IN ('WEBHOOK','MANUAL')),
    graph           JSONB        NOT NULL DEFAULT '{"nodes":[],"edges":[]}',
    created_by      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_workflow_tenant_slug
    ON nexus_automation_workflow (tenant_schema, slug);

CREATE INDEX IF NOT EXISTS idx_workflow_tenant_status
    ON nexus_automation_workflow (tenant_schema, status);

-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS nexus_automation_execution (
    id              VARCHAR(120) PRIMARY KEY,
    workflow_id     VARCHAR(120) NOT NULL
                        REFERENCES nexus_automation_workflow(id) ON DELETE CASCADE,
    tenant_schema   VARCHAR(120) NOT NULL,
    trigger_payload JSONB        NOT NULL DEFAULT '{}',
    status          VARCHAR(20)  NOT NULL DEFAULT 'RUNNING'
                        CHECK (status IN ('RUNNING','SUCCESS','FAILED','TIMEOUT')),
    step_traces     JSONB        NOT NULL DEFAULT '[]',   -- array of step trace objects
    final_output    JSONB,
    error_message   TEXT,
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_execution_workflow_id
    ON nexus_automation_execution (workflow_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_execution_tenant_status
    ON nexus_automation_execution (tenant_schema, status, started_at DESC);
