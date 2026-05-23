-- ── Phase 1: Governance Core ─────────────────────────────────────────────────
-- Column-level masking policies, row-level security, data contracts,
-- and user attribute storage for RLS template resolution.

-- ── Column masking policies ───────────────────────────────────────────────────
-- Controls how individual columns are presented to users based on their role.
-- mask_type values:
--   EXCLUDE  → column removed from SELECT entirely (most restrictive)
--   HASH     → MD5(CAST(col AS TEXT)) — value anonymised but deterministic
--   PARTIAL  → first N chars + '****' — useful for IDs, phone numbers
--   CONSTANT → replaced with a fixed string (e.g. 'REDACTED')
CREATE TABLE IF NOT EXISTS nexus_column_policy (
    id             BIGSERIAL    PRIMARY KEY,
    policy_key     VARCHAR(255) NOT NULL UNIQUE,
    object_key     VARCHAR(255) NOT NULL,          -- nexus_data_object.object_key
    column_name    VARCHAR(255) NOT NULL,
    mask_type      VARCHAR(50)  NOT NULL
                   CHECK (mask_type IN ('EXCLUDE','HASH','PARTIAL','CONSTANT')),
    constant_value VARCHAR(500),                   -- used when mask_type = CONSTANT
    partial_chars  INT          NOT NULL DEFAULT 3, -- chars shown when mask_type = PARTIAL
    exempt_roles   TEXT[]       NOT NULL DEFAULT '{}',
    -- roles listed here see the real value; empty = no exemptions
    created_by     VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_col_policy_object ON nexus_column_policy(object_key);

-- ── Row-level security policies ───────────────────────────────────────────────
-- Automatically appends a WHERE condition to every query touching the table.
-- filter_template supports placeholders resolved at query time:
--   {user.email}        → authenticated user's email
--   {user.role}         → authenticated user's role
--   {user.<attribute>}  → value from nexus_user_account.attributes JSON
--
-- Example templates:
--   "region = {user.region}"
--   "department_code = {user.department} OR is_public = TRUE"
CREATE TABLE IF NOT EXISTS nexus_rls_policy (
    id              BIGSERIAL    PRIMARY KEY,
    policy_key      VARCHAR(255) NOT NULL UNIQUE,
    policy_name     VARCHAR(255) NOT NULL,
    object_key      VARCHAR(255) NOT NULL,
    filter_template TEXT         NOT NULL,
    applies_to_roles TEXT[]      NOT NULL DEFAULT '{}',
    -- empty array = applies to ALL roles (most restrictive default)
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rls_policy_object ON nexus_rls_policy(object_key, is_active);

-- ── Data contracts ────────────────────────────────────────────────────────────
-- Rules that every LLM-generated query against a table must satisfy.
-- rule_type values:
--   REQUIRE_DATE_FILTER   → query must have a WHERE condition on a date column
--   REQUIRE_COLUMN_FILTER → query must filter on a specified column
--   REQUIRE_LIMIT         → query must have a LIMIT clause
--   BLOCK_FULL_SCAN       → reject any SELECT without a WHERE clause
--
-- rule_config JSONB per rule_type:
--   REQUIRE_DATE_FILTER:   {"columns":["created_at","updated_at"],"max_range_days":90}
--   REQUIRE_COLUMN_FILTER: {"column":"tenant_id"}
--   REQUIRE_LIMIT:         {"max_rows":10000}
--   BLOCK_FULL_SCAN:       {} (no config needed)
--
-- enforcement values:
--   BLOCK          → reject query, return explanation to user
--   WARN           → log violation, allow execution
--   AUTO_REMEDIATE → rewrite SQL to add missing constraint and proceed
CREATE TABLE IF NOT EXISTS nexus_data_contract (
    id            BIGSERIAL    PRIMARY KEY,
    contract_key  VARCHAR(255) NOT NULL UNIQUE,
    contract_name VARCHAR(255) NOT NULL,
    object_key    VARCHAR(255) NOT NULL,
    rule_type     VARCHAR(100) NOT NULL
                  CHECK (rule_type IN (
                      'REQUIRE_DATE_FILTER',
                      'REQUIRE_COLUMN_FILTER',
                      'REQUIRE_LIMIT',
                      'BLOCK_FULL_SCAN'
                  )),
    rule_config   JSONB        NOT NULL DEFAULT '{}',
    enforcement   VARCHAR(50)  NOT NULL DEFAULT 'BLOCK'
                  CHECK (enforcement IN ('BLOCK','WARN','AUTO_REMEDIATE')),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by    VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_contract_object ON nexus_data_contract(object_key, is_active);

-- ── User attributes for RLS template resolution ───────────────────────────────
-- Admins set per-user attributes (region, department, cost_centre, etc.)
-- that are substituted into RLS filter_template at query time.
-- This avoids changing the UserAccount record and auth session structure.
ALTER TABLE nexus_user_account
    ADD COLUMN IF NOT EXISTS attributes JSONB NOT NULL DEFAULT '{}';
