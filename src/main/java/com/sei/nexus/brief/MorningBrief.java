package com.sei.nexus.brief;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record MorningBrief(
        String     id,
        String     tenantSchema,
        LocalDate  briefDate,
        String     status,          // GENERATING | READY | FAILED
        String     headline,
        String     sectionsJson,    // JSONB stored as raw string, parsed on the way out
        List<String> agentsUsed,
        Instant    generatedAt,
        Instant    createdAt
) {}
