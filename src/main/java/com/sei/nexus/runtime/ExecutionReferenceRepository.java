package com.sei.nexus.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for the immutable {@link ExecutionReference} (Execution Continuity).
 * Tenant-schema resident; {@code TenantAwareDataSource} routing keeps rows tenant-isolated.
 * Rows are write-once (immutable): {@code ON CONFLICT DO NOTHING}.
 */
@Repository
public class ExecutionReferenceRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReferenceRepository.class);

    private static final String INSERT = """
            INSERT INTO nexus_execution_reference
                (execution_id, parent_execution_id, conversation_id, run_key, connection_key,
                 started_at, completed_at, execution_ms, governance_outcome, row_count,
                 result_columns, result_json, sql_reference, contract_id, semantic_hash,
                 retrieval_targets, object_bindings, attribute_bindings, approved_assets)
            VALUES (?,?,?,?,?, ?,?,?,?,?, ?::jsonb,?::jsonb,?,?,?, ?::jsonb,?::jsonb,?::jsonb,?::jsonb)
            ON CONFLICT (execution_id) DO NOTHING
            """;

    private static final String FIND_LATEST_BY_CONVERSATION = """
            SELECT execution_id, parent_execution_id, conversation_id, run_key, connection_key,
                   started_at, completed_at, execution_ms, governance_outcome, row_count,
                   result_columns, result_json, sql_reference, contract_id, semantic_hash,
                   retrieval_targets, object_bindings, attribute_bindings, approved_assets
              FROM nexus_execution_reference
             WHERE conversation_id = ?
             ORDER BY created_at DESC
             LIMIT 1
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ExecutionReferenceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(ExecutionReference r) {
        jdbc.update(INSERT,
                r.executionId(), r.parentExecutionId(), r.conversationId(), r.runKey(), r.connectionKey(),
                ts(r.startedAt()), ts(r.completedAt()), r.executionMs(), r.governanceOutcome(), r.rowCount(),
                json(r.resultColumns()), r.resultJson(), r.sqlReference(), r.contractId(), r.semanticHash(),
                json(r.retrievalTargets()), json(r.businessObjectBindings()),
                json(r.businessAttributeBindings()), json(r.approvedAssets()));
    }

    /** The most recent execution for a conversation — the follow-up grounding substrate. */
    public Optional<ExecutionReference> findLatestByConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Optional.empty();
        List<ExecutionReference> found = jdbc.query(FIND_LATEST_BY_CONVERSATION, ROW_MAPPER, conversationId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private final RowMapper<ExecutionReference> ROW_MAPPER = (ResultSet rs, int n) -> new ExecutionReference(
            rs.getString("execution_id"), rs.getString("parent_execution_id"), rs.getString("conversation_id"),
            rs.getString("run_key"), rs.getString("connection_key"),
            inst(rs.getTimestamp("started_at")), inst(rs.getTimestamp("completed_at")),
            rs.getLong("execution_ms"), rs.getString("governance_outcome"), rs.getInt("row_count"),
            readList(rs.getString("result_columns")), rs.getString("result_json"), rs.getString("sql_reference"),
            rs.getString("contract_id"), rs.getString("semantic_hash"),
            readList(rs.getString("retrieval_targets")), readMap(rs.getString("object_bindings")),
            readMap(rs.getString("attribute_bindings")), readList(rs.getString("approved_assets")));

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
    private static Instant inst(Timestamp t) { return t == null ? null : t.toInstant(); }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (Exception e) { log.warn("ExecutionReference JSON serialize failed: {}", e.getMessage()); return "null"; }
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return mapper.readValue(json, new TypeReference<Map<String, String>>() {}); }
        catch (Exception e) { return Map.of(); }
    }
}
