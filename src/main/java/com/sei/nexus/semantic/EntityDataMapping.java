package com.sei.nexus.semantic;

import java.time.Instant;

public record EntityDataMapping(
    String mappingKey,
    String entityKey,
    String objectKey,
    String fieldMappings,
    String identityColumns,
    boolean isPrimary,
    Instant createdAt
) {}
