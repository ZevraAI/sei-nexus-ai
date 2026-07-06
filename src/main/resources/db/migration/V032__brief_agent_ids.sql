-- V032: Allow executives to select which agents contribute to their morning brief.
-- Empty array = use all active agents (backward compatible default).

ALTER TABLE nexus_morning_brief_config
    ADD COLUMN IF NOT EXISTS brief_agent_ids TEXT[] NOT NULL DEFAULT '{}';
