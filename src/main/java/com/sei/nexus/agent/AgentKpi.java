package com.sei.nexus.agent;

import java.time.Instant;

public record AgentKpi(
    String kpiKey,
    String agentKey,
    String domainKey,
    String kpiName,
    String kpiDescription,
    String measurementObjectKey,
    String measurementSql,
    Double thresholdWarning,
    Double thresholdCritical,
    boolean higherIsBetter,
    Integer refreshIntervalHrs,
    String status,
    Instant createdAt
) {}
