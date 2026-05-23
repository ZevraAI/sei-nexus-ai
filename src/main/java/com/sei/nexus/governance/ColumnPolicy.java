package com.sei.nexus.governance;

import java.time.Instant;

/**
 * Defines how a single column in a data object should be masked when returned
 * to users who are not in the exempt roles list.
 *
 * mask_type semantics:
 *   EXCLUDE  — column is removed from the SELECT clause entirely
 *   HASH     — column value replaced with MD5(CAST(value AS TEXT))
 *   PARTIAL  — first {@code partialChars} characters kept, rest replaced with '****'
 *   CONSTANT — column value replaced with {@code constantValue}
 */
public record ColumnPolicy(
        String   policyKey,
        String   objectKey,
        String   columnName,
        String   maskType,        // EXCLUDE | HASH | PARTIAL | CONSTANT
        String   constantValue,   // nullable; used when maskType = CONSTANT
        int      partialChars,    // default 3; used when maskType = PARTIAL
        String[] exemptRoles,     // roles that see the real value; empty = no exemptions
        String   createdBy,
        Instant  createdAt,
        Instant  updatedAt
) {}
