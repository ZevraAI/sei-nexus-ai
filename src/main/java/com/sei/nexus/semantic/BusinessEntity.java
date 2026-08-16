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
    Instant updatedAt,
    // Restored (Story 1, Enterprise Business Reference Grounding): the same column
    // V001 originally had, dropped in the V006 rebuild. Curated metadata, never
    // inferred — null for every entity until a curator sets it, which changes
    // nothing about how that entity behaves today.
    String entityType
) {
    /** Pre-Story-1 shape — no entity type. Every existing call site keeps compiling unchanged. */
    public BusinessEntity(String entityKey, String domainKey, String entityName, String description,
                          String primaryObjectKey, String operationalMeaning, String investigationHints,
                          String status, String createdBy, Instant createdAt, Instant updatedAt) {
        this(entityKey, domainKey, entityName, description, primaryObjectKey, operationalMeaning,
                investigationHints, status, createdBy, createdAt, updatedAt, null);
    }
}
