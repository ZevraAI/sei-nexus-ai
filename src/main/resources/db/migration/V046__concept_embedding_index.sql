-- V046: Concept retrieval index (replaces the OpenAI Vector Store used by Stage 1)
--
-- Stage 1 of concept-scoped narrowing decides which business concepts a question is about. It used
-- to search a per-tenant OpenAI Vector Store through the model's file_search tool. It now works from
-- a Zevra-owned index: each concept's retrieval text is embedded ONCE (when the concept changes —
-- see ConceptKnowledgeSynchronizationService) and stored here; at question time Zevra embeds the
-- question, takes the nearest concepts by cosine similarity, and the LLM chooses among them.
--
-- Tenant-scoped like the document-memory tables (V013): unqualified name, no search_path override,
-- so it is created in `public` and in every tenant schema (TenantSchemaMigrator catches existing
-- tenants up at startup). The `public` copy stays empty.
--
-- concept_uid       connection_key::pack_key::concept_key — the same identity the sync already uses.
-- content_hash      SHA-256 of everything that makes up the concept's meaning (incl. promoted learned
--                   knowledge); an unchanged hash means "do not re-embed".
-- projection_version shape version of doc_text; bump to force a re-embed of every concept.
-- doc_text          exactly the text that was embedded AND that the Stage 1 prompt shows the model.
-- embedding         vector(1536), the same dimension document memory already uses.
--
-- No ANN index on purpose: a tenant has tens to a few thousand concepts, an exact scan is
-- sub-millisecond at that size, and an IVFFlat index on a small table lowers recall.
--
-- Additive only. The ai_knowledge_* columns on public.nexus_tenant (V042) are no longer written or
-- read by the application; they are left in place and can be dropped by a later cleanup migration.

CREATE TABLE IF NOT EXISTS nexus_concept_embedding (
    concept_uid        VARCHAR(400)  PRIMARY KEY,
    connection_key     VARCHAR(120)  NOT NULL,
    pack_key           VARCHAR(120)  NOT NULL,
    concept_key        VARCHAR(200)  NOT NULL,
    content_hash       VARCHAR(64)   NOT NULL,
    projection_version VARCHAR(10)   NOT NULL,
    doc_text           TEXT          NOT NULL,
    embedding          vector(1536)  NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_concept_embedding_connection
    ON nexus_concept_embedding (connection_key);
