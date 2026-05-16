package com.sei.nexus.reasoning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ReasoningRepository {

    // ── Session SQL ──────────────────────────────────────────────────────────
    private static final String INSERT_SESSION =
            "INSERT INTO nexus_reasoning_session " +
            "(session_key, run_key, conversation_id, agent_key, domain_key, " +
            " initial_question, investigation_plan, status, conclusion, confidence_score, " +
            " started_at, concluded_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SESSION_STATUS =
            "UPDATE nexus_reasoning_session SET status=?, conclusion=?, confidence_score=?, " +
            "concluded_at=? WHERE session_key=?";

    private static final String FIND_SESSION_BY_RUN =
            "SELECT session_key, run_key, conversation_id, agent_key, domain_key, " +
            "initial_question, investigation_plan, status, conclusion, confidence_score, " +
            "started_at, concluded_at FROM nexus_reasoning_session WHERE run_key = ?";

    private static final String FIND_SESSIONS_BY_CONVERSATION =
            "SELECT session_key, run_key, conversation_id, agent_key, domain_key, " +
            "initial_question, investigation_plan, status, conclusion, confidence_score, " +
            "started_at, concluded_at FROM nexus_reasoning_session " +
            "WHERE conversation_id = ? ORDER BY started_at DESC";

    // ── Step SQL ─────────────────────────────────────────────────────────────
    private static final String INSERT_STEP =
            "INSERT INTO nexus_reasoning_step " +
            "(step_key, session_key, step_no, step_type, instruction, evidence_used, " +
            " outcome, confidence_delta, execution_key, executed_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String FIND_STEPS_BY_SESSION =
            "SELECT step_key, session_key, step_no, step_type, instruction, evidence_used, " +
            "outcome, confidence_delta, execution_key, executed_at " +
            "FROM nexus_reasoning_step WHERE session_key = ? ORDER BY step_no ASC";

    // ── Hypothesis SQL ───────────────────────────────────────────────────────
    private static final String INSERT_HYPOTHESIS =
            "INSERT INTO nexus_hypothesis " +
            "(hypothesis_key, session_key, hypothesis_text, confidence, supporting_evidence, " +
            " contradicting_evidence, status, formed_at, resolved_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_HYPOTHESIS =
            "UPDATE nexus_hypothesis SET confidence=?, status=?, supporting_evidence=?, " +
            "contradicting_evidence=?, resolved_at=? WHERE hypothesis_key=?";

    private static final String FIND_HYPOTHESES_BY_SESSION =
            "SELECT hypothesis_key, session_key, hypothesis_text, confidence, " +
            "supporting_evidence, contradicting_evidence, status, formed_at, resolved_at " +
            "FROM nexus_hypothesis WHERE session_key = ? ORDER BY formed_at ASC";

    // ── Finding SQL ──────────────────────────────────────────────────────────
    private static final String INSERT_FINDING =
            "INSERT INTO nexus_operational_finding " +
            "(finding_key, domain_key, agent_key, finding_type, title, description, " +
            " evidence_summary, related_entity_keys, confidence, status, " +
            " first_observed_at, last_confirmed_at, resolved_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_FINDING =
            "UPDATE nexus_operational_finding SET status=?, last_confirmed_at=?, resolved_at=? " +
            "WHERE finding_key=?";

    private static final String FIND_FINDINGS_BY_DOMAIN =
            "SELECT finding_key, domain_key, agent_key, finding_type, title, description, " +
            "evidence_summary, related_entity_keys, confidence, status, " +
            "first_observed_at, last_confirmed_at, resolved_at " +
            "FROM nexus_operational_finding WHERE domain_key = ? AND status = ? " +
            "ORDER BY last_confirmed_at DESC";

    private static final String FIND_FINDINGS_BY_DOMAIN_ALL_STATUS =
            "SELECT finding_key, domain_key, agent_key, finding_type, title, description, " +
            "evidence_summary, related_entity_keys, confidence, status, " +
            "first_observed_at, last_confirmed_at, resolved_at " +
            "FROM nexus_operational_finding WHERE domain_key = ? " +
            "ORDER BY last_confirmed_at DESC";

    private static final String FIND_FINDING_BY_KEY =
            "SELECT finding_key, domain_key, agent_key, finding_type, title, description, " +
            "evidence_summary, related_entity_keys, confidence, status, " +
            "first_observed_at, last_confirmed_at, resolved_at " +
            "FROM nexus_operational_finding WHERE finding_key = ?";

    private static final String FIND_RECENT_FINDINGS =
            "SELECT finding_key, domain_key, agent_key, finding_type, title, description, " +
            "evidence_summary, related_entity_keys, confidence, status, " +
            "first_observed_at, last_confirmed_at, resolved_at " +
            "FROM nexus_operational_finding WHERE domain_key = ANY(?::text[]) " +
            "AND status IN ('ACTIVE','MONITORING') ORDER BY last_confirmed_at DESC LIMIT ?";

    private final JdbcTemplate jdbc;

    public ReasoningRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Session ──────────────────────────────────────────────────────────────

    public void saveSession(ReasoningSession s) {
        jdbc.update(INSERT_SESSION,
                s.sessionKey(), s.runKey(), s.conversationId(), s.agentKey(), s.domainKey(),
                s.initialQuestion(), s.investigationPlan(), s.status(), s.conclusion(),
                s.confidenceScore(), toTimestamp(s.startedAt()), toTimestamp(s.concludedAt()));
    }

    public void updateSessionStatus(String sessionKey, String status, String conclusion,
                                    Double confidence, Instant concludedAt) {
        jdbc.update(UPDATE_SESSION_STATUS, status, conclusion, confidence,
                toTimestamp(concludedAt), sessionKey);
    }

    public Optional<ReasoningSession> findSessionByRunKey(String runKey) {
        List<ReasoningSession> rows = jdbc.query(FIND_SESSION_BY_RUN, sessionMapper(), runKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ReasoningSession> findSessionsByConversation(String conversationId) {
        return jdbc.query(FIND_SESSIONS_BY_CONVERSATION, sessionMapper(), conversationId);
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    public void saveStep(ReasoningStep step) {
        jdbc.update(INSERT_STEP,
                step.stepKey(), step.sessionKey(), step.stepNo(), step.stepType(),
                step.instruction(), step.evidenceUsed(), step.outcome(),
                step.confidenceDelta(), step.executionKey(), toTimestamp(step.executedAt()));
    }

    public List<ReasoningStep> findStepsBySession(String sessionKey) {
        return jdbc.query(FIND_STEPS_BY_SESSION, stepMapper(), sessionKey);
    }

    // ── Hypotheses ───────────────────────────────────────────────────────────

    public void saveHypothesis(Hypothesis h) {
        jdbc.update(INSERT_HYPOTHESIS,
                h.hypothesisKey(), h.sessionKey(), h.hypothesisText(), h.confidence(),
                h.supportingEvidence(), h.contradictingEvidence(), h.status(),
                toTimestamp(h.formedAt()), toTimestamp(h.resolvedAt()));
    }

    public void updateHypothesis(String hypothesisKey, Double confidence, String status,
                                  String supporting, String contradicting) {
        jdbc.update(UPDATE_HYPOTHESIS, confidence, status, supporting, contradicting,
                toTimestamp(Instant.now()), hypothesisKey);
    }

    public List<Hypothesis> findHypothesesBySession(String sessionKey) {
        return jdbc.query(FIND_HYPOTHESES_BY_SESSION, hypothesisMapper(), sessionKey);
    }

    // ── Findings ─────────────────────────────────────────────────────────────

    public void saveFinding(OperationalFinding f) {
        jdbc.update(INSERT_FINDING,
                f.findingKey(), f.domainKey(), f.agentKey(), f.findingType(),
                f.title(), f.description(), f.evidenceSummary(), f.relatedEntityKeys(),
                f.confidence(), f.status(),
                toTimestamp(f.firstObservedAt()), toTimestamp(f.lastConfirmedAt()),
                toTimestamp(f.resolvedAt()));
    }

    public void updateFindingStatus(String findingKey, String status, Instant resolvedAt) {
        jdbc.update(UPDATE_FINDING, status, toTimestamp(Instant.now()), toTimestamp(resolvedAt), findingKey);
    }

    public List<OperationalFinding> findFindingsByDomain(String domainKey, String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return jdbc.query(FIND_FINDINGS_BY_DOMAIN_ALL_STATUS, findingMapper(), domainKey);
        }
        return jdbc.query(FIND_FINDINGS_BY_DOMAIN, findingMapper(), domainKey, status);
    }

    public Optional<OperationalFinding> findFindingByKey(String findingKey) {
        List<OperationalFinding> rows = jdbc.query(FIND_FINDING_BY_KEY, findingMapper(), findingKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<OperationalFinding> findRecentFindings(List<String> domainKeys, int limit) {
        if (domainKeys == null || domainKeys.isEmpty()) return List.of();
        return jdbc.query(FIND_RECENT_FINDINGS, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("text", domainKeys.toArray()));
            ps.setInt(2, limit);
        }, findingMapper());
    }

    // ── Row Mappers ───────────────────────────────────────────────────────────

    private RowMapper<ReasoningSession> sessionMapper() {
        return (rs, rowNum) -> {
            Double conf = rs.getDouble("confidence_score");
            if (rs.wasNull()) conf = null;
            return new ReasoningSession(
                    rs.getString("session_key"),
                    rs.getString("run_key"),
                    rs.getString("conversation_id"),
                    rs.getString("agent_key"),
                    rs.getString("domain_key"),
                    rs.getString("initial_question"),
                    rs.getString("investigation_plan"),
                    rs.getString("status"),
                    rs.getString("conclusion"),
                    conf,
                    toInstant(rs, "started_at"),
                    toInstant(rs, "concluded_at"));
        };
    }

    private RowMapper<ReasoningStep> stepMapper() {
        return (rs, rowNum) -> {
            Double cd = rs.getDouble("confidence_delta");
            if (rs.wasNull()) cd = null;
            return new ReasoningStep(
                    rs.getString("step_key"),
                    rs.getString("session_key"),
                    rs.getInt("step_no"),
                    rs.getString("step_type"),
                    rs.getString("instruction"),
                    rs.getString("evidence_used"),
                    rs.getString("outcome"),
                    cd,
                    rs.getString("execution_key"),
                    toInstant(rs, "executed_at"));
        };
    }

    private RowMapper<Hypothesis> hypothesisMapper() {
        return (rs, rowNum) -> {
            Double conf = rs.getDouble("confidence");
            if (rs.wasNull()) conf = null;
            return new Hypothesis(
                    rs.getString("hypothesis_key"),
                    rs.getString("session_key"),
                    rs.getString("hypothesis_text"),
                    conf,
                    rs.getString("supporting_evidence"),
                    rs.getString("contradicting_evidence"),
                    rs.getString("status"),
                    toInstant(rs, "formed_at"),
                    toInstant(rs, "resolved_at"));
        };
    }

    private RowMapper<OperationalFinding> findingMapper() {
        return (rs, rowNum) -> {
            Double conf = rs.getDouble("confidence");
            if (rs.wasNull()) conf = null;
            return new OperationalFinding(
                    rs.getString("finding_key"),
                    rs.getString("domain_key"),
                    rs.getString("agent_key"),
                    rs.getString("finding_type"),
                    rs.getString("title"),
                    rs.getString("description"),
                    rs.getString("evidence_summary"),
                    rs.getString("related_entity_keys"),
                    conf,
                    rs.getString("status"),
                    toInstant(rs, "first_observed_at"),
                    toInstant(rs, "last_confirmed_at"),
                    toInstant(rs, "resolved_at"));
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private Instant toInstant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }
}
