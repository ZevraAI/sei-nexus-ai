package com.sei.nexus.instruction;

import com.sei.nexus.common.PgVector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Persistence and vector arithmetic for {@code nexus_instruction} (V050), tenant-scoped through the
 * routing {@link JdbcTemplate}. No semantics: which instructions are approved is a human decision,
 * and which of them apply to a question is decided by the model reading the few that retrieval
 * returns.
 */
@Repository
public class InstructionRepository {

    /** An approved instruction and its cosine similarity to the question, in [-1, 1]. */
    public record Scored(String instructionText, double similarity) {}

    private static final String COLUMNS =
            "instruction_id, instruction_text, status, always_apply, submitted_by, reviewed_by, "
            + "created_at, reviewed_at";

    private final JdbcTemplate jdbc;

    public InstructionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String text, String submittedBy) {
        jdbc.update("INSERT INTO nexus_instruction (instruction_id, instruction_text, status, submitted_by) "
                + "VALUES (?, ?, 'PENDING', ?)", id, text, submittedBy);
    }

    public Optional<Instruction> findById(String id) {
        List<Instruction> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM nexus_instruction WHERE instruction_id = ?", mapper(), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Instruction> findByStatus(String status) {
        return jdbc.query("SELECT " + COLUMNS + " FROM nexus_instruction WHERE status = ? "
                + "ORDER BY created_at DESC", mapper(), status);
    }

    /** Approve (with the possibly edited text), storing the embedding computed for that text. */
    public int approve(String id, String text, boolean alwaysApply, float[] embedding, String reviewer) {
        return jdbc.update("""
                UPDATE nexus_instruction
                   SET instruction_text = ?, always_apply = ?, embedding = ?::vector, status = 'APPROVED',
                       reviewed_by = ?, reviewed_at = NOW(), updated_at = NOW()
                 WHERE instruction_id = ?
                """, text, alwaysApply, PgVector.toLiteral(embedding), reviewer, id);
    }

    /** Remove an instruction from use (rejecting a pending one, or retiring an approved one). */
    public int archive(String id, String reviewer) {
        return jdbc.update("""
                UPDATE nexus_instruction
                   SET status = 'ARCHIVED', embedding = NULL, reviewed_by = ?, reviewed_at = NOW(),
                       updated_at = NOW()
                 WHERE instruction_id = ?
                """, reviewer, id);
    }

    public int countApproved() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nexus_instruction WHERE status = 'APPROVED'", Integer.class);
        return n == null ? 0 : n;
    }

    public int countApprovedAlways() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nexus_instruction WHERE status = 'APPROVED' AND always_apply",
                Integer.class);
        return n == null ? 0 : n;
    }

    /** Approved always-apply instructions, oldest first, at most {@code limit}. */
    public List<String> findAlwaysApplied(int limit) {
        return jdbc.query("SELECT instruction_text FROM nexus_instruction "
                        + "WHERE status = 'APPROVED' AND always_apply ORDER BY reviewed_at, instruction_id LIMIT ?",
                (rs, i) -> rs.getString(1), limit);
    }

    /**
     * The approved, non-always instructions nearest to {@code queryEmbedding} whose similarity is at
     * least {@code minSimilarity}, nearest first; ties broken by id so the order is deterministic.
     */
    public List<Scored> searchNearest(float[] queryEmbedding, int topK, double minSimilarity) {
        String vec = PgVector.toLiteral(queryEmbedding);
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT instruction_text, 1 - (embedding <=> ?::vector) AS similarity
                      FROM nexus_instruction
                     WHERE status = 'APPROVED' AND NOT always_apply AND embedding IS NOT NULL
                       AND 1 - (embedding <=> ?::vector) >= ?
                     ORDER BY embedding <=> ?::vector, instruction_id
                     LIMIT ?
                    """);
            ps.setString(1, vec);
            ps.setString(2, vec);
            ps.setDouble(3, minSimilarity);
            ps.setString(4, vec);
            ps.setInt(5, topK);
            return ps;
        }, (rs, i) -> new Scored(rs.getString("instruction_text"), rs.getDouble("similarity")));
    }

    private RowMapper<Instruction> mapper() {
        return (rs, i) -> new Instruction(
                rs.getString("instruction_id"), rs.getString("instruction_text"),
                rs.getString("status"), rs.getBoolean("always_apply"),
                rs.getString("submitted_by"), rs.getString("reviewed_by"),
                toOdt(rs.getTimestamp("created_at")), toOdt(rs.getTimestamp("reviewed_at")));
    }

    private static OffsetDateTime toOdt(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
