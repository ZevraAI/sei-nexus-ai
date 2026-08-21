package com.sei.nexus.onboarding;

import java.time.Instant;
import java.util.List;

/**
 * An async table-analysis job (V040). One row per {@code POST /onboarding/analyze}
 * request; {@code resultsJson} accumulates incrementally, one table at a time, as
 * {@link OnboardingService#analyzeOneTable} finishes for each entry in
 * {@code tableNames} — see {@link OnboardingAnalysisJobRepository#updateTableResult}.
 */
public record OnboardingAnalysisJob(
        String id,
        String tenantSchema,
        String connectionKey,
        String schemaName,
        String domainKey,
        List<String> tableNames,
        String status,          // RUNNING | COMPLETE | FAILED
        String resultsJson,     // JSON object keyed by table_name
        int tablesDone,
        int tablesTotal,
        String requestHash,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {}
