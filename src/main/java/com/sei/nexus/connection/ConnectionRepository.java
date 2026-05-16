package com.sei.nexus.connection;

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
public class ConnectionRepository {

    private final JdbcTemplate jdbc;

    public ConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<NexusConnection> findAll() {
        return jdbc.query(
                "SELECT * FROM nexus_connection WHERE status = 'ACTIVE' ORDER BY name",
                rowMapper());
    }

    public Optional<NexusConnection> findByKey(String connectionKey) {
        List<NexusConnection> rows = jdbc.query(
                "SELECT * FROM nexus_connection WHERE connection_key = ?",
                rowMapper(),
                connectionKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Upserts a connection record keyed on connection_key.
     */
    public void save(NexusConnection conn) {
        jdbc.update("""
                INSERT INTO nexus_connection
                    (connection_key, name, connection_type, usage_description,
                     jdbc_url, instance_url, username, encrypted_secret,
                     allowed_schemas, allowed_tables, read_only,
                     last_test_status, last_test_message, last_tested_at,
                     status, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (connection_key) DO UPDATE SET
                    name               = EXCLUDED.name,
                    connection_type    = EXCLUDED.connection_type,
                    usage_description  = EXCLUDED.usage_description,
                    jdbc_url           = EXCLUDED.jdbc_url,
                    instance_url       = EXCLUDED.instance_url,
                    username           = EXCLUDED.username,
                    encrypted_secret   = EXCLUDED.encrypted_secret,
                    allowed_schemas    = EXCLUDED.allowed_schemas,
                    allowed_tables     = EXCLUDED.allowed_tables,
                    read_only          = EXCLUDED.read_only,
                    status             = EXCLUDED.status,
                    updated_at         = NOW()
                """,
                conn.connectionKey(), conn.name(), conn.connectionType(),
                conn.usageDescription(), conn.jdbcUrl(), conn.instanceUrl(),
                conn.username(), conn.encryptedSecret(),
                conn.allowedSchemas(), conn.allowedTables(), conn.readOnly(),
                conn.lastTestStatus(), conn.lastTestMessage(),
                conn.lastTestedAt() != null ? Timestamp.from(conn.lastTestedAt()) : null,
                conn.status(),
                Timestamp.from(conn.createdAt() != null ? conn.createdAt() : Instant.now()),
                Timestamp.from(conn.updatedAt() != null ? conn.updatedAt() : Instant.now()));
    }

    public void archive(String connectionKey) {
        jdbc.update("""
                UPDATE nexus_connection
                   SET status = 'ARCHIVED', updated_at = NOW()
                 WHERE connection_key = ?
                """, connectionKey);
    }

    public void updateTestResult(String connectionKey, String status, String message) {
        jdbc.update("""
                UPDATE nexus_connection
                   SET last_test_status  = ?,
                       last_test_message = ?,
                       last_tested_at    = NOW(),
                       updated_at        = NOW()
                 WHERE connection_key = ?
                """, status, message, connectionKey);
    }

    // ---------------------------------------------------------------------------
    // Row mapper
    // ---------------------------------------------------------------------------

    private RowMapper<NexusConnection> rowMapper() {
        return (rs, rowNum) -> new NexusConnection(
                rs.getString("connection_key"),
                rs.getString("name"),
                rs.getString("connection_type"),
                rs.getString("usage_description"),
                rs.getString("jdbc_url"),
                rs.getString("instance_url"),
                rs.getString("username"),
                rs.getString("encrypted_secret"),
                rs.getString("allowed_schemas"),
                rs.getString("allowed_tables"),
                rs.getBoolean("read_only"),
                rs.getString("last_test_status"),
                rs.getString("last_test_message"),
                toInstant(rs, "last_tested_at"),
                rs.getString("status"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private Instant toInstant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }
}
