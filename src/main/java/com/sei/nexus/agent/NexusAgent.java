package com.sei.nexus.agent;

import java.time.Instant;

public record NexusAgent(
    String agentKey,
    String name,
    String purpose,
    String domainKeys,
    String connectionKeys,
    boolean restApiEnabled,
    String actionScope,
    int versionNo,
    String status,
    String createdBy,
    Instant createdAt,
    Instant updatedAt
) {}
