package com.sei.nexus.agentmemory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Persistence for the Conversation Business Object Roster (Conversation Memory).
 * Tenant-schema resident; {@code TenantAwareDataSource} routing keeps rows tenant-isolated,
 * exactly like {@link com.sei.nexus.runtime.ExecutionReferenceRepository}.
 *
 * <p>Contains no semantic logic and never inspects the user's question — it is a pure
 * reference index keyed by {@code (conversation_id, entity_key)}. Registration is
 * idempotent by design ({@code ON CONFLICT DO NOTHING}): the first discovery of an
 * entity in a conversation wins, and repeated discovery never duplicates a row.
 */
@Repository
public class ConversationRosterRepository {

    private static final String ENSURE = """
            INSERT INTO nexus_conversation_business_object_roster
                (conversation_id, entity_key, business_name, object_type, discovered_at)
            VALUES (?, ?, ?, ?, NOW())
            ON CONFLICT (conversation_id, entity_key) DO NOTHING
            """;

    private static final String FIND_BY_CONVERSATION = """
            SELECT conversation_id, entity_key, business_name, object_type, discovered_at
              FROM nexus_conversation_business_object_roster
             WHERE conversation_id = ?
             ORDER BY discovered_at
            """;

    private static final String EXISTS_IN_CONVERSATION = """
            SELECT COUNT(*)
              FROM nexus_conversation_business_object_roster
             WHERE conversation_id = ? AND entity_key = ?
            """;

    private final JdbcTemplate jdbc;

    public ConversationRosterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Mechanically ensures {@code entityKey} is present in {@code conversationId}'s roster.
     * Idempotent — a repeated call for the same (conversationId, entityKey) is a no-op.
     * Callers decide WHEN to call this (on successful authoritative retrieval or execution);
     * this method performs no judgment of its own.
     */
    public void ensure(String conversationId, String entityKey, String businessName, String objectType) {
        if (conversationId == null || conversationId.isBlank() || entityKey == null || entityKey.isBlank()) {
            return;
        }
        jdbc.update(ENSURE, conversationId, entityKey, businessName, objectType);
    }

    /** The full roster for a conversation, in discovery order. */
    public List<ConversationRosterEntry> findByConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        return jdbc.query(FIND_BY_CONVERSATION, ROW_MAPPER, conversationId);
    }

    /** Whether {@code entityKey} has already been discovered in {@code conversationId}. */
    public boolean existsInConversation(String conversationId, String entityKey) {
        if (conversationId == null || conversationId.isBlank() || entityKey == null || entityKey.isBlank()) {
            return false;
        }
        Integer count = jdbc.queryForObject(EXISTS_IN_CONVERSATION, Integer.class, conversationId, entityKey);
        return count != null && count > 0;
    }

    private static final RowMapper<ConversationRosterEntry> ROW_MAPPER = (ResultSet rs, int n) ->
            new ConversationRosterEntry(
                    rs.getString("conversation_id"),
                    rs.getString("entity_key"),
                    rs.getString("business_name"),
                    rs.getString("object_type"),
                    inst(rs.getTimestamp("discovered_at")));

    private static Instant inst(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
