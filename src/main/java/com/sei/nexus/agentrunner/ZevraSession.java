package com.sei.nexus.agentrunner;

import java.time.Instant;

public record ZevraSession(
        String  id,
        String  agentId,
        String  tenantSchema,
        String  inputMessage,
        String  status,
        String  stepsJson,
        String  finalOutput,
        String  errorMessage,
        int     iterationsUsed,
        Instant startedAt,
        Instant completedAt
) {}
