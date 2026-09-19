-- Adds an additive, nullable connection_key to nexus_learned_mapping so a
-- /TeachZevra explicit teaching can be scoped to one specific authorized
-- connection instead of tenant-wide.
--
-- NULL (the default, and the only value implicit learning ever writes) means
-- "applies tenant-wide" — identical to today's behavior. A non-null value is
-- set ONLY by explicit teaching (LearnedMappingRepository's connection-scoped
-- insert path, driven by TeachingService), never inferred. This mirrors the
-- concept_key column's precedent from V043: additive, nullable, explicit-only.

ALTER TABLE nexus_learned_mapping ADD COLUMN IF NOT EXISTS connection_key VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_nexus_learned_mapping_connection_key
    ON nexus_learned_mapping(connection_key) WHERE connection_key IS NOT NULL;
