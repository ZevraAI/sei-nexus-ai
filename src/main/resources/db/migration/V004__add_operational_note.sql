-- V004: Create nexus_operational_note (distinct from nexus_knowledge_note —
--       used by EnterpriseMapService to store curated expert context notes)

CREATE TABLE IF NOT EXISTS nexus_operational_note (
    note_key    VARCHAR(120)  PRIMARY KEY,
    domain_key  VARCHAR(120)  NOT NULL REFERENCES nexus_domain(domain_key),
    entity_name VARCHAR(255),
    object_key  VARCHAR(120),
    title       VARCHAR(500)  NOT NULL,
    note_text   TEXT          NOT NULL,
    tags        TEXT,
    status      VARCHAR(40)   NOT NULL DEFAULT 'ACTIVE',
    created_by  VARCHAR(255),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_operational_note_domain ON nexus_operational_note(domain_key, status);
CREATE INDEX IF NOT EXISTS idx_operational_note_object ON nexus_operational_note(object_key);
