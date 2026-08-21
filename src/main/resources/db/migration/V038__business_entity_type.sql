-- V038: Restore entity_type on nexus_business_entity (Story 1, Enterprise Business
-- Reference Grounding — architecture roadmap, §1 Metadata Foundation).
--
-- This column existed in the original schema (V001__init.sql) and was dropped during
-- the V006 rebuild. It is restored, not introduced: no new metadata model, no new
-- concept — the same column, the same meaning, the same table.
--
-- Nullable and additive: existing rows are unaffected, and a business entity with no
-- entity_type set continues to behave exactly as it does today. This column has no
-- consumer yet — populating it is a curation activity (existing onboarding process),
-- not something this migration performs or triggers.

ALTER TABLE nexus_business_entity
    ADD COLUMN IF NOT EXISTS entity_type VARCHAR(120);
