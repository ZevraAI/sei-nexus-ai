package com.sei.nexus.agent;

import java.time.Instant;

public record AgentVersion(
    String versionKey,
    String agentKey,
    int versionNo,
    String snapshot,
    Instant createdAt
) {}
