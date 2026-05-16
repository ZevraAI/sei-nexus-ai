package com.sei.nexus.enterprise;

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

@Repository
public class EnterpriseMapRepository {

    private final JdbcTemplate jdbc;

    public EnterpriseMapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // DataObject
    // -------------------------------------------------------------------------

    public void saveDataObject(DataObject obj) {
        jdbc.update("""
                INSERT INTO nexus_data_object
                    (object_key, domain_key, entity_name, connection_key,
                     schema_name, table_name, business_name, purpose,
                     identifier_columns, status_columns, exception_columns,
                     safe_filter_columns, usage_guidance, filter_guidance,
                     avoid_guidance, row_limit, large_table, scan_status,
                     version_no, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (object_key) DO UPDATE SET
                    domain_key           = EXCLUDED.domain_key,
                    entity_name          = EXCLUDED.entity_name,
                    connection_key       = EXCLUDED.connection_key,
                    schema_name          = EXCLUDED.schema_name,
                    table_name           = EXCLUDED.table_name,
                    business_name        = EXCLUDED.business_name,
                    purpose              = EXCLUDED.purpose,
                    identifier_columns   = EXCLUDED.identifier_columns,
                    status_columns       = EXCLUDED.status_columns,
                    exception_columns    = EXCLUDED.exception_columns,
                    safe_filter_columns  = EXCLUDED.safe_filter_columns,
                    usage_guidance       = EXCLUDED.usage_guidance,
                    filter_guidance      = EXCLUDED.filter_guidance,
                    avoid_guidance       = EXCLUDED.avoid_guidance,
                    row_limit            = EXCLUDED.row_limit,
                    large_table          = EXCLUDED.large_table,
                    scan_status          = EXCLUDED.scan_status,
                    version_no           = EXCLUDED.version_no,
                    updated_at           = NOW()
                """,
                obj.objectKey(), obj.domainKey(), obj.entityName(), obj.connectionKey(),
                obj.schemaName(), obj.tableName(), obj.businessName(), obj.purpose(),
                obj.identifierColumns(), obj.statusColumns(), obj.exceptionColumns(),
                obj.safeFilterColumns(), obj.usageGuidance(), obj.filterGuidance(),
                obj.avoidGuidance(), obj.rowLimit(), obj.largeTable(), obj.scanStatus(),
                obj.versionNo(),
                Timestamp.from(obj.createdAt() != null ? obj.createdAt() : Instant.now()),
                Timestamp.from(obj.updatedAt() != null ? obj.updatedAt() : Instant.now()));
    }

