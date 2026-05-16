package com.sei.nexus.memory;

import java.time.Instant;

/**
 * Represents a document ingested into the knowledge base.
 * Stored in the nexus_document table.
 */
public record KnowledgeDocument(
        String documentKey,
        String domainKey,
        String title,
        String fileName,
        String filePath,
        long fileSizeBytes,
        String contentType,
        String tags,
        String status,       // UPLOADED | INDEXING | INDEXED | FAILED | ARCHIVED
        int chunkCount,
        Instant indexedAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {}
