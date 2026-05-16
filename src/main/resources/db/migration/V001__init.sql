-- SEI Nexus AI - Initial Schema Migration
-- V001__init.sql

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- DOMAIN
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_domain (
    domain_key          VARCHAR(120)  PRIMARY KEY,
    name                VARCHAR(255)  NOT NULL,
    description         TEXT,
    owner_team          VARCHAR(255),
    owner_email         VARCHAR(255),
    status              VARCHAR(40)   NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ============================================================
-- USER ACCOUNT
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_user_account (
    email               VARCHAR(255)  PRIMARY KEY,
    display_name        VARCHAR(255)  NOT NULL,
    password_hash       VARCHAR(255)  NOT NULL,
    role                VARCHAR(40)   NOT NULL DEFAULT 'ANALYST',
    status              VARCHAR(40)   NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ============================================================
-- USER SESSION
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_user_session (
    session_key         VARCHAR(120)  PRIMARY KEY,
    user_email          VARCHAR(255)  NOT NULL REFERENCES nexus_user_account(email),
    session_token_hash  VARCHAR(255)  NOT NULL UNIQUE,
    expires_at          TIMESTAMPTZ   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_session_token_hash ON nexus_user_session(session_token_hash);
CREATE INDEX IF NOT EXISTS idx_user_session_expires_at ON nexus_user_session(expires_at);

-- ============================================================
-- DOCUMENT
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_document (
    document_key        VARCHAR(120)  PRIMARY KEY,
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    title               VARCHAR(500)  NOT NULL,
    source_uri          TEXT,
    mime_type           VARCHAR(100),
    file_size_bytes     BIGINT,
    page_count          INTEGER,
    status              VARCHAR(40)   NOT NULL DEFAULT 'PROCESSING',
    ingested_by         VARCHAR(255),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_document_domain_key ON nexus_document(domain_key);

-- ============================================================
-- DOCUMENT CHUNK
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_document_chunk (
    chunk_key           VARCHAR(120)  PRIMARY KEY,
    document_key        VARCHAR(120)  NOT NULL REFERENCES nexus_document(document_key),
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    chunk_index         INTEGER       NOT NULL,
    chunk_text          TEXT          NOT NULL,
    word_count          INTEGER,
    embedding           vector(1536),
    token_count         INTEGER,
    page_number         INTEGER,
    section_heading     VARCHAR(500),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chunk_document_key ON nexus_document_chunk(document_key);
CREATE INDEX IF NOT EXISTS idx_chunk_domain_key   ON nexus_document_chunk(domain_key);
CREATE INDEX IF NOT EXISTS idx_chunk_embedding    ON nexus_document_chunk
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ============================================================
-- CONNECTION
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_connection (
    connection_key      VARCHAR(120)  PRIMARY KEY,
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    name                VARCHAR(255)  NOT NULL,
    connection_type     VARCHAR(80)   NOT NULL,
    jdbc_url            TEXT,
    username_enc        TEXT,
    password_enc        TEXT,
    schema_name         VARCHAR(255),
    catalog_name        VARCHAR(255),
    status              VARCHAR(40)   NOT NULL DEFAULT 'ACTIVE',
    last_tested_at      TIMESTAMPTZ,
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_connection_domain_key ON nexus_connection(domain_key);

-- ============================================================
-- AGENT
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_agent (
    agent_key           VARCHAR(120)  PRIMARY KEY,
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    name                VARCHAR(255)  NOT NULL,
    description         TEXT,
    agent_type          VARCHAR(80)   NOT NULL,
    status              VARCHAR(40)   NOT NULL DEFAULT 'ACTIVE',
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_domain_key ON nexus_agent(domain_key);

-- ============================================================
-- AGENT VERSION
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_agent_version (
    version_key         VARCHAR(120)  PRIMARY KEY,
    agent_key           VARCHAR(120)  NOT NULL REFERENCES nexus_agent(agent_key),
    version_number      INTEGER       NOT NULL,
    system_prompt       TEXT,
    config_json         TEXT,
    published_by        VARCHAR(255),
    published_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    is_current          BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_agent_version_agent_key ON nexus_agent_version(agent_key);

-- ============================================================
-- AGENT PLAYBOOK
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_agent_playbook (
    playbook_key        VARCHAR(120)  PRIMARY KEY,
    agent_key           VARCHAR(120)  NOT NULL REFERENCES nexus_agent(agent_key),
    name                VARCHAR(255)  NOT NULL,
    description         TEXT,
    steps_json          TEXT,
    status              VARCHAR(40)   NOT NULL DEFAULT 'ACTIVE',
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_playbook_agent_key ON nexus_agent_playbook(agent_key);

-- ============================================================
-- AGENT KPI
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_agent_kpi (
    kpi_key             VARCHAR(120)  PRIMARY KEY,
    agent_key           VARCHAR(120)  NOT NULL REFERENCES nexus_agent(agent_key),
    kpi_name            VARCHAR(255)  NOT NULL,
    kpi_description     TEXT,
    measurement_query   TEXT,
    target_value        NUMERIC(18,4),
    unit                VARCHAR(80),
    status              VARCHAR(40)   NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kpi_agent_key ON nexus_agent_kpi(agent_key);

-- ============================================================
-- RUN
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_run (
    run_key             VARCHAR(120)  PRIMARY KEY,
    conversation_id     VARCHAR(120)  NOT NULL,
    agent_key           VARCHAR(120),
    domain_key          VARCHAR(120)  REFERENCES nexus_domain(domain_key),
    user_email          VARCHAR(255)  NOT NULL,
    question            TEXT          NOT NULL,
    answer              TEXT,
    decision_type       VARCHAR(80),
    status              VARCHAR(40)   NOT NULL DEFAULT 'PENDING',
    result_snapshot     TEXT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_run_conversation_id ON nexus_run(conversation_id);
CREATE INDEX IF NOT EXISTS idx_run_user_email      ON nexus_run(user_email);
CREATE INDEX IF NOT EXISTS idx_run_domain_key      ON nexus_run(domain_key);
CREATE INDEX IF NOT EXISTS idx_run_created_at      ON nexus_run(created_at);

-- ============================================================
-- CONVERSATION PIN
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_conversation_pin (
    conversation_id     VARCHAR(120)  PRIMARY KEY,
    user_email          VARCHAR(255)  NOT NULL,
    pinned_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    note                TEXT
);

CREATE INDEX IF NOT EXISTS idx_pin_user_email ON nexus_conversation_pin(user_email);

-- ============================================================
-- EVIDENCE
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_evidence (
    evidence_key        VARCHAR(120)  PRIMARY KEY,
    run_key             VARCHAR(120)  NOT NULL REFERENCES nexus_run(run_key),
    evidence_type       VARCHAR(80)   NOT NULL,
    payload_json        TEXT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_evidence_run_key ON nexus_evidence(run_key);

-- ============================================================
-- DATA OBJECT
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_data_object (
    object_key          VARCHAR(120)  PRIMARY KEY,
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    connection_key      VARCHAR(120)  REFERENCES nexus_connection(connection_key),
    object_name         VARCHAR(255)  NOT NULL,
    object_type         VARCHAR(80)   NOT NULL,
    schema_name         VARCHAR(255),
    catalog_name        VARCHAR(255),
    description         TEXT,
    row_count_estimate  BIGINT,
    status              VARCHAR(40)   NOT NULL DEFAULT 'ACTIVE',
    last_profiled_at    TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_data_object_domain_key ON nexus_data_object(domain_key);

-- ============================================================
-- DATA OBJECT VERSION
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_data_object_version (
    version_key         VARCHAR(120)  PRIMARY KEY,
    object_key          VARCHAR(120)  NOT NULL REFERENCES nexus_data_object(object_key),
    version_number      INTEGER       NOT NULL,
    schema_snapshot     TEXT,
    profiled_by         VARCHAR(255),
    profiled_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_data_object_version_object_key ON nexus_data_object_version(object_key);

-- ============================================================
-- DATA COLUMN
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_data_column (
    column_key          VARCHAR(120)  PRIMARY KEY,
    object_key          VARCHAR(120)  NOT NULL REFERENCES nexus_data_object(object_key),
    column_name         VARCHAR(255)  NOT NULL,
    data_type           VARCHAR(120),
    ordinal_position    INTEGER,
    is_nullable         BOOLEAN,
    is_primary_key      BOOLEAN       DEFAULT FALSE,
    description         TEXT,
    sample_values       TEXT,
    cardinality_est     BIGINT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_data_column_object_key ON nexus_data_column(object_key);

-- ============================================================
-- KNOWLEDGE NOTE
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_knowledge_note (
    note_key            VARCHAR(120)  PRIMARY KEY,
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    author_email        VARCHAR(255)  NOT NULL,
    note_type           VARCHAR(80)   NOT NULL DEFAULT 'GENERAL',
    title               VARCHAR(500),
    body                TEXT          NOT NULL,
    tags                TEXT,
    status              VARCHAR(40)   NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_note_domain_key ON nexus_knowledge_note(domain_key);

-- ============================================================
-- KNOWLEDGE GAP
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_knowledge_gap (
    gap_key             VARCHAR(120)  PRIMARY KEY,
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    gap_type            VARCHAR(80)   NOT NULL,
    run_key             VARCHAR(120)  REFERENCES nexus_run(run_key),
    question            TEXT,
    gap_description     TEXT          NOT NULL,
    proposal_text       TEXT,
    status              VARCHAR(40)   NOT NULL DEFAULT 'OPEN',
    resolved_by         VARCHAR(255),
    resolution_note     TEXT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_gap_domain_key ON nexus_knowledge_gap(domain_key);
CREATE INDEX IF NOT EXISTS idx_knowledge_gap_status     ON nexus_knowledge_gap(status);

-- ============================================================
-- INVESTIGATION RECIPE
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_investigation_recipe (
    recipe_key          VARCHAR(120)  PRIMARY KEY,
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    name                VARCHAR(255)  NOT NULL,
    description         TEXT,
    trigger_patterns    TEXT,
    steps_json          TEXT,
    status              VARCHAR(40)   NOT NULL DEFAULT 'ACTIVE',
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_recipe_domain_key ON nexus_investigation_recipe(domain_key);

-- ============================================================
-- INVESTIGATION STEP
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_investigation_step (
    step_key            VARCHAR(120)  PRIMARY KEY,
    recipe_key          VARCHAR(120)  NOT NULL REFERENCES nexus_investigation_recipe(recipe_key),
    step_number         INTEGER       NOT NULL,
    step_type           VARCHAR(80)   NOT NULL,
    step_description    TEXT,
    config_json         TEXT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_inv_step_recipe_key ON nexus_investigation_step(recipe_key);

-- ============================================================
-- QUERY EXECUTION
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_query_execution (
    execution_key       VARCHAR(120)  PRIMARY KEY,
    run_key             VARCHAR(120)  REFERENCES nexus_run(run_key),
    connection_key      VARCHAR(120)  REFERENCES nexus_connection(connection_key),
    sql_text            TEXT          NOT NULL,
    execution_mode      VARCHAR(40)   NOT NULL DEFAULT 'SYNC',
    status              VARCHAR(40)   NOT NULL DEFAULT 'PENDING',
    row_count           INTEGER,
    execution_ms        BIGINT,
    error_message       TEXT,
    result_preview      TEXT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_query_exec_run_key ON nexus_query_execution(run_key);

-- ============================================================
-- BUSINESS ENTITY
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_business_entity (
    entity_key          VARCHAR(120)  PRIMARY KEY,
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    entity_type         VARCHAR(120)  NOT NULL,
    entity_name         VARCHAR(500)  NOT NULL,
    canonical_id        VARCHAR(255),
    attributes_json     TEXT,
    status              VARCHAR(40)   NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_business_entity_domain_key ON nexus_business_entity(domain_key);
CREATE INDEX IF NOT EXISTS idx_business_entity_type       ON nexus_business_entity(entity_type);

-- ============================================================
-- ENTITY LIFECYCLE STATE
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_entity_lifecycle_state (
    state_key           VARCHAR(120)  PRIMARY KEY,
    entity_key          VARCHAR(120)  NOT NULL REFERENCES nexus_business_entity(entity_key),
    state_name          VARCHAR(120)  NOT NULL,
    entered_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    exited_at           TIMESTAMPTZ,
    triggered_by        VARCHAR(255),
    metadata_json       TEXT
);

CREATE INDEX IF NOT EXISTS idx_entity_lifecycle_entity_key ON nexus_entity_lifecycle_state(entity_key);

-- ============================================================
-- ENTITY RELATIONSHIP
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_entity_relationship (
    relationship_key    VARCHAR(120)  PRIMARY KEY,
    source_entity_key   VARCHAR(120)  NOT NULL REFERENCES nexus_business_entity(entity_key),
    target_entity_key   VARCHAR(120)  NOT NULL REFERENCES nexus_business_entity(entity_key),
    relationship_type   VARCHAR(120)  NOT NULL,
    strength            NUMERIC(5,4),
    attributes_json     TEXT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_entity_rel_source ON nexus_entity_relationship(source_entity_key);
CREATE INDEX IF NOT EXISTS idx_entity_rel_target ON nexus_entity_relationship(target_entity_key);

-- ============================================================
-- OPERATIONAL VOCABULARY
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_operational_vocabulary (
    vocab_key           VARCHAR(120)  PRIMARY KEY,
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    term                VARCHAR(255)  NOT NULL,
    definition          TEXT,
    synonyms            TEXT,
    context_note        TEXT,
    status              VARCHAR(40)   NOT NULL DEFAULT 'ACTIVE',
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_vocab_domain_key ON nexus_operational_vocabulary(domain_key);

-- ============================================================
-- ENTITY DATA MAPPING
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_entity_data_mapping (
    mapping_key         VARCHAR(120)  PRIMARY KEY,
    entity_key          VARCHAR(120)  NOT NULL REFERENCES nexus_business_entity(entity_key),
    object_key          VARCHAR(120)  REFERENCES nexus_data_object(object_key),
    column_key          VARCHAR(120)  REFERENCES nexus_data_column(column_key),
    mapping_type        VARCHAR(80)   NOT NULL,
    notes               TEXT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_entity_data_mapping_entity_key ON nexus_entity_data_mapping(entity_key);

-- ============================================================
-- REASONING SESSION
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_reasoning_session (
    reasoning_key       VARCHAR(120)  PRIMARY KEY,
    run_key             VARCHAR(120)  NOT NULL REFERENCES nexus_run(run_key),
    reasoning_mode      VARCHAR(80)   NOT NULL DEFAULT 'CHAIN_OF_THOUGHT',
    status              VARCHAR(40)   NOT NULL DEFAULT 'IN_PROGRESS',
    conclusion          TEXT,
    confidence_score    NUMERIC(5,4),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_reasoning_session_run_key ON nexus_reasoning_session(run_key);

-- ============================================================
-- REASONING STEP
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_reasoning_step (
    step_key            VARCHAR(120)  PRIMARY KEY,
    reasoning_key       VARCHAR(120)  NOT NULL REFERENCES nexus_reasoning_session(reasoning_key),
    step_number         INTEGER       NOT NULL,
    step_type           VARCHAR(80)   NOT NULL,
    thought             TEXT,
    action              TEXT,
    observation         TEXT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_reasoning_step_reasoning_key ON nexus_reasoning_step(reasoning_key);

-- ============================================================
-- HYPOTHESIS
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_hypothesis (
    hypothesis_key      VARCHAR(120)  PRIMARY KEY,
    run_key             VARCHAR(120)  NOT NULL REFERENCES nexus_run(run_key),
    domain_key          VARCHAR(120)  REFERENCES nexus_domain(domain_key),
    hypothesis_text     TEXT          NOT NULL,
    evidence_for        TEXT,
    evidence_against    TEXT,
    confidence_score    NUMERIC(5,4),
    verdict             VARCHAR(40),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_hypothesis_run_key ON nexus_hypothesis(run_key);

-- ============================================================
-- OPERATIONAL FINDING
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_operational_finding (
    finding_key         VARCHAR(120)  PRIMARY KEY,
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    run_key             VARCHAR(120)  REFERENCES nexus_run(run_key),
    finding_type        VARCHAR(80)   NOT NULL,
    severity            VARCHAR(40)   NOT NULL DEFAULT 'INFO',
    title               VARCHAR(500)  NOT NULL,
    description         TEXT,
    recommendation      TEXT,
    status              VARCHAR(40)   NOT NULL DEFAULT 'OPEN',
    resolved_by         VARCHAR(255),
    resolved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_finding_domain_key ON nexus_operational_finding(domain_key);
CREATE INDEX IF NOT EXISTS idx_finding_status     ON nexus_operational_finding(status);

-- ============================================================
-- OPERATIONAL BASELINE
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_operational_baseline (
    baseline_key        VARCHAR(120)  PRIMARY KEY,
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    metric_name         VARCHAR(255)  NOT NULL,
    metric_description  TEXT,
    baseline_value      NUMERIC(18,4),
    unit                VARCHAR(80),
    computed_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    valid_from          TIMESTAMPTZ,
    valid_to            TIMESTAMPTZ,
    computed_by         VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_baseline_domain_key ON nexus_operational_baseline(domain_key);

-- ============================================================
-- ANOMALY EVENT
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_anomaly_event (
    anomaly_key         VARCHAR(120)  PRIMARY KEY,
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    baseline_key        VARCHAR(120)  REFERENCES nexus_operational_baseline(baseline_key),
    detected_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    anomaly_type        VARCHAR(80)   NOT NULL,
    observed_value      NUMERIC(18,4),
    expected_value      NUMERIC(18,4),
    deviation_pct       NUMERIC(8,4),
    severity            VARCHAR(40)   NOT NULL DEFAULT 'WARNING',
    description         TEXT,
    status              VARCHAR(40)   NOT NULL DEFAULT 'OPEN',
    resolved_at         TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_anomaly_domain_key  ON nexus_anomaly_event(domain_key);
CREATE INDEX IF NOT EXISTS idx_anomaly_detected_at ON nexus_anomaly_event(detected_at);

-- ============================================================
-- AUDIT EVENT
-- ============================================================
CREATE TABLE IF NOT EXISTS nexus_audit_event (
    audit_key           VARCHAR(120)  PRIMARY KEY,
    event_type          VARCHAR(120)  NOT NULL,
    actor_email         VARCHAR(255),
    domain_key          VARCHAR(120),
    resource_type       VARCHAR(120),
    resource_key        VARCHAR(120),
    action              VARCHAR(80)   NOT NULL,
    details_json        TEXT,
    ip_address          VARCHAR(80),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_event_actor     ON nexus_audit_event(actor_email);
CREATE INDEX IF NOT EXISTS idx_audit_event_domain    ON nexus_audit_event(domain_key);
CREATE INDEX IF NOT EXISTS idx_audit_event_created   ON nexus_audit_event(created_at);
CREATE INDEX IF NOT EXISTS idx_audit_event_type      ON nexus_audit_event(event_type);