    public Optional<DataObject> findDataObjectByKey(String objectKey) {
        List<DataObject> rows = jdbc.query(
                "SELECT * FROM nexus_data_object WHERE object_key = ?",
                dataObjectMapper(), objectKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<DataObject> findDataObjectsByDomain(String domainKey) {
        return jdbc.query(
                "SELECT * FROM nexus_data_object WHERE domain_key = ? AND scan_status != 'ARCHIVED' ORDER BY entity_name",
                dataObjectMapper(), domainKey);
    }

    /**
     * Returns all data objects whose connection_key appears in the agent's connection_keys list.
     * The agent's connection_keys column is a comma-separated string stored in nexus_agent.
     */
    public List<DataObject> findDataObjectsByAgentConnections(String agentKey) {
        return jdbc.query("""
                SELECT o.*
                  FROM nexus_data_object o
                 WHERE o.scan_status != 'ARCHIVED'
                   AND o.connection_key IN (
                         SELECT TRIM(unnest(string_to_array(a.connection_keys, ',')))
                           FROM nexus_agent a
                          WHERE a.agent_key = ?
                   )
                 ORDER BY o.entity_name
                """, dataObjectMapper(), agentKey);
    }

    public void archiveDataObject(String objectKey) {
        jdbc.update("""
                UPDATE nexus_data_object
                   SET scan_status = 'ARCHIVED', updated_at = NOW()
                 WHERE object_key = ?
                """, objectKey);
    }

    public void saveDataObjectVersion(String objectKey, int versionNo, String snapshotJson, String reason) {
        jdbc.update("""
                INSERT INTO nexus_data_object_version
                    (version_key, object_key, version_no, snapshot_json, reason, created_at)
                VALUES (gen_random_uuid()::text, ?, ?, ?::jsonb, ?, NOW())
                ON CONFLICT (object_key, version_no) DO NOTHING
                """, objectKey, versionNo, snapshotJson, reason);
    }

    public List<Map<String, Object>> findVersionsByObject(String objectKey) {
        return jdbc.queryForList("""
                SELECT version_key, object_key, version_no, reason, created_at
                  FROM nexus_data_object_version
                 WHERE object_key = ?
                 ORDER BY version_no DESC
                """, objectKey);
    }

    public Optional<String> findVersionSnapshot(String objectKey, int versionNo) {
        List<String> rows = jdbc.query("""
                SELECT snapshot_json::text
                  FROM nexus_data_object_version
                 WHERE object_key = ? AND version_no = ?
                """,
                (rs, i) -> rs.getString(1),
                objectKey, versionNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // -------------------------------------------------------------------------
    // DataColumn
    // -------------------------------------------------------------------------

    public void saveColumn(DataColumn col) {
        jdbc.update("""
                INSERT INTO nexus_data_column
                    (column_key, object_key, column_name, data_type, is_nullable,
                     business_meaning, is_identifier, is_status, is_error,
                     is_sensitive, is_filterable, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (object_key, column_name) DO UPDATE SET
                    data_type        = EXCLUDED.data_type,
                    is_nullable      = EXCLUDED.is_nullable,
                    business_meaning = CASE
                                         WHEN nexus_data_column.business_meaning IS NOT NULL
                                              AND nexus_data_column.business_meaning != ''
                                         THEN nexus_data_column.business_meaning
                                         ELSE EXCLUDED.business_meaning
                                       END,
                    is_identifier    = EXCLUDED.is_identifier,
                    is_status        = EXCLUDED.is_status,
                    is_error         = EXCLUDED.is_error,
                    is_sensitive     = EXCLUDED.is_sensitive,
                    is_filterable    = EXCLUDED.is_filterable,
                    updated_at       = NOW()
                """,
                col.columnKey(), col.objectKey(), col.columnName(), col.dataType(),
                col.isNullable(), col.businessMeaning(),
                col.isIdentifier(), col.isStatus(), col.isError(),
                col.isSensitive(), col.isFilterable(),
                Timestamp.from(col.createdAt() != null ? col.createdAt() : Instant.now()),
                Timestamp.from(col.updatedAt() != null ? col.updatedAt() : Instant.now()));
    }

    public void updateColumn(String objectKey, String columnName, String businessMeaning,
                             boolean isIdentifier, boolean isStatus, boolean isError,
                             boolean isSensitive, boolean isFilterable) {
        jdbc.update("""
                UPDATE nexus_data_column
                   SET business_meaning = ?,
                       is_identifier    = ?,
                       is_status        = ?,
                       is_error         = ?,
                       is_sensitive     = ?,
                       is_filterable    = ?,
                       updated_at       = NOW()
                 WHERE object_key = ? AND column_name = ?
                """, businessMeaning, isIdentifier, isStatus, isError,
                isSensitive, isFilterable, objectKey, columnName);
    }

    public List<DataColumn> findColumnsByObject(String objectKey) {
        return jdbc.query(
                "SELECT * FROM nexus_data_column WHERE object_key = ? ORDER BY column_name",
                columnMapper(), objectKey);
    }

    public void deleteColumnsByObject(String objectKey) {
        jdbc.update("DELETE FROM nexus_data_column WHERE object_key = ?", objectKey);
    }

    // -------------------------------------------------------------------------
    // OperationalNote
    // -------------------------------------------------------------------------

    public void saveNote(OperationalNote note) {
        jdbc.update("""
                INSERT INTO nexus_operational_note
                    (note_key, domain_key, entity_name, object_key, title,
                     note_text, tags, status, created_by, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (note_key) DO UPDATE SET
                    title       = EXCLUDED.title,
                    note_text   = EXCLUDED.note_text,
                    tags        = EXCLUDED.tags,
                    status      = EXCLUDED.status,
                    updated_at  = NOW()
                """,
                note.noteKey(), note.domainKey(), note.entityName(), note.objectKey(),
                note.title(), note.noteText(), note.tags(), note.status(), note.createdBy(),
                Timestamp.from(note.createdAt() != null ? note.createdAt() : Instant.now()),
                Timestamp.from(note.updatedAt() != null ? note.updatedAt() : Instant.now()));
    }

    public List<OperationalNote> findNotesByDomain(String domainKey) {
        return jdbc.query(
                "SELECT * FROM nexus_operational_note WHERE domain_key = ? AND status = 'ACTIVE' ORDER BY created_at DESC",
                noteMapper(), domainKey);
    }

    public void archiveNote(String noteKey) {
        jdbc.update("""
                UPDATE nexus_operational_note
                   SET status = 'ARCHIVED', updated_at = NOW()
                 WHERE note_key = ?
                """, noteKey);
    }

    public Optional<DataObject> findObjectByTableName(String connectionKey, String schemaName, String tableName) {
        List<DataObject> rows = jdbc.query("""
                SELECT * FROM nexus_data_object
                 WHERE connection_key = ?
                   AND schema_name    = ?
                   AND table_name     = ?
                   AND scan_status   != 'ARCHIVED'
                """, dataObjectMapper(), connectionKey, schemaName, tableName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // -------------------------------------------------------------------------
    // Row mappers
    // -------------------------------------------------------------------------

    private RowMapper<DataObject> dataObjectMapper() {
        return (rs, rowNum) -> new DataObject(
                rs.getString("object_key"),
                rs.getString("domain_key"),
                rs.getString("entity_name"),
                rs.getString("connection_key"),
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("business_name"),
                rs.getString("purpose"),
                rs.getString("identifier_columns"),
                rs.getString("status_columns"),
                rs.getString("exception_columns"),
                rs.getString("safe_filter_columns"),
                rs.getString("usage_guidance"),
                rs.getString("filter_guidance"),
                rs.getString("avoid_guidance"),
                rs.getObject("row_limit") != null ? rs.getInt("row_limit") : null,
                rs.getBoolean("large_table"),
                rs.getString("scan_status"),
                rs.getInt("version_no"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private RowMapper<DataColumn> columnMapper() {
        return (rs, rowNum) -> new DataColumn(
                rs.getString("column_key"),
                rs.getString("object_key"),
                rs.getString("column_name"),
                rs.getString("data_type"),
                rs.getBoolean("is_nullable"),
                rs.getString("business_meaning"),
                rs.getBoolean("is_identifier"),
                rs.getBoolean("is_status"),
                rs.getBoolean("is_error"),
                rs.getBoolean("is_sensitive"),
                rs.getBoolean("is_filterable"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private RowMapper<OperationalNote> noteMapper() {
        return (rs, rowNum) -> new OperationalNote(
                rs.getString("note_key"),
                rs.getString("domain_key"),
                rs.getString("entity_name"),
                rs.getString("object_key"),
                rs.getString("title"),
                rs.getString("note_text"),
                rs.getString("tags"),
                rs.getString("status"),
                rs.getString("created_by"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private Instant toInstant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }
}
