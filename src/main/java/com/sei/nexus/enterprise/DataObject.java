package com.sei.nexus.enterprise;

import java.time.Instant;

/**
 * Represents a governed data object (table or view) in the enterprise map.
 * Stored in nexus_data_object.
 */
public record DataObject(
        String objectKey,
        String domainKey,
        String entityName,
        String connectionKey,
        String schemaName,
        String tableName,
        String businessName,
        String purpose,
        String identifierColumns,    // comma-separated
        String statusColumns,        // comma-separated
        String exceptionColumns,     // comma-separated
        String safeFilterColumns,    // comma-separated
        String usageGuidance,
        String filterGuidance,
        String avoidGuidance,
        Integer rowLimit,
        boolean largeTable,
        String scanStatus,           // PENDING | SCANNED | FAILED
        int versionNo,
        Instant createdAt,
        Instant updatedAt
) {}
