package com.sei.nexus.query;

import java.time.Instant;

public record QueryExecution(
    String executionKey,
    String runKey,
    int stepNo,
    String connectionKey,
    String objectKeys,
    String classification,   // POINT_LOOKUP|BOUNDED_LIST|AGGREGATION|JOIN_INVESTIGATION|HIGH_RISK_SCAN|BLOCKED
    String route,            // EXECUTE_SYNC|EXECUTE_ASYNC|ASK_FOR_FILTER|BLOCK
    String riskLevel,        // LOW|MEDIUM|HIGH|CRITICAL
    String status,           // PLANNED|QUEUED|RUNNING|SUCCESS|FAILED|BLOCKED
    Long estimatedRows,
    Long estimatedCost,
    Integer timeoutSeconds,
    Integer rowLimit,
    String originalSql,
    String approvedSql,
    String decisionReason,
    String errorMessage,
    String resultJson,
    Instant createdAt,
    Instant startedAt,
    Instant completedAt
) {}
