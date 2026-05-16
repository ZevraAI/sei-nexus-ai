package com.sei.nexus.connection;

import java.time.Instant;

/**
 * Represents a named, governed data-source connection.
 * Stored in the nexus_connection table.
 *
 * <p><strong>Security note:</strong> {@code encryptedSecret} is stored as-is in this
 * implementation (effectively plaintext).  In production this field should be
 * encrypted at rest via HashiCorp Vault or AWS Secrets Manager, and the decryption
 * should happen only at query-execution time, never returned to callers.</p>
 */
public record NexusConnection(
        String connectionKey,
        String name,
        String connectionType,       // POSTGRES | ORACLE | REST_API
        String usageDescription,
        String jdbcUrl,
        String instanceUrl,
        String username,
        String encryptedSecret,      // PRODUCTION: store via Vault — never return to API callers
        String allowedSchemas,       // comma-separated list of permitted schemas
        String allowedTables,        // comma-separated list of permitted schema.table entries
        boolean readOnly,
        String lastTestStatus,       // SUCCESS | FAILED | null
        String lastTestMessage,
        Instant lastTestedAt,
        String status,               // ACTIVE | ARCHIVED
        Instant createdAt,
        Instant updatedAt
) {}
