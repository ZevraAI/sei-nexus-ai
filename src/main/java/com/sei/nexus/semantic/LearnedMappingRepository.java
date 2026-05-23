package com.sei.nexus.semantic;

import com.sei.nexus.common.Keys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class LearnedMappingRepository {

    private final JdbcTemplate jdbc;

    public LearnedMappingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Write operations ──────────────────────────────────────────────────────

    /**
     * Insert a new mapping or, if a mapping for the same (domain_key, business_term)
     * already exists, increment its use_count and update confidence via a weighted average.
     */
    public LearnedMapping upsert(LearnedMapping m) {
        String key = m.mappingKey() != null ? m.mappingKey() : Keys.uniqueKey("lmap");

        // Check if an existing mapping matches the (domain_key, business_term) pair
        Optional<LearnedMapping> existing = findByTerm(m.domainKey(), m.businessTerm());

        if (existing.isPresent()) {
            // Reinforce: nudge confidence up slightly and record latest use
            LearnedMapping e = existing.get();
            double newConf = Math.min(e.confidence() + 0.05, 1.0);
            int    newCount = e.useCount() + 1;
            jdbc.update("""
                    UPDATE nexus_learned_mapping
                    SET confidence    = ?,
                        use_count     = ?,
                        sql_pattern   = ?,
                        last_used_at  = NOW(),
                        updated_at    = NOW()
                    WHERE mapping_key = ?
                    """,
                    newConf, newCount, m.sqlPattern(), e.mappingKey());
            return findByKey(e.mappingKey()).orElse(e);
        }

        // New mapping
        jdbc.update("""
                INSERT INTO nexus_learned_mapping
                    (mapping_key, domain_key, business_term, sql_pattern,
                     source_run_key, source, confidence, use_count,
                     last_used_at, promoted, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,NOW(),FALSE,NOW(),NOW())
                """,
                key,
                m.domainKey(),
                m.businessTerm(),
                m.sqlPattern(),
                m.sourceRunKey(),
                m.source(),
                m.confidence(),
                m.useCount());
        return findByKey(key).orElseThrow();
    }

    /** Decrease confidence after a user correction. */
    public void penalise(String mappingKey) {
        jdbc.update("""
                UPDATE nexus_learned_mapping
                SET confidence  = GREATEST(confidence - 0.20, 0.0),
                    updated_at  = NOW()
                WHERE mapping_key = ?
                """, mappingKey);
    }

    /** Reinforce from explicit positive feedback (thumbs up). */
    public void reinforce(String mappingKey) {
        jdbc.update("""
                UPDATE nexus_learned_mapping
                SET confidence  = LEAST(confidence + 0.08, 1.0),
                    use_count   = use_count + 1,
                    last_used_at = NOW(),
                    updated_at  = NOW()
                WHERE mapping_key = ?
                """, mappingKey);
    }

    /** Mark a mapping as promoted (graduated to formal vocabulary). */
    public void markPromoted(String mappingKey) {
        jdbc.update(
                "UPDATE nexus_learned_mapping SET promoted = TRUE, updated_at = NOW() WHERE mapping_key = ?",
                mappingKey);
    }

    /** Admin update — change sql_pattern or reset confidence. */
    public void update(String mappingKey, String sqlPattern, Double confidence) {
        jdbc.update("""
                UPDATE nexus_learned_mapping
                SET sql_pattern = COALESCE(?, sql_pattern),
                    confidence  = COALESCE(?, confidence),
                    updated_at  = NOW()
                WHERE mapping_key = ?
                """, sqlPattern, confidence, mappingKey);
    }

    public void delete(String mappingKey) {
        jdbc.update("DELETE FROM nexus_learned_mapping WHERE mapping_key = ?", mappingKey);
    }

    // ── Read operations ───────────────────────────────────────────────────────

    public Optional<LearnedMapping> findByKey(String mappingKey) {
        List<LearnedMapping> rows = jdbc.query(
                "SELECT * FROM nexus_learned_mapping WHERE mapping_key = ?", mapper(), mappingKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<LearnedMapping> findByTerm(String domainKey, String businessTerm) {
        List<LearnedMapping> rows;
        if (domainKey == null) {
            rows = jdbc.query(
                    "SELECT * FROM nexus_learned_mapping WHERE domain_key IS NULL AND business_term = ?",
                    mapper(), businessTerm);
        } else {
            rows = jdbc.query(
                    "SELECT * FROM nexus_learned_mapping WHERE domain_key = ? AND business_term = ?",
                    mapper(), domainKey, businessTerm);
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Returns mappings for the Learnings panel — ordered by confidence desc.
     * If domainKey is null, returns all cross-domain mappings for the tenant.
     */
    public List<LearnedMapping> findForDomain(String domainKey) {
        if (domainKey == null || domainKey.isBlank()) {
            return jdbc.query(
                    "SELECT * FROM nexus_learned_mapping ORDER BY confidence DESC, use_count DESC LIMIT 100",
                    mapper());
        }
        return jdbc.query(
                "SELECT * FROM nexus_learned_mapping " +
                "WHERE domain_key = ? OR domain_key IS NULL " +
                "ORDER BY confidence DESC, use_count DESC LIMIT 100",
                mapper(), domainKey);
    }

    /**
     * Returns high-confidence mappings for context injection into the SQL planner.
     * Only returns mappings with confidence >= minConfidence and use_count >= minUses.
     */
    public List<LearnedMapping> findForContextInjection(String domainKey,
                                                         double minConfidence,
                                                         int minUses,
                                                         int limit) {
        if (domainKey == null || domainKey.isBlank()) {
            return jdbc.query("""
                    SELECT * FROM nexus_learned_mapping
                    WHERE domain_key IS NULL
                      AND confidence  >= ?
                      AND use_count   >= ?
                    ORDER BY confidence DESC, use_count DESC
                    LIMIT ?
                    """, mapper(), minConfidence, minUses, limit);
        }
        return jdbc.query("""
                SELECT * FROM nexus_learned_mapping
                WHERE (domain_key = ? OR domain_key IS NULL)
                  AND confidence  >= ?
                  AND use_count   >= ?
                ORDER BY confidence DESC, use_count DESC
                LIMIT ?
                """, mapper(), domainKey, minConfidence, minUses, limit);
    }

    /** Batch query: low-confidence mappings eligible for purging. */
    public List<LearnedMapping> findPurgeCandidates(int minUses, double maxConfidence) {
        return jdbc.query("""
                SELECT * FROM nexus_learned_mapping
                WHERE use_count  >= ?
                  AND confidence <= ?
                  AND promoted   = FALSE
                """, mapper(), minUses, maxConfidence);
    }

    /** Batch query: promotion candidates. */
    public List<LearnedMapping> findPromotionCandidates(int minUses, double minConfidence) {
        return jdbc.query("""
                SELECT * FROM nexus_learned_mapping
                WHERE use_count  >= ?
                  AND confidence >= ?
                  AND promoted   = FALSE
                """, mapper(), minUses, minConfidence);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RowMapper<LearnedMapping> mapper() {
        return (rs, i) -> new LearnedMapping(
                rs.getString("mapping_key"),
                rs.getString("domain_key"),
                rs.getString("business_term"),
                rs.getString("sql_pattern"),
                rs.getString("source_run_key"),
                rs.getString("source"),
                rs.getDouble("confidence"),
                rs.getInt("use_count"),
                toInstant(rs.getTimestamp("last_used_at")),
                rs.getBoolean("promoted"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
