package com.sei.nexus.reasoning;

import java.time.Instant;

public record OperationalFinding(
        String findingKey,
        String domainKey,
        String agentKey,
        String findingType,
        String title,
        String description,
        String evidenceSummary,
        String relatedEntityKeys,
        Double confidence,
        String status,
        Instant firstObservedAt,
        Instant lastConfirmedAt,
        Instant resolvedAt
) {}
