package com.sei.nexus.attachment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Repository
public class ChatAttachmentRepository {

    private final JdbcTemplate jdbc;

    public ChatAttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(ChatAttachment a) {
        jdbc.update("""
                INSERT INTO nexus_chat_attachment
                    (attachment_key, conversation_id, file_name, attachment_type,
                     mime_type, file_size_bytes, summary, extracted_text,
                     thumbnail_base64, created_by, created_at, expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (attachment_key) DO UPDATE SET
                    summary          = EXCLUDED.summary,
                    extracted_text   = EXCLUDED.extracted_text,
                    thumbnail_base64 = EXCLUDED.thumbnail_base64
                """,
                a.attachmentKey(), a.conversationId(), a.fileName(), a.attachmentType(),
                a.mimeType(), a.fileSizeBytes(), a.summary(), a.extractedText(),
                a.thumbnailBase64(), a.createdBy(),
                Timestamp.from(a.createdAt() != null ? a.createdAt() : Instant.now()),
                Timestamp.from(a.expiresAt() != null ? a.expiresAt()
                        : Instant.now().plus(24, ChronoUnit.HOURS)));
    }

    public Optional<ChatAttachment> findByKey(String attachmentKey) {
        List<ChatAttachment> rows = jdbc.query(
                "SELECT * FROM nexus_chat_attachment WHERE attachment_key = ?",
                mapper(), attachmentKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ChatAttachment> findByConversation(String conversationId) {
        return jdbc.query("""
                SELECT * FROM nexus_chat_attachment
                 WHERE conversation_id = ? AND expires_at > NOW()
                 ORDER BY created_at DESC
                """, mapper(), conversationId);
    }

    /** Nightly cleanup — remove expired attachments. */
    public int deleteExpired() {
        return jdbc.update("DELETE FROM nexus_chat_attachment WHERE expires_at <= NOW()");
    }

    private RowMapper<ChatAttachment> mapper() {
        return (rs, i) -> new ChatAttachment(
                rs.getString("attachment_key"),
                rs.getString("conversation_id"),
                rs.getString("file_name"),
                rs.getString("attachment_type"),
                rs.getString("mime_type"),
                rs.getObject("file_size_bytes") != null ? rs.getLong("file_size_bytes") : null,
                rs.getString("summary"),
                rs.getString("extracted_text"),
                rs.getString("thumbnail_base64"),
                rs.getString("created_by"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("expires_at")));
    }

    private Instant toInstant(java.sql.Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
