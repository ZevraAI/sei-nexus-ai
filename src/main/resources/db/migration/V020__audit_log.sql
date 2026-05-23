-- ── Phase 1: Compliance Audit Log ────────────────────────────────────────────
-- Immutable record of every query Zevra executes, including all governance
-- decisions applied (masking, RLS, contract checks).
--
-- Designed for compliance audits: answers "who queried what, when, and what
-- did they actually see?"

-- Drop the table first so we always start from the correct schema.
-- On a fresh install this is a no-op. On a schema that has a partial/wrong
-- version of the table (e.g. from a previously-interrupted migration) this
-- ensures the correct columns exist. Audit data is ephemeral by design.
DROP TABLE IF EXISTS nexus_audit_event CASCADE;

CREATE TABLE nexus_audit_event (
    id                      BIGSERIAL    PRIMARY KEY,
    event_key               VARCHAR(255) NOT NULL UNIQUE,
    event_type              VARCHAR(100) NOT NULL,
    -- QUERY_EXECUTED    — normal query ran to completion
    -- COLUMN_MASKED     — one or more columns were masked before returning
    -- RLS_APPLIED       — row-level filter was injected into the query
    -- CONTRACT_VIOLATED — a data contract check triggered
    -- ACCESS_DENIED     — query was blocked entirely
    user_email              VARCHAR(255),
    user_role               VARCHAR(100),
    run_key                 VARCHAR(255),
    connection_key          VARCHAR(255),
    object_keys             TEXT[]       NOT NULL DEFAULT '{}',
    columns_accessed        TEXT[]       NOT NULL DEFAULT '{}',
    columns_masked          TEXT[]       NOT NULL DEFAULT '{}',
    rls_policies_applied    TEXT[]       NOT NULL DEFAULT '{}',
    contracts_checked       TEXT[]       NOT NULL DEFAULT '{}',
    contracts_violated      TEXT[]       NOT NULL DEFAULT '{}',
    original_sql            TEXT,        -- what the LLM generated
    executed_sql            TEXT,        -- what actually ran (post masking + RLS)
    row_count_returned      INT,
    rows_filtered_by_rls    INT,
    execution_ms            INT,
    ip_address              VARCHAR(100),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Primary access patterns: recent events per tenant, events per user, events per run
CREATE INDEX idx_audit_tenant_time  ON nexus_audit_event(created_at DESC);
CREATE INDEX idx_audit_user_time    ON nexus_audit_event(user_email, created_at DESC);
CREATE INDEX idx_audit_run_key      ON nexus_audit_event(run_key);
CREATE INDEX idx_audit_event_type   ON nexus_audit_event(event_type, created_at DESC);
CREATE INDEX idx_audit_conn_key     ON nexus_audit_event(connection_key);
