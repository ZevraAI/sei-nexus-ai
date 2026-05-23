package com.sei.nexus.semantic;

import com.sei.nexus.common.Keys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class CorrectionRepository {

    private final JdbcTemplate jdbc;

    public CorrectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Correction save(Correction c) {
        String key = c.correctionKey() != null ? c.correctionKey() : Keys.uniqueKey("corr");
        jdbc.update("""
                INSERT INTO nexus_correction
                    (correction_key, conversation_id, original_run_key, correction_run_key,
                     original_interpretation, corrected_interpretation, correction_type,
                     applied_to_context, extracted_at)
                VALUES (?,?,?,?,?,?,?,?,NOW())
                ON CONFLICT (correction_key) DO NOTHING
                """,
                key,
                c.conversationId(),
                c.originalRunKey(),
                c.correctionRunKey(),
                c.originalInterpretation(),
                c.correctedInterpretation(),
                c.correctionType(),
                c.appliedToContext());
        return new Correction(key, c.conversationId(), c.originalRunKey(), c.correctionRunKey(),
                c.originalInterpretation(), c.correctedInterpretation(), c.correctionType(),
                c.appliedToContext(), Instant.now());
    }

    /**
     * Returns recent corrections for a conversation — used to inject
     * "Known corrections" context into the SQL planner.
     */
    public List<Correction> findRecentForConversation(String conversationId, int limit) {
        return jdbc.query("""
                SELECT * FROM nexus_correction
                WHERE conversation_id = ?
                ORDER BY extracted_at DESC
                LIMIT ?
                """, mapper(), conversationId, limit);
    }

    /** All corrections across the tenant for the Learnings panel. */
    public List<Correction> findAll(int limit) {
        return jdbc.query(
                "SELECT * FROM nexus_correction ORDER BY extracted_at DESC LIMIT ?",
                mapper(), limit);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RowMapper<Correction> mapper() {
        return (rs, i) -> new Correction(
                rs.getString("correction_key"),
                rs.getString("conversation_id"),
                rs.getString("original_run_key"),
                rs.getString("correction_run_key"),
                rs.getString("original_interpretation"),
                rs.getString("corrected_interpretation"),
                rs.getString("correction_type"),
                rs.getBoolean("applied_to_context"),
                toInstant(rs.getTimestamp("extracted_at")));
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.now();
    }
}
