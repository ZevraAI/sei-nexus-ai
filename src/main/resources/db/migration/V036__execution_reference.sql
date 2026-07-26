-- V036: Execution Continuity — ExecutionReference (what actually executed)
--
-- Immutable, factual record produced by GovernedSqlRuntime after a successful execution.
-- Tenant-schema resident like all nexus_* metadata: the migration runs in every tenant schema,
-- and TenantAwareDataSource routing keeps rows tenant-isolated.
--
-- Holds only intrinsic execution facts + a semantic snapshot copied verbatim from the
-- ExecutionContract. Contains NO comparisons, deltas, or derived interpretation — those are
-- AgentBrain's responsibility, computed from two references, never persisted here.
--
-- parent_execution_id is supplied by AgentBrain (lineage); Runtime records it verbatim.

CREATE TABLE IF NOT EXISTS nexus_execution_reference (
    execution_id         VARCHAR(120)  PRIMARY KEY,
    parent_execution_id  VARCHAR(120),
    conversation_id      VARCHAR(120),
    run_key              VARCHAR(120),
    connection_key       VARCHAR(120),
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    execution_ms         BIGINT,
    governance_outcome   VARCHAR(64),
    row_count            INTEGER       NOT NULL DEFAULT 0,
    result_columns       JSONB,
    result_json          JSONB,
    sql_reference        TEXT,
    contract_id          VARCHAR(120),
    semantic_hash        VARCHAR(160),
    retrieval_targets    JSONB,
    object_bindings      JSONB,
    attribute_bindings   JSONB,
    approved_assets      JSONB,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Follow-up grounding fetches the latest reference for a conversation.
CREATE INDEX IF NOT EXISTS idx_execref_conversation
    ON nexus_execution_reference(conversation_id, created_at DESC);
