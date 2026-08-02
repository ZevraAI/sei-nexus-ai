package com.sei.nexus.enterprise;

import java.time.Instant;

/**
 * A single column of a DataObject, enriched with business meaning and role flags.
 * Stored in nexus_data_column.
 */
public record DataColumn(
        String columnKey,
        String objectKey,
        String columnName,
        String dataType,
        boolean isNullable,
        String businessMeaning,
        boolean isIdentifier,
        boolean isStatus,
        boolean isError,
        boolean isSensitive,
        boolean isFilterable,
        // Raw type name from information_schema (e.g. the ENUM type name when
        // data_type = 'USER-DEFINED'); null for plain built-in types. PRO-10.
        String udtName,
        // Reference to nexus_value_domain when a discovered domain matches udtName.
        String valueDomainKey,
        // Provenance of the role flags above (PRO-29 Semantic Role ownership):
        // INFERRED (name hints, recomputable) | DECLARED (onboarding/API
        // declaration, survives rescans) | CONFIRMED (human, authoritative —
        // never recomputed away).
        String roleSource,
        Instant createdAt,
        Instant updatedAt
) {

    public static final String ROLE_INFERRED  = "INFERRED";
    public static final String ROLE_DECLARED  = "DECLARED";
    public static final String ROLE_CONFIRMED = "CONFIRMED";
}
