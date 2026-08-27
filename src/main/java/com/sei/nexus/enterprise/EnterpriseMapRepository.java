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
     * Global Concept Resolution (Phase 1, read-only): all non-archived data objects for one
     * connection — the physical-object universe a connection-scoped resolver evaluates against.
     * Purely additive; no existing caller uses this method.
     */
    public List<DataObject> findDataObjectsByConnection(String connectionKey) {
        return jdbc.query(
                "SELECT * FROM nexus_data_object WHERE connection_key = ? AND scan_status != 'ARCHIVED' ORDER BY entity_name",
                dataObjectMapper(), connectionKey);
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

    /**
     * Returns the approved (non-ARCHIVED) data objects whose connection_key is in the
     * supplied list — the authoritative business-object surface for a ZevraAgent's
     * connections (ADR-0003 A9). Unlike findDataObjectsByAgentConnections(agentKey),
     * which resolves nexus_agent, this takes the connection keys directly, so it fits the
     * ZevraAgent model (nexus_zevra_agent.connection_keys). Consumed only by AgentBrain.
     */
    /**
     * Returns all non-archived data objects belonging to any of the given business domains.
     * The batch form of {@link #findDataObjectsByDomain(String)} — one query instead of one
     * per domain. An object carries a single {@code domain_key}, so the union cannot duplicate.
     */
    public List<DataObject> findDataObjectsByDomainKeys(List<String> domainKeys) {
        if (domainKeys == null || domainKeys.isEmpty()) return List.of();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < domainKeys.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        return jdbc.query(
                "SELECT * FROM nexus_data_object WHERE scan_status != 'ARCHIVED' "
                        + "AND domain_key IN (" + placeholders + ") ORDER BY entity_name",
                dataObjectMapper(), domainKeys.toArray());
    }

    public List<DataObject> findDataObjectsByConnectionKeys(List<String> connectionKeys) {
        if (connectionKeys == null || connectionKeys.isEmpty()) return List.of();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < connectionKeys.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        return jdbc.query(
                "SELECT * FROM nexus_data_object WHERE scan_status != 'ARCHIVED' "
                        + "AND connection_key IN (" + placeholders + ") ORDER BY entity_name",
                dataObjectMapper(), connectionKeys.toArray());
    }

    /**
     * Concept-Scoped Metadata Narrowing, Stage 2: the exact, targeted set of physical data
     * objects a Stage 1 concept selection resolved to (via {@code
     * SemanticRepository#findEntitiesByConnectionAndConcepts}'s {@code primary_object_key}
     * values) — never the whole connection's inventory. Mirrors {@link
     * #findDataObjectsByConnectionKeys} exactly, just keyed by object_key instead of
     * connection_key.
     */
    public List<DataObject> findDataObjectsByKeys(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) return List.of();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < objectKeys.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        return jdbc.query(
                "SELECT * FROM nexus_data_object WHERE scan_status != 'ARCHIVED' "
                        + "AND object_key IN (" + placeholders + ") ORDER BY entity_name",
                dataObjectMapper(), objectKeys.toArray());
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
                     is_sensitive, is_filterable, udt_name, value_domain_key,
                     role_source, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
                    udt_name         = EXCLUDED.udt_name,
                    value_domain_key = EXCLUDED.value_domain_key,
                    role_source      = EXCLUDED.role_source,
                    updated_at       = NOW()
                """,
                col.columnKey(), col.objectKey(), col.columnName(), col.dataType(),
                col.isNullable(), col.businessMeaning(),
                col.isIdentifier(), col.isStatus(), col.isError(),
                col.isSensitive(), col.isFilterable(),
                col.udtName(), col.valueDomainKey(),
                col.roleSource() != null ? col.roleSource() : DataColumn.ROLE_INFERRED,
                Timestamp.from(col.createdAt() != null ? col.createdAt() : Instant.now()),
                Timestamp.from(col.updatedAt() != null ? col.updatedAt() : Instant.now()));
    }

    /** Human role assertion (PRO-29): the edit is CONFIRMED — authoritative over
     *  every producer; scans never recompute these flags away. */
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
                       role_source      = 'CONFIRMED',
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
    // ValueDomain (PRO-10)
    // -------------------------------------------------------------------------

    /**
     * Inserts or refreshes a value domain, keyed by its natural identity
     * (connection, source schema, domain name, source). Returns the persisted
     * domain_value_key — the existing one when the domain was already known,
     * so re-scans never create duplicates.
     *
     * <p><b>Additive retention (OBSERVED domains).</b> Observed value domains are sampled via
     * {@code SELECT DISTINCT … LIMIT}, so a value present in an earlier scan can be absent from a
     * later one (rows deleted, temporary absence, sampling variance). Overwriting would silently
     * lose it and orphan any Business Value mapping keyed on that physical value. On conflict the
     * stored set therefore becomes the <b>union</b> of previously-observed and newly-observed
     * values (deduplicated, sorted) — previously observed values are never lost, so downstream
     * mappings stay stable over time. The union is computed atomically in the {@code ON CONFLICT}
     * update, so concurrent re-scans of a shared domain cannot lose an update.
     *
     * <p><b>AUTHORITATIVE (enum) domains replace.</b> Enum domains come from a complete catalog
     * read (not a sample), so the freshly-read set is the source of truth and is written verbatim,
     * preserving enum order.
     */
    public String upsertValueDomain(ValueDomain d) {
        return jdbc.queryForObject("""
                INSERT INTO nexus_value_domain
                    (domain_value_key, connection_key, source_schema, domain_name,
                     source, is_authoritative, domain_values, scanned_at, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?::jsonb,NOW(),NOW(),NOW())
                ON CONFLICT (connection_key, source_schema, domain_name, source) DO UPDATE SET
                    domain_values = CASE
                        WHEN EXCLUDED.source = 'OBSERVED' THEN (
                            SELECT COALESCE(jsonb_agg(DISTINCT elem ORDER BY elem), '[]'::jsonb)
                            FROM jsonb_array_elements_text(
                                nexus_value_domain.domain_values || EXCLUDED.domain_values) AS elem)
                        ELSE EXCLUDED.domain_values
                    END,
                    is_authoritative = EXCLUDED.is_authoritative,
                    scanned_at       = NOW(),
                    updated_at       = NOW()
                RETURNING domain_value_key
                """,
                String.class,
                d.domainValueKey(), d.connectionKey(), d.sourceSchema(), d.domainName(),
                d.source(), d.isAuthoritative(), d.domainValuesJson());
    }

    public Optional<ValueDomain> findValueDomainByKey(String domainValueKey) {
        List<ValueDomain> rows = jdbc.query(
                "SELECT * FROM nexus_value_domain WHERE domain_value_key = ?",
                valueDomainMapper(), domainValueKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private RowMapper<ValueDomain> valueDomainMapper() {
        return (rs, rowNum) -> new ValueDomain(
                rs.getString("domain_value_key"),
                rs.getString("connection_key"),
                rs.getString("source_schema"),
                rs.getString("domain_name"),
                rs.getString("source"),
                rs.getBoolean("is_authoritative"),
                rs.getString("domain_values"),
                toInstant(rs, "scanned_at"));
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
                rs.getString("udt_name"),
                rs.getString("value_domain_key"),
                rs.getString("role_source"),
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
