-- ── Phase 4: Industry Context Packs ──────────────────────────────────────────
-- Tracks which industry context packs a tenant has applied.
-- Pack definitions themselves are loaded from classpath JSON resources at runtime
-- (src/main/resources/industry-packs/*.json) so they update with each deployment
-- without needing cross-schema DB seeding.

CREATE TABLE IF NOT EXISTS nexus_tenant_pack (
    id              BIGSERIAL    PRIMARY KEY,
    pack_key        VARCHAR(255) NOT NULL,
    pack_version    VARCHAR(20)  NOT NULL DEFAULT '1.0.0',
    display_name    VARCHAR(255),
    status          VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','DISABLED')),
    mapping_json    JSONB,
    -- Resolved entity mapping: {"Patient": "tbl_patients", "Encounter": "visits"}
    coverage_score  FLOAT,
    -- Percentage of pack entities that were matched (0.0–1.0)
    applied_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    applied_by      VARCHAR(255),
    UNIQUE (pack_key)
    -- One pack can be applied only once per tenant schema.
);

CREATE INDEX IF NOT EXISTS idx_tenant_pack_status
    ON nexus_tenant_pack(status);
