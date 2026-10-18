-- V047 — Drop the OpenAI Vector Store columns from the tenant registry.
--
-- Stage 1 no longer uses an OpenAI Vector Store: it retrieves candidate concepts from Zevra's own
-- pgvector concept index (V046, nexus_concept_embedding). Nothing reads or writes these columns any
-- more, and the Vector Stores and files they pointed at have been deleted from OpenAI.
--
-- Public-only, like V042 which added them (nexus_tenant lives in the shared platform schema).

SET search_path = public;

ALTER TABLE nexus_tenant
    DROP COLUMN IF EXISTS ai_knowledge_vector_store_id,
    DROP COLUMN IF EXISTS ai_knowledge_status,
    DROP COLUMN IF EXISTS ai_knowledge_error,
    DROP COLUMN IF EXISTS ai_knowledge_provisioned_at;
