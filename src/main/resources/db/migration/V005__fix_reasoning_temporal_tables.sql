-- V005: Drop and recreate reasoning/temporal tables whose V001 schemas don't
--       match the current Java repositories. Dev environment only — no data loss risk.

-- ── Drop in FK-safe order ─────────────────────────────────────────────────────
DROP TABLE IF EXISTS nexus_hypothesis            CASCADE;
DROP TABLE IF EXISTS nexus_reasoning_step        CASCADE;
DROP TABLE IF EXISTS nexus_reasoning_session     CASCADE;
DROP TABLE IF EXISTS nexus_anomaly_event         CASCADE;
DROP TABLE IF EXISTS nexus_operational_finding   CASCADE;
DROP TABLE IF EXISTS nexus_operational_baseline  CASCADE;

-- ── nexus_reasoning_session ──────────────────────────────────────────────────
CREATE TABLE nexus_reasoning_session (
    session_key         VARCHAR(120)    PRIMARY KEY,
    run_key             VARCHAR(120)    NOT NULL REFERENCES nexus_run(run_key),
    conversation_id     VARCHAR(120)    NOT NULL,
    agent_key           VARCHAR(120),
    domain_key          VARCHAR(120),
    initial_question    TEXT            NOT NULL,
    investigation_plan  TEXT,
    status              VARCHAR(40)     NOT NULL DEFAULT 'ACTIVE',
    conclusion          TEXT,
    confidence_score    NUMERIC(5,4),
    started_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    concluded_at        TIMESTAMPTZ
);

CREATE INDEX idx_reasoning_session_run  ON nexus_reasoning_session(run_key);
CREATE INDEX idx_reasoning_session_conv ON nexus_reasoning_session(conversation_id, started_at DESC);

-- ── nexus_reasoning_step ─────────────────────────────────────────────────────
CREATE TABLE nexus_reasoning_step (
    step_key        VARCHAR(120)    PRIMARY KEY,
    session_key     VARCHAR(120)    NOT NULL REFERENCES nexus_reasoning_session(session_key) ON DELETE CASCADE,
    step_no         INTEGER         NOT NULL,
    step_type       VARCHAR(80)     NOT NULL,
    instruction     TEXT,
    evidence_used   TEXT,
    outcome         TEXT,
    confidence_delta NUMERIC(5,4),
    execution_key   VARCHAR(120),
    executed_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    UNIQUE (session_key, step_no)
);

CREATE INDEX idx_reasoning_step_session ON nexus_reasoning_step(session_key, step_no);

-- ── nexus_hypothesis ─────────────────────────────────────────────────────────
CREATE TABLE nexus_hypothesis (
    hypothesis_key          VARCHAR(120)    PRIMARY KEY,
    session_key             VARCHAR(120)    NOT NULL REFERENCES nexus_reasoning_session(session_key) ON DELETE CASCADE,
    hypothesis_text         TEXT            NOT NULL,
    confidence              NUMERIC(5,4)    NOT NULL DEFAULT 0.500,
    supporting_evidence     TEXT,
    contradicting_evidence  TEXT,
    status                  VARCHAR(40)     NOT NULL DEFAULT 'ACTIVE',
    formed_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    resolved_at             TIMESTAMPTZ
);

CREATE INDEX idx_hypothesis_session ON nexus_hypothesis(session_key, status);

-- ── nexus_operational_finding ────────────────────────────────────────────────
CREATE TABLE nexus_operational_finding (
    finding_key         VARCHAR(120)    PRIMARY KEY,
    domain_key          VARCHAR(120)    NOT NULL REFERENCES nexus_domain(domain_key),
    agent_key           VARCHAR(120),
    finding_type        VARCHAR(80)     NOT NULL,
    title               VARCHAR(512)    NOT NULL,
    description         TEXT,
    evidence_summary    TEXT,
    related_entity_keys TEXT,
    confidence          NUMERIC(5,4),
    status              VARCHAR(40)     NOT NULL DEFAULT 'ACTIVE',
    first_observed_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    last_confirmed_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    resolved_at         TIMESTAMPTZ
);

CREATE INDEX idx_operational_finding_domain ON nexus_operational_finding(domain_key, status);
CREATE INDEX idx_operational_finding_agent  ON nexus_operational_finding(agent_key, status);

-- ── nexus_operational_baseline ───────────────────────────────────────────────
CREATE TABLE nexus_operational_baseline (
    baseline_key        VARCHAR(120)    PRIMARY KEY,
    domain_key          VARCHAR(120)    NOT NULL REFERENCES nexus_domain(domain_key),
    agent_key           VARCHAR(120),
    kpi_key             VARCHAR(120),
    metric_name         VARCHAR(255)    NOT NULL,
    measurement_sql     TEXT            NOT NULL,
    connection_key      VARCHAR(120),
    current_value       NUMERIC(18,4),
    baseline_avg        NUMERIC(18,4),
    baseline_stddev     NUMERIC(18,4),
    measurement_window  VARCHAR(32)     NOT NULL DEFAULT 'DAILY',
    trend_data          TEXT            NOT NULL DEFAULT '[]',
    last_computed_at    TIMESTAMPTZ,
    next_due_at         TIMESTAMPTZ,
    status              VARCHAR(40)     NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_baseline_domain   ON nexus_operational_baseline(domain_key, status);
CREATE INDEX idx_baseline_next_due ON nexus_operational_baseline(next_due_at) WHERE status = 'ACTIVE';

-- ── nexus_anomaly_event ──────────────────────────────────────────────────────
CREATE TABLE nexus_anomaly_event (
    anomaly_key     VARCHAR(120)    PRIMARY KEY,
    baseline_key    VARCHAR(120)    NOT NULL REFERENCES nexus_operational_baseline(baseline_key),
    domain_key      VARCHAR(120)    NOT NULL REFERENCES nexus_domain(domain_key),
    entity_key      VARCHAR(120),
    detected_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    metric_name     VARCHAR(255),
    baseline_value  NUMERIC(18,4),
    observed_value  NUMERIC(18,4),
    deviation_pct   NUMERIC(8,2),
    deviation_stddev NUMERIC(8,2),
    severity        VARCHAR(16)     NOT NULL DEFAULT 'MEDIUM',
    status          VARCHAR(32)     NOT NULL DEFAULT 'OPEN',
    finding_key     VARCHAR(120)    REFERENCES nexus_operational_finding(finding_key)
);

CREATE INDEX idx_anomaly_domain   ON nexus_anomaly_event(domain_key, status, detected_at DESC);
CREATE INDEX idx_anomaly_baseline ON nexus_anomaly_event(baseline_key, detected_at DESC);
