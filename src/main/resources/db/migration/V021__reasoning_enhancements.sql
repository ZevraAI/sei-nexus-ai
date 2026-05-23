-- ── Phase 2: Multi-step Reasoning Engine ─────────────────────────────────────
-- Extends existing reasoning tables to carry the evidence context and evaluation
-- decisions produced by the iterative ReAct-style reasoning loop.

ALTER TABLE nexus_reasoning_step
    ADD COLUMN IF NOT EXISTS input_evidence_summary TEXT,
    -- compact statistical summary of all prior steps' results fed to this step's planner
    ADD COLUMN IF NOT EXISTS step_output_json        JSONB,
    -- up to 200 rows returned by this step (for replay and frontend display)
    ADD COLUMN IF NOT EXISTS planner_rationale       TEXT,
    -- why the planner chose this particular SQL
    ADD COLUMN IF NOT EXISTS evaluator_decision      VARCHAR(50),
    -- SUFFICIENT | NEED_MORE_DATA | NEED_DIFFERENT_APPROACH | DEAD_END
    ADD COLUMN IF NOT EXISTS evaluator_rationale     TEXT;

ALTER TABLE nexus_reasoning_session
    ADD COLUMN IF NOT EXISTS step_count            INT     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_rows_processed  INT     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cross_source          BOOLEAN NOT NULL DEFAULT FALSE;
    -- true when the session queried more than one database connection
