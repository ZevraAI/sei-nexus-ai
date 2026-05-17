-- V010: Rebuild nexus_connection to match ConnectionRepository column names.
--
-- V001 created nexus_connection with the original schema (username_enc,
-- password_enc, domain_key, etc.). The Java ConnectionRepository uses a
-- different schema (encrypted_secret, allowed_schemas, read_only, etc.).
-- This migration drops and recreates the table with the correct columns.
-- Any existing connection rows are lost (acceptable — this is a configuration
-- table and connections can be re-added through the UI).

DROP TABLE IF EXISTS nexus_connection CASCADE;

CREATE TABLE nexus_connection (
    connection_key      VARCHAR(120)    PRIMARY KEY,
    name                VARCHAR(255)    NOT NULL,
    connection_type     VARCHAR(32)     NOT NULL
                            CHECK (connection_type IN ('POSTGRES', 'ORACLE', 'REST_API')),
    usage_description   TEXT,
    jdbc_url            TEXT,
    instance_url        TEXT,
    username            VARCHAR(255),
    encrypted_secret    TEXT,
    allowed_schemas     TEXT,
    allowed_tables      TEXT,
    read_only           BOOLEAN         NOT NULL DEFAULT TRUE,
    last_test_status    VARCHAR(32),
    last_test_message   TEXT,
    last_tested_at      TIMESTAMPTZ,
    status              VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'INACTIVE', 'ERROR', 'ARCHIVED')),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_nexus_connection_status ON nexus_connection(status);
