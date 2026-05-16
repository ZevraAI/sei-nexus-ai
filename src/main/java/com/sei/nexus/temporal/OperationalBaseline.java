package com.sei.nexus.temporal;

import java.time.Instant;

public record OperationalBaseline(
        String baselineKey,
        String domainKey,
        String agentKey,
        String kpiKey,
        String metricName,
        String measurementSql,
        String connectionKey,
        Double currentValue,
        Double baselineAvg,
        Double baselineStddev,
        String measurementWindow,
        String trendData,
        Instant lastComputedAt,
        Instant nextDueAt,
        String status,
        Instant createdAt
) {}
