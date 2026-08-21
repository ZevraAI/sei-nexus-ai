-- V040: Async onboarding table-analysis jobs.
--
-- POST /onboarding/analyze used to block the HTTP request for as long as it took
-- to run one AI call per selected table, sequentially, with a frontend progress
-- bar that had no real connection to backend progress. This table backs an async
-- job: the endpoint now returns a job id immediately, tables are analyzed with
-- bounded concurrency, and results land here incrementally (one jsonb_set per
-- completed table) so both polling and a page refresh mid-job can show real,
-- authoritative progress rather than relying solely on the SSE replay buffer.
SET search_path = public;

CREATE TABLE IF NOT EXISTS nexus_onboarding_analysis_job (
    id              VARCHAR(120) PRIMARY KEY,
    tenant_schema   TEXT NOT NULL,
    connection_key  TEXT NOT NULL,
    schema_name     TEXT NOT NULL,
    domain_key      TEXT NOT NULL,
    table_names     TEXT[] NOT NULL,
    status          TEXT NOT NULL DEFAULT 'RUNNING'
                        CHECK (status IN ('RUNNING', 'COMPLETE', 'FAILED')),
    -- Keyed by table_name; each table's entry is written via jsonb_set as soon as
    -- that table's analysis finishes (success or graceful-degradation stub) — not
    -- one write at the end. This is what makes GET .../{id} meaningful mid-job.
    results_json    JSONB NOT NULL DEFAULT '{}',
    tables_done     INT NOT NULL DEFAULT 0,
    tables_total    INT NOT NULL,
    -- sha256(connection_key|schema_name|domain_key|sorted table_names) — lets a
    -- double-submitted request reattach to the existing job instead of starting
    -- a second one.
    request_hash    TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_onboarding_job_tenant_hash
    ON nexus_onboarding_analysis_job(tenant_schema, request_hash, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_onboarding_job_tenant_created
    ON nexus_onboarding_analysis_job(tenant_schema, created_at DESC);
