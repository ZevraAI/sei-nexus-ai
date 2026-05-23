-- ── Phase 3: Semantic Learning ────────────────────────────────────────────────
-- Three tables that record what Zevra learns from team usage:
--   nexus_learned_mapping  — business term → SQL pattern pairs
--   nexus_correction       — detected user corrections for context injection
--   nexus_common_query     — canonical questions the team asks repeatedly

-- ── Learned term → SQL pattern mappings ──────────────────────────────────────
-- Each row says: "in this team's context, <business_term> means <sql_pattern>".
-- Confidence starts at 0.5 and drifts up/down based on reinforcement signals.
-- When confidence >= 0.8 AND use_count >= 10 the term is promoted to the
-- formal nexus_operational_vocabulary (visible in the Semantic Layer UI).
CREATE TABLE IF NOT EXISTS nexus_learned_mapping (
    id              BIGSERIAL    PRIMARY KEY,
    mapping_key     VARCHAR(255) NOT NULL UNIQUE,
    domain_key      VARCHAR(255),             -- null = applies to all domains in this tenant
    business_term   VARCHAR(500) NOT NULL,
    sql_pattern     TEXT         NOT NULL,    -- e.g. "status = 'DELAYED' AND eta < NOW()"
    source_run_key  VARCHAR(255),
    source          VARCHAR(50)  NOT NULL     -- QUERY_SUCCESS | USER_CORRECTION | POSITIVE_FEEDBACK
                    CHECK (source IN ('QUERY_SUCCESS','USER_CORRECTION','POSITIVE_FEEDBACK')),
    confidence      FLOAT        NOT NULL DEFAULT 0.5
                    CHECK (confidence >= 0.0 AND confidence <= 1.0),
    use_count       INT          NOT NULL DEFAULT 0,
    last_used_at    TIMESTAMPTZ,
    promoted        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Two partial unique indexes to handle nullable domain_key correctly:
-- (NULL, 'same term') and (NULL, 'same term') must be treated as duplicates.
CREATE UNIQUE INDEX IF NOT EXISTS idx_learned_term_with_domain
    ON nexus_learned_mapping(domain_key, business_term)
    WHERE domain_key IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_learned_term_no_domain
    ON nexus_learned_mapping(business_term)
    WHERE domain_key IS NULL;

CREATE INDEX IF NOT EXISTS idx_learned_mapping_domain
    ON nexus_learned_mapping(domain_key, confidence DESC, use_count DESC);

-- ── User corrections ──────────────────────────────────────────────────────────
-- Recorded when the CorrectionDetector identifies a follow-up question that
-- contradicts or refines the previous answer.  Used to:
--   1. Decrease confidence on the related learned mapping.
--   2. Inject "Known corrections" into future SQL planning prompts.
CREATE TABLE IF NOT EXISTS nexus_correction (
    id                       BIGSERIAL    PRIMARY KEY,
    correction_key           VARCHAR(255) NOT NULL UNIQUE,
    conversation_id          VARCHAR(255),
    original_run_key         VARCHAR(255),
    correction_run_key       VARCHAR(255),
    original_interpretation  TEXT,
    corrected_interpretation TEXT,
    correction_type          VARCHAR(100)          -- TIMEFRAME | ENTITY | FILTER | METRIC | DIRECTION
                             CHECK (correction_type IN (
                                 'TIMEFRAME','ENTITY','FILTER','METRIC','DIRECTION','OTHER'
                             )),
    applied_to_context       BOOLEAN      NOT NULL DEFAULT FALSE,
    extracted_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_correction_conversation
    ON nexus_correction(conversation_id, extracted_at DESC);

-- ── Common query clusters ─────────────────────────────────────────────────────
-- Canonical forms of questions the team asks repeatedly.  When a question
-- matches a cluster with high success_count and a representative_sql,
-- the planner receives that SQL as a strong hint.
CREATE TABLE IF NOT EXISTS nexus_common_query (
    id                   BIGSERIAL    PRIMARY KEY,
    query_key            VARCHAR(255) NOT NULL UNIQUE,
    domain_key           VARCHAR(255),
    canonical_question   TEXT         NOT NULL,
    example_questions    TEXT[]       NOT NULL DEFAULT '{}',
    representative_sql   TEXT,
    success_count        INT          NOT NULL DEFAULT 0,
    avg_user_rating      FLOAT,
    last_run_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_common_query_domain
    ON nexus_common_query(domain_key, success_count DESC);
