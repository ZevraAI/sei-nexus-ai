-- V016: Proactive Intelligence — Alert Rules and Delivery tables.
--
-- nexus_alert_rule  : configuration — which metric triggers which notification
-- nexus_alert_delivery : history — every alert sent, powers the in-app bell

CREATE TABLE nexus_alert_rule (
    rule_key            VARCHAR(120)    PRIMARY KEY,
    rule_name           VARCHAR(255)    NOT NULL,
    baseline_key        VARCHAR(120),
    agent_key           VARCHAR(120),
    kpi_key             VARCHAR(120),
    metric_name         VARCHAR(255),
    condition           VARCHAR(32)     NOT NULL DEFAULT 'ANY_ANOMALY'
                            CHECK (condition IN ('ANY_ANOMALY','ABOVE_WARNING','ABOVE_CRITICAL','BELOW_WARNING','BELOW_CRITICAL')),
    severity_threshold  VARCHAR(16)     NOT NULL DEFAULT 'MEDIUM'
                            CHECK (severity_threshold IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    channel             VARCHAR(32)     NOT NULL DEFAULT 'IN_APP'
                            CHECK (channel IN ('IN_APP','SLACK','EMAIL','ALL')),
    slack_webhook       TEXT,
    email_to            TEXT,
    cooldown_minutes    INT             NOT NULL DEFAULT 60,
    enabled             BOOLEAN         NOT NULL DEFAULT TRUE,
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alert_rule_baseline ON nexus_alert_rule(baseline_key);
CREATE INDEX idx_alert_rule_enabled  ON nexus_alert_rule(enabled);

CREATE TABLE nexus_alert_delivery (
    delivery_key        VARCHAR(120)    PRIMARY KEY,
    rule_key            VARCHAR(120),
    rule_name           VARCHAR(255),
    anomaly_key         VARCHAR(120),
    channel             VARCHAR(32)     NOT NULL,
    metric_name         VARCHAR(255),
    current_value       DECIMAL(18,4),
    baseline_value      DECIMAL(18,4),
    deviation_pct       DECIMAL(8,2),
    severity            VARCHAR(16),
    message_text        TEXT            NOT NULL,
    status              VARCHAR(32)     NOT NULL DEFAULT 'UNREAD'
                            CHECK (status IN ('UNREAD','READ','FAILED')),
    sent_at             TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    read_at             TIMESTAMPTZ
);

CREATE INDEX idx_alert_delivery_status  ON nexus_alert_delivery(status, sent_at DESC);
CREATE INDEX idx_alert_delivery_sent    ON nexus_alert_delivery(sent_at DESC);
