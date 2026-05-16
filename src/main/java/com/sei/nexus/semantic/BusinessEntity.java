package com.sei.nexus.semantic;

import java.time.Instant;

public record BusinessEntity(
    String entityKey,
    String domainKey,
    String entityName,
    String description,
    String primaryObjectKey,
    String operationalMeaning,
    String investigationHints,
    String status,
    String createdBy,
    Instant createdAt,
    Instant updatedAt
) {}
