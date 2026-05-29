package com.sei.nexus.agentrunner;

import java.time.Instant;
import java.util.List;

public record ZevraAgent(
        String      id,
        String      tenantSchema,
        String      name,
        String      slug,
        String      description,
        String      persona,
        String      goal,
        List<String> connectionKeys,
        int         maxIterations,
        String      status,
        String      createdBy,
        Instant     createdAt,
        Instant     updatedAt
) {}
