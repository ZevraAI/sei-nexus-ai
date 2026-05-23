-- V018: Chat attachments — file upload and image paste in conversations.
-- Attachments are scoped to a conversation and auto-expire after 24 hours.
-- They are never stored as permanent AI Memory unless the user explicitly saves them.

CREATE TABLE nexus_chat_attachment (
    attachment_key      VARCHAR(120)    PRIMARY KEY,
    conversation_id     VARCHAR(120),
    file_name           VARCHAR(500)    NOT NULL,
    attachment_type     VARCHAR(32)     NOT NULL
                            CHECK (attachment_type IN ('IMAGE','TABULAR','DOCUMENT','TEXT')),
    mime_type           VARCHAR(100),
    file_size_bytes     BIGINT,
    -- AI-generated one-line description of what was extracted
    summary             TEXT,
    -- Full extracted text injected into the LLM context
    extracted_text      TEXT,
    -- Base64-encoded thumbnail (images only) for display in the UI
    thumbnail_base64    TEXT,
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    -- Attachments expire automatically; a scheduler purges old rows nightly
    expires_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
);

CREATE INDEX idx_attachment_conversation ON nexus_chat_attachment(conversation_id, created_at DESC);
CREATE INDEX idx_attachment_expires      ON nexus_chat_attachment(expires_at);
