-- V048: Playbooks belong to the agents that actually run (nexus_zevra_agent).
--
-- The earlier nexus_agent_playbook table hung off the legacy nexus_agent table. The agents users build
-- and run are nexus_zevra_agent rows executed by AgentRunner, which never read those playbooks. This
-- replaces it with a playbook table keyed to the real agent and drops the old one (it was never
-- reachable from a running agent, so there is nothing to carry over).
--
-- A playbook is a named, reusable way of investigating one kind of question. AgentRunner shows every
-- ACTIVE playbook of the agent to the model, which decides whether one applies and follows it.

CREATE TABLE IF NOT EXISTS nexus_zevra_agent_playbook (
    id               VARCHAR(120) PRIMARY KEY,
    agent_id         VARCHAR(120) NOT NULL REFERENCES nexus_zevra_agent(id) ON DELETE CASCADE,
    tenant_schema    TEXT         NOT NULL,
    name             TEXT         NOT NULL,
    when_to_use      TEXT         NOT NULL,
    steps            TEXT         NOT NULL,
    evidence_order   TEXT,
    escalation       TEXT,
    status           TEXT         NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
    created_by       TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_zevra_playbook_agent ON nexus_zevra_agent_playbook(agent_id, tenant_schema);

DROP TABLE IF EXISTS nexus_agent_playbook CASCADE;
