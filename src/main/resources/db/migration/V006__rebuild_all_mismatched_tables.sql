-- V006: Drop and rebuild every table whose V001 schema doesn't match the current
--       Java repositories. CASCADE handles FK dependencies automatically.
--       nexus_connection / nexus_run / nexus_evidence / nexus_document* are
--       intentionally excluded — those paths are already working.

-- ── Drop everything that needs rebuilding ────────────────────────────────────
DROP TABLE IF EXISTS nexus_query_execution        CASCADE;
DROP TABLE IF EXISTS nexus_entity_data_mapping    CASCADE;
DROP TABLE IF EXISTS nexus_operational_vocabulary CASCADE;
DROP TABLE IF EXISTS nexus_entity_relationship    CASCADE;
DROP TABLE IF EXISTS nexus_entity_lifecycle_state CASCADE;
DROP TABLE IF EXISTS nexus_business_entity        CASCADE;
DROP TABLE IF EXISTS nexus_agent_kpi              CASCADE;
DROP TABLE IF EXISTS nexus_agent_playbook         CASCADE;
DROP TABLE IF EXISTS nexus_agent_version          CASCADE;
DROP TABLE IF EXISTS nexus_agent                  CASCADE;
DROP TABLE IF EXISTS nexus_data_column            CASCADE;
DROP TABLE IF EXISTS nexus_data_object_version    CASCADE;
DROP TABLE IF EXISTS nexus_data_object            CASCADE;

