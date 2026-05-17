-- V013: Rebuild nexus_document and nexus_document_chunk to match MemoryRepository.
--
-- V001 created nexus_document with columns: source_uri, mime_type, page_count, ingested_by.
-- MemoryRepository.saveDocument() inserts: file_name, file_path, content_type, tags,
-- chunk_count, indexed_at, created_by — a completely different set.
--
-- nexus_document_chunk also had domain_key NOT NULL, but saveChunk() does not supply it.
-- The retrieve query joins nexus_document for domain filtering so domain_key is not
-- needed on the chunk row itself.

DROP TABLE IF EXISTS nexus_document_chunk CASCADE;
DROP TABLE IF EXISTS nexus_document CASCADE;

CREATE TABLE nexus_document (
    document_key        VARCHAR(120)    PRIMARY KEY,
    domain_key          VARCHAR(120)    NOT NULL REFERENCES nexus_domain(domain_key),
    title               VARCHAR(500)    NOT NULL,
    file_name           VARCHAR(500),
    file_path           TEXT,
    file_size_bytes     BIGINT,
    content_type        VARCHAR(100),
    tags                TEXT,
    status              VARCHAR(40)     NOT NULL DEFAULT 'PROCESSING',
    chunk_count         INTEGER         NOT NULL DEFAULT 0,
    indexed_at          TIMESTAMPTZ,
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_nexus_document_domain ON nexus_document(domain_key);
CREATE INDEX idx_nexus_document_status ON nexus_document(status);

CREATE TABLE nexus_document_chunk (
    chunk_key           VARCHAR(120)    PRIMARY KEY,
    document_key        VARCHAR(120)    NOT NULL REFERENCES nexus_document(document_key) ON DELETE CASCADE,
    chunk_index         INTEGER         NOT NULL,
    chunk_text          TEXT            NOT NULL,
    embedding           vector(1536),
    token_count         INTEGER         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_nexus_chunk_document  ON nexus_document_chunk(document_key);
CREATE INDEX idx_nexus_chunk_embedding ON nexus_document_chunk
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
