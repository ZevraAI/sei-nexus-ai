-- V034: Value domain metadata (PRO-10)
--
-- Persists column value domains discovered from source-database catalogs
-- (PostgreSQL ENUM types initially). One row per (connection, schema, type).
-- Tenant-schema resident like all nexus_* metadata: the migration runs in every
-- tenant schema, and TenantAwareDataSource routing keeps rows tenant-isolated.
--
-- domain_values holds the ordered legal values as a JSON array of strings,
-- e.g. ["open","temporarily_closed","seasonal","under_construction","closed"].

CREATE TABLE IF NOT EXISTS nexus_value_domain (
    domain_value_key  VARCHAR(120)  PRIMARY KEY,
    connection_key    VARCHAR(120)  NOT NULL,
    source_schema     VARCHAR(255)  NOT NULL,
    domain_name       VARCHAR(255)  NOT NULL,
    source            VARCHAR(16)   NOT NULL DEFAULT 'ENUM',
    is_authoritative  BOOLEAN       NOT NULL DEFAULT TRUE,
    domain_values     JSONB         NOT NULL,
    scanned_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (connection_key, source_schema, domain_name, source)
);

CREATE INDEX IF NOT EXISTS idx_value_domain_conn
    ON nexus_value_domain(connection_key, source_schema);

-- Column -> domain binding + preserved type identity.
-- udt_name keeps the raw type name from information_schema so the enum identity
-- is never lost again even when no domain row could be resolved.
ALTER TABLE nexus_data_column
    ADD COLUMN IF NOT EXISTS udt_name         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS value_domain_key VARCHAR(120)
        REFERENCES nexus_value_domain(domain_value_key) ON DELETE SET NULL;
