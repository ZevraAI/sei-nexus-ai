-- V007: Knowledge Graph
-- Adds graph metadata columns to nexus_business_entity and nexus_entity_relationship.
--
-- Demo seed data (lgs-* logistics entities) has been removed.
-- Each tenant's knowledge graph is built from their own data during onboarding.
-- Seeding demo entities into every tenant schema caused confusion because
-- they appeared alongside the tenant's real entities in the UI.

-- ── 1. Enrich entity table ────────────────────────────────────────────────────
ALTER TABLE nexus_business_entity
    ADD COLUMN IF NOT EXISTS node_type   VARCHAR(32)  NOT NULL DEFAULT 'ENTITY',
    ADD COLUMN IF NOT EXISTS color       VARCHAR(16),
    ADD COLUMN IF NOT EXISTS group_label VARCHAR(64);

-- ── 2. Enrich relationship table ─────────────────────────────────────────────
ALTER TABLE nexus_entity_relationship
    ADD COLUMN IF NOT EXISTS cardinality   VARCHAR(8),
    ADD COLUMN IF NOT EXISTS bidirectional BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS join_sql      TEXT,
    ADD COLUMN IF NOT EXISTS edge_color    VARCHAR(16);

-- ── 3. Ensure PLATFORM domain exists (self-contained for fresh installs) ─────
INSERT INTO nexus_domain (domain_key, name, description, owner_team, status)
VALUES ('PLATFORM', 'Zevra Platform', 'Default platform domain', 'Platform Team', 'ACTIVE')
ON CONFLICT (domain_key) DO NOTHING;
