package com.sei.nexus.sql;

import com.sei.nexus.common.NexusException;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.connection.NexusConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * Executes approved SQL against governed external data sources.
 *
 * <p>Uses {@link DriverManager} with try-with-resources for all queries so that
 * connections are always released, even on error.  No connection pool is maintained
 * here because queries are short-lived and pooling at this layer would complicate
 * multi-tenant credential management.
 *
 * <p><strong>Secret handling:</strong> {@code encryptedSecret} is currently stored
 * as plaintext.  In production, decrypt via Vault before passing to the JDBC driver.
 * </p>
 */
@Service
public class DynamicSqlService {

    private static final Logger log = LoggerFactory.getLogger(DynamicSqlService.class);

    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
    private static final int COUNT_QUERY_TIMEOUT_SECONDS = 10;
    private static final int MAX_COLUMN_STRING_LENGTH = 4096;

    private final ConnectionRepository connectionRepository;

    public DynamicSqlService(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
    }

    // ---------------------------------------------------------------------------
    // Query execution
    // ---------------------------------------------------------------------------

    /**
     * Executes an approved SELECT statement and returns rows as a list of maps.
     *
     * @param connectionKey  the connection to use
     * @param approvedSql    SQL that has already passed safety + governance checks
     * @param maxRows        hard cap on returned rows
     * @return ordered list of column-name → value maps
     */
    public List<Map<String, Object>> executeQuery(String connectionKey,
                                                   String approvedSql,
                                                   int maxRows) {
        NexusConnection conn = requireConnection(connectionKey);
        String secret = conn.encryptedSecret(); // PRODUCTION: decrypt via Vault here

        try (Connection jdbc = DriverManager.getConnection(
                conn.jdbcUrl(), conn.username(), secret)) {

            try (Statement stmt = jdbc.createStatement()) {
                stmt.setMaxRows(maxRows);
                stmt.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);

                try (ResultSet rs = stmt.executeQuery(approvedSql)) {
                    return mapResultSet(rs);
                }
            }

        } catch (SQLException ex) {
            log.error("Query execution failed on connection {}: {}", connectionKey, ex.getMessage());
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Query failed: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------------------
    // Row count estimation
    // ---------------------------------------------------------------------------

    /**
     * Wraps the supplied SQL in a COUNT(*) to estimate result set size.
     *
     * @return the estimated row count, or {@code -1} if estimation fails
     */
    public long estimateRowCount(String connectionKey, String sql) {
        NexusConnection conn = requireConnection(connectionKey);
        String secret = conn.encryptedSecret(); // PRODUCTION: decrypt via Vault here

        String countSql = "SELECT COUNT(*) FROM (" + sql + ") nexus_count_subq";

        try (Connection jdbc = DriverManager.getConnection(
                conn.jdbcUrl(), conn.username(), secret)) {

            try (Statement stmt = jdbc.createStatement()) {
                stmt.setQueryTimeout(COUNT_QUERY_TIMEOUT_SECONDS);

                try (ResultSet rs = stmt.executeQuery(countSql)) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
            }

        } catch (Exception ex) {
            log.warn("Row count estimation failed for connection {}: {}", connectionKey, ex.getMessage());
        }
        return -1L;
    }

    // ---------------------------------------------------------------------------
    // Schema / catalog introspection
    // ---------------------------------------------------------------------------

    /**
     * Returns column metadata for a table.
     *
     * <p>Uses {@code information_schema.columns} for Postgres and
     * {@code all_tab_columns} for Oracle.</p>
     *
     * @return list of column detail maps
     */
    public List<Map<String, Object>> describeTable(String connectionKey,
                                                    String schemaName,
                                                    String tableName) {
        NexusConnection conn = requireConnection(connectionKey);
        String secret = conn.encryptedSecret(); // PRODUCTION: decrypt via Vault here

        String querySql;
        Object[] params;

        if ("ORACLE".equalsIgnoreCase(conn.connectionType())) {
            querySql = """
                    SELECT column_name, data_type, nullable
                      FROM all_tab_columns
                     WHERE owner      = ?
                       AND table_name = ?
                     ORDER BY column_id
                    """;
            params = new Object[]{
                    schemaName != null ? schemaName.toUpperCase() : null,
                    tableName.toUpperCase()};
        } else {
            // Postgres and other ANSI-compliant sources
            querySql = """
                    SELECT column_name, data_type, is_nullable
                      FROM information_schema.columns
                     WHERE table_schema = ?
                       AND table_name   = ?
                     ORDER BY ordinal_position
                    """;
            params = new Object[]{schemaName, tableName};
        }

        try (Connection jdbc = DriverManager.getConnection(
                conn.jdbcUrl(), conn.username(), secret)) {

            try (PreparedStatement ps = jdbc.prepareStatement(querySql)) {
                ps.setString(1, (String) params[0]);
                ps.setString(2, (String) params[1]);
                ps.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);

                try (ResultSet rs = ps.executeQuery()) {
                    return mapResultSet(rs);
                }
            }

        } catch (SQLException ex) {
            log.error("describeTable failed for {}.{} on {}: {}",
                    schemaName, tableName, connectionKey, ex.getMessage());
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not describe table: " + ex.getMessage());
        }
    }

