package com.sei.nexus.memory;

/**
 * A single text chunk derived from a KnowledgeDocument, with its pgvector embedding.
 * Stored in the nexus_document_chunk table.
 */
public record DocumentChunk(
        String chunkKey,
        String documentKey,
        int chunkNo,
        String chunkText,
        float[] embedding,
        int tokenCount
) {}
