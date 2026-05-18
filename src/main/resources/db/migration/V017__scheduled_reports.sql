-- V017: Automated Scheduled Reports
-- Allows users to pin a set of questions as a recurring report delivered
-- by email or Slack on a daily/weekly/monthly schedule.

CREATE TABLE nexus_scheduled_report (
    report_key             VARCHAR(120)    PRIMARY KEY,
    name                   VARCHAR(255)    NOT NULL,
    description            TEXT,
    questions_json         TEXT            NOT NULL DEFAULT '[]',
    agent_key              VARCHAR(120),
    schedule_type          VARCHAR(16)     NOT NULL DEFAULT 'WEEKLY'
                               CHECK (schedule_type IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    schedule_time          VARCHAR(5)      NOT NULL DEFAULT '08:00',  -- HH:mm in 24h
    schedule_day_of_week   VARCHAR(3),      -- MON TUE WED THU FRI SAT SUN
    schedule_day_of_month  INT,             -- 1-28
    timezone               VARCHAR(64)     NOT NULL DEFAULT 'UTC',
    channel                VARCHAR(16)     NOT NULL DEFAULT 'EMAIL'
                               CHECK (channel IN ('EMAIL', 'SLACK', 'BOTH')),
    slack_webhook          TEXT,
    email_to               TEXT,
    status                 VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE'
                               CHECK (status IN ('ACTIVE', 'PAUSED', 'ARCHIVED')),
    last_run_at            TIMESTAMPTZ,
    next_run_at            TIMESTAMPTZ,
    last_run_status        VARCHAR(16),
    last_run_message       TEXT,
    created_by             VARCHAR(255),
    created_at             TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_report_active_next ON nexus_scheduled_report(next_run_at)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_report_created_by  ON nexus_scheduled_report(created_by);
