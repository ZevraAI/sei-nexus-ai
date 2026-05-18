package com.sei.nexus.alert;

import java.time.Instant;

public record AlertRule(
        String  ruleKey,
        String  ruleName,
        String  baselineKey,
        String  agentKey,
        String  kpiKey,
        String  metricName,
        String  condition,         // ANY_ANOMALY | ABOVE_WARNING | ABOVE_CRITICAL | BELOW_WARNING | BELOW_CRITICAL
        String  severityThreshold, // LOW | MEDIUM | HIGH | CRITICAL
        String  channel,           // IN_APP | SLACK | EMAIL | ALL
        String  slackWebhook,
        String  emailTo,
        int     cooldownMinutes,
        boolean enabled,
        String  createdBy,
        Instant createdAt,
        Instant updatedAt
) {}
