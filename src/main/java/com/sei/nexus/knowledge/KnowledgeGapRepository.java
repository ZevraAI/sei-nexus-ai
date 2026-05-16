package com.sei.nexus.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class KnowledgeGapRepository {

    private static final String FIND_BY_STATUS =
            "SELECT gap_key, domain_key, gap_type, run_key, question, gap_description, " +
            "proposal_text, status, resolved_by, resolution_note, created_at, updated_at " +
            "FROM nexus_knowledge_gap WHERE status = ? ORDER BY created_at DESC";

    private static final String FIND_BY_DOMAIN_AND_STATUS =
            "SELECT gap_key, domain_key, gap_type, run_key, question, gap_description, " +
            "proposal_text, status, resolved_by, resolution_note, created_at, updated_at " +
            "FROM nexus_knowledge_gap WHERE domain_key = ? AND status = ? ORDER BY created_at DESC";

    private static final String INSERT_GAP =
            "INSERT INTO nexus_knowledge_gap (gap_key, domain_key, gap_type, run_key, question, " +
            "gap_description, proposal_text, status, resolved_by, resolution_note, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";

    private static final String UPDATE_STATUS =
            "UPDATE nexus_knowledge_gap SET status = ?, resolved_by = ?, resolution_note = ?, " +
            "updated_at = NOW() WHERE gap_key = ?";

    private final JdbcTemplate jdbc;

    public KnowledgeGapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<KnowledgeGap> findByStatus(String status) {
        return jdbc.query(FIND_BY_STATUS, gapMapper(), status);
    }

    public List<KnowledgeGap> findByDomainAndStatus(String domainKey, String status) {
        return jdbc.query(FIND_BY_DOMAIN_AND_STATUS, gapMapper(), domainKey, status);
    }

    public void save(KnowledgeGap gap) {
        jdbc.update(INSERT_GAP,
                gap.gapKey(),
                gap.domainKey(),
                gap.gapType(),
                gap.runKey(),
                gap.question(),
                gap.gapDescription(),
                gap.proposalText(),
                gap.status() != null ? gap.status() : "OPEN",
                gap.resolvedBy(),
                gap.resolutionNote());
    }

    public int updateStatus(String gapKey, String status, String resolvedBy, String resolutionNote) {
        return jdbc.update(UPDATE_STATUS, status, resolvedBy, resolutionNote, gapKey);
    }

    private RowMapper<KnowledgeGap> gapMapper() {
        return (rs, rowNum) -> new KnowledgeGap(
                rs.getString("gap_key"),
                rs.getString("domain_key"),
                rs.getString("gap_type"),
                rs.getString("run_key"),
                rs.getString("question"),
                rs.getString("gap_description"),
                rs.getString("proposal_text"),
                rs.getString("status"),
                rs.getString("resolved_by"),
                rs.getString("resolution_note"),
                toOffsetDateTime(rs, "created_at"),
                toOffsetDateTime(rs, "updated_at")
        );
    }

    private OffsetDateTime toOffsetDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;
    }
}
