package com.sei.nexus.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class MemoryRepository {

    private final JdbcTemplate jdbc;

    public MemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------------
    // Document operations
    // ---------------------------------------------------------------------------

    public void saveDocument(KnowledgeDocument doc) {
        jdbc.update("""
                INSERT INTO nexus_document
                    (document_key, domain_key, title, file_name, file_path,
                     file_size_bytes, content_type, tags, status,
                     chunk_count, indexed_at, created_by, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                doc.documentKey(), doc.domainKey(), doc.title(),
                doc.fileName(), doc.filePath(), doc.fileSizeBytes(),
                doc.contentType(), doc.tags(), doc.status(),
                doc.chunkCount(),
                doc.indexedAt() != null ? Timestamp.from(doc.indexedAt()) : null,
                doc.createdBy(),
                Timestamp.from(doc.createdAt()),
                Timestamp.from(doc.updatedAt()));
    }

    public void updateDocumentStatus(String documentKey, String status,
                                     int chunkCount, Instant indexedAt) {
        jdbc.update("""
                UPDATE nexus_document
                   SET status = ?, chunk_count = ?, indexed_at = ?, updated_at = NOW()
                 WHERE document_key = ?
                """,
                status, chunkCount,
                indexedAt != null ? Timestamp.from(indexedAt) : null,
                documentKey);
    }

    public Optional<KnowledgeDocument> findByKey(String documentKey) {
        List<KnowledgeDocument> rows = jdbc.query(
                "SELECT * FROM nexus_document WHERE document_key = ?",
                documentRowMapper(),
                documentKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<KnowledgeDocument> findByDomain(String domainKey) {
        return jdbc.query(
                "SELECT * FROM nexus_document WHERE domain_key = ? AND status != 'ARCHIVED' ORDER BY created_at DESC",
                documentRowMapper(),
                domainKey);
    }

    public void archiveDocument(String documentKey) {
        jdbc.update("""
                UPDATE nexus_document
                   SET status = 'ARCHIVED', updated_at = NOW()
                 WHERE document_key = ?
                """, documentKey);
    }

    public void updateDocumentMeta(String documentKey, String domainKey,
                                   String title, String tags) {
        jdbc.update("""
                UPDATE nexus_document
                   SET domain_key = ?, title = ?, tags = ?, updated_at = NOW()
                 WHERE document_key = ?
                """, domainKey, title, tags, documentKey);
    }

    // ---------------------------------------------------------------------------
    // Chunk operations
    // ---------------------------------------------------------------------------

    /**
     * Saves a chunk with the embedding stored as a pgvector literal using the ::vector cast.
     */
    public void saveChunk(DocumentChunk chunk) {
        String vectorStr = toVectorString(chunk.embedding());
        // The ::vector cast is appended directly in the SQL string; the parameter
        // supplies the bracketed float list which Postgres parses as a vector.
        jdbc.update("""
                INSERT INTO nexus_document_chunk
                    (chunk_key, document_key, chunk_index, chunk_text, embedding, token_count)
                VALUES (?, ?, ?, ?, ?::vector, ?)
                """,
                chunk.chunkKey(), chunk.documentKey(), chunk.chunkNo(),
                chunk.chunkText(), vectorStr, chunk.tokenCount());
    }

    /** Retrieves top-K chunks across ALL indexed documents (no domain filter). */
    public List<DocumentChunk> retrieveAllChunks(float[] embedding, int topK) {
        String vectorStr = toVectorString(embedding);
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT c.chunk_key,
                           c.document_key,
                           c.chunk_index AS chunk_no,
                           c.chunk_text,
                           c.token_count,
                           1 - (c.embedding <=> ?::vector) AS similarity
                      FROM nexus_document_chunk c
                      JOIN nexus_document d USING (document_key)
                     WHERE d.status = 'INDEXED'
                     ORDER BY c.embedding <=> ?::vector
                     LIMIT ?
                    """);
            ps.setString(1, vectorStr);
            ps.setString(2, vectorStr);
            ps.setInt(3, topK);
            return ps;
        }, (rs, rowNum) -> new DocumentChunk(
                rs.getString("chunk_key"),
                rs.getString("document_key"),
                rs.getInt("chunk_no"),
                rs.getString("chunk_text"),
                new float[0],
                rs.getInt("token_count")));
    }

    public void deleteChunks(String documentKey) {
        jdbc.update("DELETE FROM nexus_document_chunk WHERE document_key = ?", documentKey);
    }

    /**
     * Performs a pgvector cosine-similarity search across chunks belonging to the
     * supplied domains.  Returns the top-K most similar chunks.
     *
     * <p>The embedding parameter is passed twice: once for the similarity score column
     * and once for the ORDER BY clause.
     */
    public List<DocumentChunk> retrieveChunks(float[] embedding,
                                               List<String> domainKeys,
                                               int topK) {
        String vectorStr = toVectorString(embedding);
        String[] domainArray = domainKeys.toArray(new String[0]);

        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT c.chunk_key,
                           c.document_key,
                           c.chunk_index AS chunk_no,
                           c.chunk_text,
                           c.token_count,
                           1 - (c.embedding <=> ?::vector) AS similarity
                      FROM nexus_document_chunk c
                      JOIN nexus_document d USING (document_key)
                     WHERE d.status = 'INDEXED'
                       AND d.domain_key = ANY(?)
                     ORDER BY c.embedding <=> ?::vector
                     LIMIT ?
                    """);
            ps.setString(1, vectorStr);
            ps.setArray(2, con.createArrayOf("text", domainArray));
            ps.setString(3, vectorStr);
            ps.setInt(4, topK);
            return ps;
        }, (rs, rowNum) -> new DocumentChunk(
                rs.getString("chunk_key"),
                rs.getString("document_key"),
                rs.getInt("chunk_no"),
                rs.getString("chunk_text"),
                new float[0],  // embeddings are not returned to save bandwidth
                rs.getInt("token_count")));
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private RowMapper<KnowledgeDocument> documentRowMapper() {
        return (rs, rowNum) -> new KnowledgeDocument(
                rs.getString("document_key"),
                rs.getString("domain_key"),
                rs.getString("title"),
                rs.getString("file_name"),
                rs.getString("file_path"),
                rs.getLong("file_size_bytes"),
                rs.getString("content_type"),
                rs.getString("tags"),
                rs.getString("status"),
                rs.getInt("chunk_count"),
                toInstant(rs, "indexed_at"),
                rs.getString("created_by"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private Instant toInstant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }

    /**
     * Converts a float array to the bracketed string format expected by pgvector,
     * e.g. [0.1,0.2,0.3].
     */
    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
