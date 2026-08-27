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
    String entityType,
    // Grouping Foundation Fix: the AI-generated business grouping from the shared
    // onboarding analysis (analyzeTableBatch), e.g. "Procurement" or "Sales" — the
    // same value regardless of whether the table was AI-recommended or added via
    // Browse All, since both pass through that one analysis step. Null until an
    // onboarding/apply flow supplies it; preserved (not erased) on omission, same
    // COALESCE discipline as primaryObjectKey (Foundation Fix #2) — see
    // SemanticRepository.UPSERT_ENTITY.
    String groupLabel,
    // Global Pack Foundation: a reference to a canonical Global Business Concept —
    // (packKey, conceptKey) together are that concept's stable identity (see
    // com.sei.nexus.pack.PackEntity#conceptKey). Never a copy of a display string
    // (never "Purchase Order"), never derived from entity_name or group_label, and
    // never populated automatically by this task — null until a future, separate
    // mapping mechanism sets it. Preserved on omission via the same COALESCE
    // discipline as groupLabel/primaryObjectKey.
    String packKey,
    String conceptKey
) {
    /** Pre-Global-Pack-Foundation shape — no pack/concept reference. Every existing call site keeps compiling unchanged. */
    public BusinessEntity(String entityKey, String domainKey, String entityName, String description,
                          String primaryObjectKey, String operationalMeaning, String investigationHints,
                          String status, String createdBy, Instant createdAt, Instant updatedAt,
                          String entityType, String groupLabel) {
        this(entityKey, domainKey, entityName, description, primaryObjectKey, operationalMeaning,
                investigationHints, status, createdBy, createdAt, updatedAt, entityType, groupLabel,
                null, null);
    }

    /** Pre-Story-1 shape — no entity type, no group label. Every existing call site keeps compiling unchanged. */
    public BusinessEntity(String entityKey, String domainKey, String entityName, String description,
                          String primaryObjectKey, String operationalMeaning, String investigationHints,
                          String status, String createdBy, Instant createdAt, Instant updatedAt) {
        this(entityKey, domainKey, entityName, description, primaryObjectKey, operationalMeaning,
                investigationHints, status, createdBy, createdAt, updatedAt, null, null);
    }
}
