package com.sei.nexus.brief;

import java.time.Instant;

public record MorningBriefConfig(
        String       tenantSchema,
        String       scheduleTime,   // HH:mm
        String       timezone,
        boolean      enabled,
        String       emailTo,        // comma-separated, nullable
        java.util.List<String> briefAgentIds, // empty = all active agents
        Instant      updatedAt
) {}