    /**
     * Returns ALL tables in the schema with column counts and a compact comma-separated
     * list of their column names — in a SINGLE database round-trip.
     *
     * <p><strong>Scalability:</strong> One SQL query regardless of how many tables exist.
     * Used by the onboarding recommendation engine so it can pass complete schema
     * metadata to the AI without making N individual {@code describeTable} calls.
     * Also filters out obvious system/framework tables (flyway, pg_*, audit logs)
     * at the SQL level to reduce noise in the AI prompt.
     */
    public List<Map<String, Object>> listTablesWithColumnCounts(String connectionKey,
                                                                  String schemaName) {
        NexusConnection conn = requireConnection(connectionKey);
        String secret = conn.encryptedSecret();

        String sql;
        if ("ORACLE".equalsIgnoreCase(conn.connectionType())) {
            sql = """
                    SELECT t.table_name,
                           COUNT(c.column_name)                                    AS column_count,
                           LISTAGG(c.column_name, ', ')
                               WITHIN GROUP (ORDER BY c.column_id)                 AS column_names
                      FROM all_tables     t
                      JOIN all_tab_columns c ON c.table_name = t.table_name
                                            AND c.owner      = t.owner
                     WHERE t.owner = ?
                     GROUP BY t.table_name
                     ORDER BY t.table_name
                    """;
        } else {
            sql = """
                    SELECT t.table_name,
                           COUNT(c.column_name)                                      AS column_count,
                           STRING_AGG(c.column_name, ', '
                               ORDER BY c.ordinal_position)                          AS column_names
                      FROM information_schema.tables  t
                      JOIN information_schema.columns c ON c.table_name  = t.table_name
                                                       AND c.table_schema = t.table_schema
                     WHERE t.table_schema = ?
                       AND t.table_type   = 'BASE TABLE'
                       AND t.table_name NOT LIKE 'flyway%'
                       AND t.table_name NOT LIKE 'pg\\_%'
                       AND t.table_name NOT LIKE '\\_%'
                       AND t.table_name NOT LIKE '%\\_log'
                       AND t.table_name NOT LIKE '%\\_logs'
                       AND t.table_name NOT LIKE '%\\_audit'
                       AND t.table_name NOT LIKE 'tmp\\_%'
                     GROUP BY t.table_name
                     ORDER BY t.table_name
                    """;
        }

        try (Connection jdbc = DriverManager.getConnection(conn.jdbcUrl(), conn.username(), secret);
             PreparedStatement ps = jdbc.prepareStatement(sql)) {

            ps.setString(1, "ORACLE".equalsIgnoreCase(conn.connectionType())
                    ? (schemaName != null ? schemaName.toUpperCase() : null) : schemaName);
            ps.setQueryTimeout(COUNT_QUERY_TIMEOUT_SECONDS);

            try (ResultSet rs = ps.executeQuery()) {
                return mapResultSet(rs);
            }
        } catch (SQLException ex) {
            log.error("listTablesWithColumnCounts failed on {}: {}", connectionKey, ex.getMessage());
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not scan schema: " + ex.getMessage());
        }
    }

