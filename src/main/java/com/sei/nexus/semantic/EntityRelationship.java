package com.sei.nexus.semantic;

import java.time.Instant;

public record EntityRelationship(
    String relationshipKey,
    String sourceEntityKey,
    String targetEntityKey,
    String relationshipType,
    String sourceColumn,
    String targetColumn,
    String joinGuidance,
    boolean crossSystem,
    String identityResolution,
    Instant createdAt
) {}
