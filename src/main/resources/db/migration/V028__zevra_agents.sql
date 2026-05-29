CREATE TABLE nexus_zevra_agent (
  id              VARCHAR(120) PRIMARY KEY,
  tenant_schema   TEXT NOT NULL,
  name            TEXT NOT NULL,
  slug            TEXT NOT NULL,
  description     TEXT,
  persona         TEXT NOT NULL,
  goal            TEXT NOT NULL,
  connection_keys TEXT[] NOT NULL DEFAULT '{}',
  max_iterations  INT  NOT NULL DEFAULT 10,
  status          TEXT NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
  created_by      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_schema, slug)
);

CREATE TABLE nexus_zevra_session (
  id              VARCHAR(120) PRIMARY KEY,
  agent_id        VARCHAR(120) NOT NULL REFERENCES nexus_zevra_agent(id),
  tenant_schema   TEXT NOT NULL,
  input_message   TEXT NOT NULL,
  status          TEXT NOT NULL DEFAULT 'RUNNING'
                  CHECK (status IN ('RUNNING','COMPLETED','FAILED','MAX_ITER')),
  steps           JSONB NOT NULL DEFAULT '[]',
  final_output    TEXT,
  error_message   TEXT,
  iterations_used INT  NOT NULL DEFAULT 0,
  started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_zevra_agent_tenant  ON nexus_zevra_agent(tenant_schema);
CREATE INDEX idx_zevra_session_agent ON nexus_zevra_session(agent_id, tenant_schema);
