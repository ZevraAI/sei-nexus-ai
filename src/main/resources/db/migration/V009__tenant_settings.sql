-- V009: Per-tenant key-value settings store
-- Used to track onboarding completion and store tenant-level configuration.
-- Lives inside each tenant schema (including public for the default tenant).

CREATE TABLE IF NOT EXISTS nexus_tenant_settings (
    setting_key    VARCHAR(128)  PRIMARY KEY,
    setting_value  TEXT          NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Index not needed on a tiny settings table, but document expected keys:
-- 'onboarding_completed'          → 'true' once onboarding wizard is done
-- 'onboarding_suggested_questions'→ JSON array of suggested first questions