    /**
     * Returns table names within the given schema that match the search pattern.
     *
     * @param query substring to match against table names (case-insensitive)
     * @return list of matching table info maps
     */
    public List<Map<String, Object>> listTables(String connectionKey,
                                                 String schemaName,
                                                 String query) {
        NexusConnection conn = requireConnection(connectionKey);
        String secret = conn.encryptedSecret(); // PRODUCTION: decrypt via Vault here

        String searchPattern = "%" + (query != null ? query.toLowerCase() : "") + "%";

        String searchSql;
        Object[] params;

        if ("ORACLE".equalsIgnoreCase(conn.connectionType())) {
            searchSql = """
                    SELECT DISTINCT table_name
                      FROM all_tables
                     WHERE owner      = ?
                       AND LOWER(table_name) LIKE ?
                     ORDER BY table_name
                    """;
            params = new Object[]{
                    schemaName != null ? schemaName.toUpperCase() : null,
                    searchPattern};
        } else {
            searchSql = """
                    SELECT DISTINCT table_name
                      FROM information_schema.tables
                     WHERE table_schema  = ?
                       AND LOWER(table_name) LIKE ?
                       AND table_type    = 'BASE TABLE'
                     ORDER BY table_name
                    """;
            params = new Object[]{schemaName, searchPattern};
        }

        try (Connection jdbc = DriverManager.getConnection(
                conn.jdbcUrl(), conn.username(), secret)) {

            try (PreparedStatement ps = jdbc.prepareStatement(searchSql)) {
                ps.setString(1, (String) params[0]);
                ps.setString(2, (String) params[1]);
                ps.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);

                try (ResultSet rs = ps.executeQuery()) {
                    return mapResultSet(rs);
                }
            }

        } catch (SQLException ex) {
            log.error("listTables failed on {}: {}", connectionKey, ex.getMessage());
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not search catalog: " + ex.getMessage());
        }
    }

    /**
     * Returns table names within the given schema that match the search pattern.
     *
     * @param query substring to match against table names (case-insensitive)
     * @return list of matching table names
     */
    public List<String> searchCatalog(String connectionKey,
                                       String schemaName,
                                       String query) {
        NexusConnection conn = requireConnection(connectionKey);
        String secret = conn.encryptedSecret(); // PRODUCTION: decrypt via Vault here

        String searchPattern = "%" + (query != null ? query.toLowerCase() : "") + "%";

        String searchSql;
        Object[] params;

        if ("ORACLE".equalsIgnoreCase(conn.connectionType())) {
            searchSql = """
                    SELECT DISTINCT table_name
                      FROM all_tables
                     WHERE owner      = ?
                       AND LOWER(table_name) LIKE ?
                     ORDER BY table_name
                    """;
            params = new Object[]{
                    schemaName != null ? schemaName.toUpperCase() : null,
                    searchPattern};
        } else {
            searchSql = """
                    SELECT DISTINCT table_name
                      FROM information_schema.tables
                     WHERE table_schema  = ?
                       AND LOWER(table_name) LIKE ?
                       AND table_type    = 'BASE TABLE'
                     ORDER BY table_name
                    """;
            params = new Object[]{schemaName, searchPattern};
        }

        try (Connection jdbc = DriverManager.getConnection(
                conn.jdbcUrl(), conn.username(), secret)) {

            try (PreparedStatement ps = jdbc.prepareStatement(searchSql)) {
                ps.setString(1, (String) params[0]);
                ps.setString(2, (String) params[1]);
                ps.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);

                try (ResultSet rs = ps.executeQuery()) {
                    List<String> tables = new ArrayList<>();
                    while (rs.next()) {
                        tables.add(rs.getString(1));
                    }
                    return tables;
                }
            }

        } catch (SQLException ex) {
            log.error("searchCatalog failed on {}: {}", connectionKey, ex.getMessage());
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not search catalog: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------------------
    // Allow-list check
    // ---------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given schema.table combination is permitted by
     * the connection's allow-list configuration.
     */
    public boolean tableIsOnAllowlist(NexusConnection conn,
                                       String schemaName,
                                       String tableName) {
        // Fail-closed: if no allow-lists configured, deny everything
        if (isEmpty(conn.allowedSchemas()) && isEmpty(conn.allowedTables())) {
            return false;
        }

        String lcSchema = schemaName != null ? schemaName.toLowerCase() : "";
        String lcTable  = tableName  != null ? tableName.toLowerCase()  : "";

        // Check schema allow-list
        if (!isEmpty(conn.allowedSchemas())) {
            for (String schema : conn.allowedSchemas().split(",")) {
                if (schema.strip().equalsIgnoreCase(lcSchema)) {
                    return true;
                }
            }
        }

        // Check table allow-list (format: schema.table)
        if (!isEmpty(conn.allowedTables())) {
            String fqn = lcSchema + "." + lcTable;
            for (String entry : conn.allowedTables().split(",")) {
                if (entry.strip().equalsIgnoreCase(fqn)) {
                    return true;
                }
            }
        }

        return false;
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private NexusConnection requireConnection(String connectionKey) {
        return connectionRepository.findByKey(connectionKey)
                .orElseThrow(() -> new NexusException(
                        HttpStatus.NOT_FOUND, "Connection not found: " + connectionKey));
    }

    /**
     * Maps a {@link ResultSet} to a list of ordered maps, one per row.
     * Column values are converted to standard Java types; large strings are truncated.
     */
    private List<Map<String, Object>> mapResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();

        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(colCount * 2);
            for (int i = 1; i <= colCount; i++) {
                String colName = meta.getColumnLabel(i);
                Object value   = rs.getObject(i);

                // Truncate large strings to prevent unbounded memory usage
                if (value instanceof String str && str.length() > MAX_COLUMN_STRING_LENGTH) {
                    value = str.substring(0, MAX_COLUMN_STRING_LENGTH) + "…[truncated]";
                }
                // Convert Clob to String
                if (value instanceof Clob clob) {
                    try {
                        String clobStr = clob.getSubString(1, (int) Math.min(
                                clob.length(), MAX_COLUMN_STRING_LENGTH));
                        value = clob.length() > MAX_COLUMN_STRING_LENGTH
                                ? clobStr + "…[truncated]" : clobStr;
                    } catch (SQLException e) {
                        value = "[CLOB read error]";
                    }
                }

                row.put(colName, value);
            }
            rows.add(row);
        }
        return rows;
    }

    private boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }
}
