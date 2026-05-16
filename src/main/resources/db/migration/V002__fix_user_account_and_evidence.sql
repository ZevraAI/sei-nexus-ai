-- V002: Fix nexus_user_account schema and evidence column name

-- 1. Add missing updated_at column to nexus_user_account
ALTER TABLE nexus_user_account
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT NOW();

-- 2. Expand role CHECK constraint to include ANALYST
--    PostgreSQL names auto-generated CHECK constraints as <table>_<column>_check
ALTER TABLE nexus_user_account
    DROP CONSTRAINT IF EXISTS nexus_user_account_role_check;

ALTER TABLE nexus_user_account
    ADD CONSTRAINT nexus_user_account_role_check
        CHECK (role IN ('USER', 'DOMAIN_OWNER', 'ADMIN', 'ANALYST'));

-- 3. Rename evidence payload column to payload_json (matches application code)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'nexus_evidence' AND column_name = 'payload'
    ) THEN
        ALTER TABLE nexus_evidence RENAME COLUMN payload TO payload_json;
    END IF;
END
$$;
