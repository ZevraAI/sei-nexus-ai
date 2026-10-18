package com.sei.nexus.knowledge;

import com.sei.nexus.common.PgVector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The concept retrieval index: one row per (connection, pack, concept) in {@code
 * nexus_concept_embedding} (V046), tenant-scoped through the same routing {@link JdbcTemplate} every
 * other tenant table uses — a query only ever sees the calling tenant's own concepts.
 *
 * <p>Pure persistence and vector arithmetic in SQL; no semantics. Which rows exist is decided by
 * {@link ConceptKnowledgeSynchronizationService} from Postgres' authoritative concept catalogue, and
 * which of them a question is about is decided by the Stage 1 LLM — this class only stores the
 * embeddings and returns the nearest ones.
 */
@Repository
public class ConceptEmbeddingRepository {

    /** A concept row as retrieval returns it. {@code similarity} is cosine similarity in [-1, 1]. */
    public record Candidate(String conceptKey, String docText, double similarity) {}

    private final JdbcTemplate jdbc;

    public ConceptEmbeddingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert or replace one concept's row (the sync's create AND update). */
    public void upsert(String conceptUid, String connectionKey, String packKey, String conceptKey,
                       String contentHash, String projectionVersion, String docText, float[] embedding) {
        jdbc.update("""
                INSERT INTO nexus_concept_embedding
                    (concept_uid, connection_key, pack_key, concept_key, content_hash,
                     projection_version, doc_text, embedding, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, NOW())
                ON CONFLICT (concept_uid) DO UPDATE SET
                    connection_key     = EXCLUDED.connection_key,
                    pack_key           = EXCLUDED.pack_key,
                    concept_key        = EXCLUDED.concept_key,
                    content_hash       = EXCLUDED.content_hash,
                    projection_version = EXCLUDED.projection_version,
                    doc_text           = EXCLUDED.doc_text,
                    embedding          = EXCLUDED.embedding,
                    updated_at         = NOW()
                """,
                conceptUid, connectionKey, packKey, conceptKey, contentHash, projectionVersion,
                docText, PgVector.toLiteral(embedding));
    }

    /** concept_uid → "content_hash|projection_version" for every indexed concept in this tenant. */
    public Map<String, String> findFingerprintsByUid() {
        Map<String, String> out = new HashMap<>();
        jdbc.query("SELECT concept_uid, content_hash, projection_version FROM nexus_concept_embedding",
                rs -> {
                    out.put(rs.getString("concept_uid"),
                            rs.getString("content_hash") + "|" + rs.getString("projection_version"));
                });
        return out;
    }

    public void deleteByUid(String conceptUid) {
        jdbc.update("DELETE FROM nexus_concept_embedding WHERE concept_uid = ?", conceptUid);
    }

    /** How many concepts are indexed for one connection — 0 means the index was never built. */
    public int countForConnection(String connectionKey) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nexus_concept_embedding WHERE connection_key = ?",
                Integer.class, connectionKey);
        return n == null ? 0 : n;
    }

    public int countAll() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM nexus_concept_embedding", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * The {@code topK} concepts of one connection nearest to {@code queryEmbedding} by cosine
     * distance, nearest first; ties broken by concept_key so the order is deterministic.
     */
    public List<Candidate> searchNearest(String connectionKey, float[] queryEmbedding, int topK) {
        String vec = PgVector.toLiteral(queryEmbedding);
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT concept_key, doc_text, 1 - (embedding <=> ?::vector) AS similarity
                      FROM nexus_concept_embedding
                     WHERE connection_key = ?
                     ORDER BY embedding <=> ?::vector, concept_key
                     LIMIT ?
                    """);
            ps.setString(1, vec);
            ps.setString(2, connectionKey);
            ps.setString(3, vec);
            ps.setInt(4, topK);
            return ps;
        }, (rs, i) -> new Candidate(rs.getString("concept_key"), rs.getString("doc_text"),
                rs.getDouble("similarity")));
    }

    /** The named concepts of one connection (used to carry a previous turn's concepts forward). The
     *  similarity of a carried concept is not meaningful and is reported as 0. */
    public List<Candidate> findByConceptKeys(String connectionKey, Collection<String> conceptKeys) {
        if (conceptKeys == null || conceptKeys.isEmpty()) return List.of();
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT concept_key, doc_text
                      FROM nexus_concept_embedding
                     WHERE connection_key = ? AND concept_key = ANY(?)
                     ORDER BY concept_key
                    """);
            ps.setString(1, connectionKey);
            ps.setArray(2, con.createArrayOf("text", conceptKeys.toArray(new String[0])));
            return ps;
        }, (rs, i) -> new Candidate(rs.getString("concept_key"), rs.getString("doc_text"), 0.0));
    }
}
