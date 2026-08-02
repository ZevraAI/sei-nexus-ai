package com.sei.nexus.semanticmodel;

/**
 * A semantic relationship between two {@link BusinessObject}s (ADR-0003 semantic model).
 *
 * <p>The concept is introduced in Phase 1A so the model shape is stable; relationships
 * are not populated until Phase 3 (first-class declared joins). A relationship is
 * business meaning; its physical join realization belongs to the execution plane.
 *
 * @param fromObjectKey source object's {@code objectKey}
 * @param toObjectKey   target object's {@code objectKey}
 * @param kind          relationship kind (e.g. BELONGS_TO, HAS_MANY)
 * @param description   business description
 */
public record Relationship(
        String fromObjectKey,
        String toObjectKey,
        String kind,
        String description
) {}
