package com.sei.nexus.enterprise;

import java.time.Instant;

/**
 * A curated operational note attached to a domain, entity, or data object.
 * Used to inject human expert context into LLM prompts.
 * Stored in nexus_operational_note.
 */
public record OperationalNote(
        String noteKey,
        String domainKey,
        String entityName,
        String objectKey,
        String title,
        String noteText,
        String tags,
        String status,     // ACTIVE | ARCHIVED
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {}
