-- V003: Install pgvector and create nexus_document_chunk (missed by baseline)

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS nexus_document_chunk (
    chunk_key           VARCHAR(120)  PRIMARY KEY,
    document_key        VARCHAR(120)  NOT NULL REFERENCES nexus_document(document_key),
    domain_key          VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    chunk_index         INTEGER       NOT NULL,
    chunk_text          TEXT          NOT NULL,
    word_count          INTEGER,
    embedding           vector(1536),
    token_count         INTEGER,
    page_number         INTEGER,
    section_heading     VARCHAR(500),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chunk_document_key ON nexus_document_chunk(document_key);
CREATE INDEX IF NOT EXISTS idx_chunk_domain_key   ON nexus_document_chunk(domain_key);
CREATE INDEX IF NOT EXISTS idx_chunk_embedding    ON nexus_document_chunk
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
