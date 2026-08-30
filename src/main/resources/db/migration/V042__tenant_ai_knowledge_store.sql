-- V042: Tenant AI knowledge store (Phase 1)
--
-- Adds the columns needed to persist ONE OpenAI Vector Store per tenant
-- against the existing shared tenant registry (public.nexus_tenant). This is
-- Phase 1 of the Persistent Tenant Knowledge migration: provisioning only.
-- No document upload, no File Search integration, no Chat changes.
--
-- ai_knowledge_vector_store_id: OpenAI vector store id (e.g. "vs_..."), NULL
--     until provisioned. NULL is the only signal needed to know "not yet
--     provisioned" — existing tenants are never backfilled by this migration
--     and stay NULL, remaining fully operational (Phase 1 is purely additive).
-- ai_knowledge_status: NULL until a provisioning attempt has been made.
--     'READY'  — the vector store exists and its id is persisted.
--     'FAILED' — the most recent provisioning attempt failed; vector_store_id
--                is still NULL (or, in the orphaned-resource case, a store may
--                exist in OpenAI but its id was never durably persisted here —
--                see AI_KNOWLEDGE_ARCHITECTURE doc for the accepted trade-off).
--     No 'PROVISIONING' state: provisioning is a single synchronous call, so
--     there is no durable window where a row would legitimately be read
--     mid-attempt by another process — a smaller state model than the
--     connection/onboarding-job conventions elsewhere, deliberately scoped to
--     what this synchronous flow actually needs.
-- ai_knowledge_error: last failure reason, for observability. Cleared on success.
-- ai_knowledge_provisioned_at: when the store was successfully created.

SET search_path = public;

ALTER TABLE nexus_tenant
    ADD COLUMN IF NOT EXISTS ai_knowledge_vector_store_id  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS ai_knowledge_status            VARCHAR(20)
        CHECK (ai_knowledge_status IN ('READY', 'FAILED')),
    ADD COLUMN IF NOT EXISTS ai_knowledge_error             VARCHAR(500),
    ADD COLUMN IF NOT EXISTS ai_knowledge_provisioned_at    TIMESTAMPTZ;
