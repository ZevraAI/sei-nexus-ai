-- V037: Business Values — a semantic layer over the existing Value Domains.
--
-- Two new tenant-owned metadata objects, additive only. Runs in every tenant schema; the
-- TenantAwareDataSource search_path keeps every row tenant-isolated (no shared/global metadata).
--
-- Physical values remain owned by nexus_value_domain.domain_values (a JSON array). These tables
-- add NO new physical-value storage: a mapping references a physical value by its natural key
-- (value_domain_key, physical_value) — an element already present in the domain's value set.
--
--   Business Attribute → Value Domain → Observed Physical Value → Business Value
--
-- Runtime, LiteralValidator, and the Execution Contract are untouched: Business Values are
-- semantic metadata that reach only the prompt, never execution.

-- ── Canonical business concept (e.g. Draft, Submitted, Approved, Closed) ─────────────────────
CREATE TABLE IF NOT EXISTS nexus_business_value (
    business_value_key      VARCHAR(120) PRIMARY KEY,
    business_attribute_key  VARCHAR(120),                       -- logical attribute this concept belongs to (scoping; prevents homonym merges)
    name                    VARCHAR(255) NOT NULL,              -- canonical concept, e.g. 'Draft'
    description             TEXT,
    source                  VARCHAR(16)  NOT NULL DEFAULT 'AI', -- AI | MANUAL
    confidence              NUMERIC(4,3),                       -- 0.000..1.000, nullable
    approval_status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING | APPROVED | REJECTED
    created_by              VARCHAR(255),
    approved_by             VARCHAR(255),
    approved_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- A concept name is unique within its business attribute (duplicate-concept prevention).
    CONSTRAINT uq_business_value_attr_name UNIQUE (business_attribute_key, name)
);

CREATE INDEX IF NOT EXISTS idx_business_value_attribute
    ON nexus_business_value(business_attribute_key);

-- ── Semantic interpretation of one observed physical value ───────────────────────────────────
CREATE TABLE IF NOT EXISTS nexus_business_value_mapping (
    mapping_key             VARCHAR(120) PRIMARY KEY,
    value_domain_key        VARCHAR(120) NOT NULL,              -- existing nexus_value_domain
    physical_value          VARCHAR(255) NOT NULL,              -- natural key within the domain (an element of domain_values)
    business_value_key      VARCHAR(120) NOT NULL,
    source                  VARCHAR(16)  NOT NULL DEFAULT 'AI', -- AI | MANUAL
    confidence              NUMERIC(4,3),
    approval_status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    cross_application       BOOLEAN      NOT NULL DEFAULT FALSE,-- governance: cross-app mappings require customer approval
    created_by              VARCHAR(255),
    approved_by             VARCHAR(255),
    approved_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Exactly one Business Value per (domain, physical value) — deterministic physical→canonical.
    CONSTRAINT uq_bvmapping_domain_value UNIQUE (value_domain_key, physical_value),
    -- One Business Value may map to many physical values (no constraint against that).
    CONSTRAINT fk_bvmapping_business_value FOREIGN KEY (business_value_key)
        REFERENCES nexus_business_value(business_value_key) ON DELETE CASCADE,
    CONSTRAINT fk_bvmapping_value_domain FOREIGN KEY (value_domain_key)
        REFERENCES nexus_value_domain(domain_value_key) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_bvmapping_business_value
    ON nexus_business_value_mapping(business_value_key);
CREATE INDEX IF NOT EXISTS idx_bvmapping_value_domain
    ON nexus_business_value_mapping(value_domain_key);