-- ── nexus_agent ──────────────────────────────────────────────────────────────
CREATE TABLE nexus_agent (
    agent_key        VARCHAR(120)  PRIMARY KEY,
    name             VARCHAR(255)  NOT NULL,
    purpose          TEXT          NOT NULL,
    domain_keys      TEXT,
    connection_keys  TEXT,
    rest_api_enabled BOOLEAN       NOT NULL DEFAULT FALSE,
    action_scope     VARCHAR(32)   NOT NULL DEFAULT 'READ_ONLY',
    version_no       INT           NOT NULL DEFAULT 1,
    status           VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
    created_by       VARCHAR(255),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_agent_status ON nexus_agent(status);

-- ── nexus_agent_version ──────────────────────────────────────────────────────
CREATE TABLE nexus_agent_version (
    version_key  VARCHAR(120)  PRIMARY KEY,
    agent_key    VARCHAR(120)  NOT NULL REFERENCES nexus_agent(agent_key) ON DELETE CASCADE,
    version_no   INT           NOT NULL,
    snapshot     TEXT          NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (agent_key, version_no)
);

CREATE INDEX idx_agent_version_agent ON nexus_agent_version(agent_key, version_no DESC);

-- ── nexus_agent_playbook ─────────────────────────────────────────────────────
CREATE TABLE nexus_agent_playbook (
    playbook_key             VARCHAR(120)    PRIMARY KEY,
    agent_key                VARCHAR(120)    NOT NULL REFERENCES nexus_agent(agent_key) ON DELETE CASCADE,
    name                     VARCHAR(255)    NOT NULL,
    trigger_conditions       TEXT,
    investigation_steps      TEXT,
    escalation_rules         TEXT,
    confidence_threshold     NUMERIC(5,4),
    preferred_evidence_order TEXT,
    max_investigation_steps  INT             NOT NULL DEFAULT 8,
    status                   VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_playbook_agent ON nexus_agent_playbook(agent_key);

-- ── nexus_agent_kpi ──────────────────────────────────────────────────────────
CREATE TABLE nexus_agent_kpi (
    kpi_key                VARCHAR(120)    PRIMARY KEY,
    agent_key              VARCHAR(120)    NOT NULL REFERENCES nexus_agent(agent_key) ON DELETE CASCADE,
    domain_key             VARCHAR(120)    NOT NULL REFERENCES nexus_domain(domain_key),
    kpi_name               VARCHAR(255)    NOT NULL,
    kpi_description        TEXT,
    measurement_object_key VARCHAR(120),
    measurement_sql        TEXT,
    threshold_warning      NUMERIC(18,4),
    threshold_critical     NUMERIC(18,4),
    higher_is_better       BOOLEAN         NOT NULL DEFAULT TRUE,
    refresh_interval_hrs   INT             NOT NULL DEFAULT 24,
    status                 VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    created_at             TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_kpi_agent  ON nexus_agent_kpi(agent_key);
CREATE INDEX idx_kpi_domain ON nexus_agent_kpi(domain_key);

-- ── nexus_data_object ────────────────────────────────────────────────────────
CREATE TABLE nexus_data_object (
    object_key           VARCHAR(120)    PRIMARY KEY,
    domain_key           VARCHAR(120)    NOT NULL REFERENCES nexus_domain(domain_key),
    entity_name          VARCHAR(255),
    connection_key       VARCHAR(120),
    schema_name          VARCHAR(128),
    table_name           VARCHAR(255)    NOT NULL,
    business_name        VARCHAR(512),
    purpose              TEXT,
    identifier_columns   TEXT,
    status_columns       TEXT,
    exception_columns    TEXT,
    safe_filter_columns  TEXT,
    usage_guidance       TEXT,
    filter_guidance      TEXT,
    avoid_guidance       TEXT,
    row_limit            INT             NOT NULL DEFAULT 100,
    large_table          BOOLEAN         NOT NULL DEFAULT FALSE,
    scan_status          VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    version_no           INT             NOT NULL DEFAULT 1,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_data_object_domain     ON nexus_data_object(domain_key);
CREATE INDEX idx_data_object_connection ON nexus_data_object(connection_key);
CREATE INDEX idx_data_object_table      ON nexus_data_object(connection_key, schema_name, table_name);

-- ── nexus_data_object_version ────────────────────────────────────────────────
CREATE TABLE nexus_data_object_version (
    version_key   VARCHAR(120)    PRIMARY KEY,
    object_key    VARCHAR(120)    NOT NULL REFERENCES nexus_data_object(object_key) ON DELETE CASCADE,
    version_no    INT             NOT NULL,
    snapshot_json JSONB,
    reason        VARCHAR(512),
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    UNIQUE (object_key, version_no)
);

CREATE INDEX idx_data_object_version_object ON nexus_data_object_version(object_key, version_no DESC);

-- ── nexus_data_column ────────────────────────────────────────────────────────
CREATE TABLE nexus_data_column (
    column_key       VARCHAR(120)    PRIMARY KEY,
    object_key       VARCHAR(120)    NOT NULL REFERENCES nexus_data_object(object_key) ON DELETE CASCADE,
    column_name      VARCHAR(255)    NOT NULL,
    data_type        VARCHAR(128),
    is_nullable      BOOLEAN         NOT NULL DEFAULT TRUE,
    business_meaning TEXT,
    is_identifier    BOOLEAN         NOT NULL DEFAULT FALSE,
    is_status        BOOLEAN         NOT NULL DEFAULT FALSE,
    is_error         BOOLEAN         NOT NULL DEFAULT FALSE,
    is_sensitive     BOOLEAN         NOT NULL DEFAULT FALSE,
    is_filterable    BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    UNIQUE (object_key, column_name)
);

CREATE INDEX idx_data_column_object ON nexus_data_column(object_key);

-- ── nexus_business_entity ────────────────────────────────────────────────────
CREATE TABLE nexus_business_entity (
    entity_key          VARCHAR(120)    PRIMARY KEY,
    domain_key          VARCHAR(120)    NOT NULL REFERENCES nexus_domain(domain_key),
    entity_name         VARCHAR(255)    NOT NULL,
    description         TEXT,
    primary_object_key  VARCHAR(120),
    operational_meaning TEXT,
    investigation_hints TEXT,
    status              VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_business_entity_domain ON nexus_business_entity(domain_key, status);

-- ── nexus_entity_lifecycle_state ─────────────────────────────────────────────
CREATE TABLE nexus_entity_lifecycle_state (
    state_key       VARCHAR(120)    PRIMARY KEY,
    entity_key      VARCHAR(120)    NOT NULL REFERENCES nexus_business_entity(entity_key) ON DELETE CASCADE,
    state_name      VARCHAR(128)    NOT NULL,
    state_code      VARCHAR(128),
    meaning         TEXT,
    is_terminal     BOOLEAN         NOT NULL DEFAULT FALSE,
    is_exception    BOOLEAN         NOT NULL DEFAULT FALSE,
    normal_sequence INT,
    next_states     TEXT,
    detection_rule  TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_lifecycle_entity ON nexus_entity_lifecycle_state(entity_key);

-- ── nexus_entity_relationship ────────────────────────────────────────────────
CREATE TABLE nexus_entity_relationship (
    relationship_key    VARCHAR(120)    PRIMARY KEY,
    source_entity_key   VARCHAR(120)    NOT NULL REFERENCES nexus_business_entity(entity_key) ON DELETE CASCADE,
    target_entity_key   VARCHAR(120)    NOT NULL REFERENCES nexus_business_entity(entity_key) ON DELETE CASCADE,
    relationship_type   VARCHAR(64)     NOT NULL,
    source_column       VARCHAR(255),
    target_column       VARCHAR(255),
    join_guidance       TEXT,
    cross_system        BOOLEAN         NOT NULL DEFAULT FALSE,
    identity_resolution TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_entity_rel_source ON nexus_entity_relationship(source_entity_key);
CREATE INDEX idx_entity_rel_target ON nexus_entity_relationship(target_entity_key);

-- ── nexus_operational_vocabulary ─────────────────────────────────────────────
CREATE TABLE nexus_operational_vocabulary (
    term_key     VARCHAR(120)    PRIMARY KEY,
    domain_key   VARCHAR(120)    NOT NULL REFERENCES nexus_domain(domain_key),
    entity_key   VARCHAR(120)    REFERENCES nexus_business_entity(entity_key) ON DELETE SET NULL,
    term         VARCHAR(255)    NOT NULL,
    definition   TEXT,
    sql_equivalent TEXT,
    examples     TEXT,
    status       VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vocab_domain ON nexus_operational_vocabulary(domain_key, status);

-- ── nexus_entity_data_mapping ────────────────────────────────────────────────
CREATE TABLE nexus_entity_data_mapping (
    mapping_key      VARCHAR(120)    PRIMARY KEY,
    entity_key       VARCHAR(120)    NOT NULL REFERENCES nexus_business_entity(entity_key) ON DELETE CASCADE,
    object_key       VARCHAR(120)    REFERENCES nexus_data_object(object_key) ON DELETE SET NULL,
    field_mappings   TEXT,
    identity_columns TEXT,
    is_primary       BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_entity_mapping_entity ON nexus_entity_data_mapping(entity_key);

-- ── nexus_query_execution ────────────────────────────────────────────────────
CREATE TABLE nexus_query_execution (
    execution_key   VARCHAR(120)    PRIMARY KEY,
    run_key         VARCHAR(120)    NOT NULL REFERENCES nexus_run(run_key) ON DELETE CASCADE,
    step_no         INT             NOT NULL DEFAULT 1,
    connection_key  VARCHAR(120),
    object_keys     TEXT,
    classification  VARCHAR(64),
    route           VARCHAR(32),
    risk_level      VARCHAR(16),
    status          VARCHAR(32)     NOT NULL DEFAULT 'PLANNED',
    estimated_rows  BIGINT,
    estimated_cost  BIGINT,
    timeout_seconds INT,
    row_limit       INT,
    original_sql    TEXT,
    approved_sql    TEXT,
    decision_reason TEXT,
    error_message   TEXT,
    result_json     TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_query_exec_run    ON nexus_query_execution(run_key);
CREATE INDEX idx_query_exec_status ON nexus_query_execution(status, created_at DESC);
