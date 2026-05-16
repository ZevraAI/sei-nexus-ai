package com.sei.nexus.run;

import com.sei.nexus.common.Keys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class RunRepository {

    private static final String INSERT_RUN =
            "INSERT INTO nexus_run (run_key, conversation_id, agent_key, domain_key, user_email, " +
            "question, answer, decision_type, status, result_snapshot, created_at, completed_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, NOW(), ?)";

    private static final String UPDATE_RUN =
            "UPDATE nexus_run SET answer = ?, decision_type = ?, status = ?, " +
            "result_snapshot = ?::jsonb, completed_at = NOW() WHERE run_key = ?";

    private static final String FIND_BY_KEY =
            "SELECT run_key, conversation_id, agent_key, domain_key, user_email, question, answer, " +
            "decision_type, status, result_snapshot, created_at, completed_at " +
            "FROM nexus_run WHERE run_key = ?";

    private static final String FIND_CONVERSATION_RUNS =
            "SELECT run_key, conversation_id, agent_key, domain_key, user_email, question, answer, " +
            "decision_type, status, result_snapshot, created_at, completed_at " +
            "FROM nexus_run WHERE conversation_id = ? ORDER BY created_at ASC LIMIT ?";

    private static final String LATEST_RESULT_SNAPSHOT =
            "SELECT result_snapshot FROM nexus_run " +
            "WHERE conversation_id = ? AND status = 'COMPLETE' AND result_snapshot IS NOT NULL " +
            "ORDER BY completed_at DESC LIMIT 1";

    private static final String INSERT_EVIDENCE =
            "INSERT INTO nexus_evidence (evidence_key, run_key, evidence_type, payload_json, created_at) " +
            "VALUES (?, ?, ?, ?::jsonb, NOW())";

    private static final String FIND_CONVERSATIONS =
            "SELECT r.conversation_id, " +
            "MIN(r.created_at) AS started_at, " +
            "MAX(r.created_at) AS last_activity, " +
            "COUNT(*) AS run_count, " +
            "MIN(r.question) AS first_question, " +
            "MAX(r.user_email) AS user_email, " +
            "BOOL_OR(p.conversation_id IS NOT NULL) AS pinned " +
            "FROM nexus_run r " +
            "LEFT JOIN nexus_conversation_pin p ON p.conversation_id = r.conversation_id " +
            "WHERE r.user_email = ? AND r.created_at > NOW() - INTERVAL '1 day' * ? " +
            "GROUP BY r.conversation_id ORDER BY last_activity DESC";

    private final JdbcTemplate jdbc;

    public RunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(NexusRun run) {
        Timestamp completedAt = run.completedAt() != null
                ? Timestamp.from(run.completedAt().toInstant()) : null;
        // Blank domain_key violates the FK — treat it as null (nullable column)
        String domainKey = (run.domainKey() == null || run.domainKey().isBlank())
                ? null : run.domainKey();
        jdbc.update(INSERT_RUN,
                run.runKey(),
                run.conversationId(),
                run.agentKey(),
                domainKey,
                run.userEmail(),
                run.question(),
                run.answer(),
                run.decisionType(),
                run.status(),
                run.resultSnapshot(),
                completedAt);
    }

    public void update(String runKey, String answer, String decisionType,
                       String status, String resultSnapshot) {
        jdbc.update(UPDATE_RUN, answer, decisionType, status, resultSnapshot, runKey);
    }

    public Optional<NexusRun> findByKey(String runKey) {
        List<NexusRun> results = jdbc.query(FIND_BY_KEY, runMapper(), runKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<NexusRun> findConversationRuns(String conversationId, int limit) {
        return jdbc.query(FIND_CONVERSATION_RUNS, runMapper(), conversationId, limit);
    }

    public Optional<String> latestResultSnapshot(String conversationId) {
        List<String> results = jdbc.query(LATEST_RESULT_SNAPSHOT,
                (rs, rowNum) -> rs.getString("result_snapshot"), conversationId);
        return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0));
    }

    public void saveEvidence(String evidenceKey, String runKey, String evidenceType, String payloadJson) {
        jdbc.update(INSERT_EVIDENCE, evidenceKey, runKey, evidenceType, payloadJson);
    }

    public List<Map<String, Object>> findConversations(String userEmail, int days) {
        return jdbc.query(FIND_CONVERSATIONS, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("conversation_id", rs.getString("conversation_id"));
            row.put("started_at", toOffsetDateTime(rs, "started_at"));
            row.put("last_activity", toOffsetDateTime(rs, "last_activity"));
            row.put("run_count", rs.getInt("run_count"));
            row.put("first_question", rs.getString("first_question"));
            row.put("user_email", rs.getString("user_email"));
            row.put("pinned", rs.getBoolean("pinned"));
            return row;
        }, userEmail, days);
    }

    private RowMapper<NexusRun> runMapper() {
        return (rs, rowNum) -> new NexusRun(
                rs.getString("run_key"),
                rs.getString("conversation_id"),
                rs.getString("agent_key"),
                rs.getString("domain_key"),
                rs.getString("user_email"),
                rs.getString("question"),
                rs.getString("answer"),
                rs.getString("decision_type"),
                rs.getString("status"),
                rs.getString("result_snapshot"),
                toOffsetDateTime(rs, "created_at"),
                toOffsetDateTime(rs, "completed_at")
        );
    }

    private OffsetDateTime toOffsetDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;
    }
}
