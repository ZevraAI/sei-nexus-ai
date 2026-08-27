-- ── Global Pack Foundation ────────────────────────────────────────────────────
-- Additive foundation for the future Global Business World List. Nothing here
-- changes existing behavior: every new column is nullable, every existing
-- constraint/index/column is left exactly as it was.
--
-- 1. nexus_tenant_pack gets a connection_key column so a Pack assignment can
--    eventually be scoped to one connection ("each connection has exactly one
--    active Industry Pack"), enforced by a partial unique index — not the
--    existing UNIQUE(pack_key) constraint, which is untouched and continues to
--    mean exactly what it means today (one row per pack per tenant schema).
--
-- 2. nexus_business_entity gets pack_key + concept_key so a tenant Business
--    Entity can eventually reference a canonical Global Business Concept
--    (identity = pack_key + concept_key together — see PackEntity.conceptKey).
--    Both columns are null for every existing row and stay null for every row
--    created by this task's code — no automatic/guessed mapping is introduced.

ALTER TABLE nexus_tenant_pack
    ADD COLUMN IF NOT EXISTS connection_key VARCHAR(120);

-- Enforces "at most one ACTIVE pack per connection" without blocking multiple
-- historical/legacy rows whose connection_key is NULL (a plain unique index
-- already treats NULLs as distinct from one another; AND connection_key IS NOT
-- NULL is added anyway to state the intent explicitly and keep NULL rows out
-- of the index altogether).
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_pack_active_connection
    ON nexus_tenant_pack (connection_key)
    WHERE status = 'ACTIVE' AND connection_key IS NOT NULL;

ALTER TABLE nexus_business_entity
    ADD COLUMN IF NOT EXISTS pack_key    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS concept_key VARCHAR(120);
