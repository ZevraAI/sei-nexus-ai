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
        Instant createdAt,
        Instant updatedAt
) {}
