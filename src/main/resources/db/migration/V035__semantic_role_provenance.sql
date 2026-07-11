-- V035: Semantic Role provenance (PRO-29)
--
-- PRO-28 established the persisted Semantic Role on nexus_data_column as the
-- source of truth for Business Value Discovery decisions. Enforcing its
-- precedence (human > declared > inferred) requires knowing WHERE each
-- column's role flags came from:
--
--   INFERRED  — produced by name-hint inference during a scan (recomputable)
--   DECLARED  — produced by an onboarding/API declaration
--               (statusColumns / safeFilterColumns / identifierColumns)
--   CONFIRMED — asserted by a human via the column PATCH endpoint;
--               never recomputed away by any scan
--
-- Tenant-schema resident like all nexus_* metadata; existing rows default to
-- INFERRED (their flags were scan-inferred and remain recomputable).

ALTER TABLE nexus_data_column
    ADD COLUMN IF NOT EXISTS role_source VARCHAR(16) NOT NULL DEFAULT 'INFERRED';
