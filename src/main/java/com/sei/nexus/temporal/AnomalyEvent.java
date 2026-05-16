package com.sei.nexus.temporal;

import java.time.Instant;

public record AnomalyEvent(
        String anomalyKey,
        String baselineKey,
        String domainKey,
        String entityKey,
        Instant detectedAt,
        String metricName,
        Double baselineValue,
        Double observedValue,
        Double deviationPct,
        Double deviationStddev,
        String severity,
        String status,
        String findingKey
) {}
