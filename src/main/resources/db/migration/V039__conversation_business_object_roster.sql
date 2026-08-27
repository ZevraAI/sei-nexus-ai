-- V039: Conversation Business Object Roster (Conversation Memory foundation, Phase 1)
--
-- A conversation-scoped reference index of Business Objects the LLM has explicitly
-- discovered (via authoritative retrieval or successful execution) during a conversation.
-- Tenant-schema resident like all nexus_* metadata: the migration runs in every tenant
-- schema, and TenantAwareDataSource routing keeps rows tenant-isolated.
--
-- This table stores REFERENCES ONLY (entity_key + a display label + type marker).
-- It does NOT store physical columns, descriptions, relationships, business guidance,
-- SQL, or any metadata snapshot — Business World (nexus_data_object) remains the sole
-- authoritative source for object metadata. This is not a cache and not a second
-- metadata system.
--
-- Registration is idempotent: ON CONFLICT (conversation_id, entity_key) DO NOTHING —
-- first discovery wins, repeated discovery of the same entity never duplicates a row.
--
-- No agent tool writes to this table yet (Phase 1 establishes persistence only;
-- wiring into the Agent tool loop is a later phase).

CREATE TABLE IF NOT EXISTS nexus_conversation_business_object_roster (
    conversation_id  VARCHAR(120)  NOT NULL,
    entity_key       VARCHAR(120)  NOT NULL,
    business_name    VARCHAR(255),
    object_type      VARCHAR(60)   NOT NULL,
    discovered_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (conversation_id, entity_key)
);

-- memory.list() reads the full roster for a conversation.
CREATE INDEX IF NOT EXISTS idx_conv_roster_conversation
    ON nexus_conversation_business_object_roster(conversation_id);
