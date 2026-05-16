package com.sei.nexus.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A {@link DataSource} decorator that sets the PostgreSQL {@code search_path}
 * to the current tenant's schema on every connection checkout.
 *
 * <p>This is the central mechanism for schema-per-tenant isolation.
 * Every call to {@link #getConnection()} — whether made by {@link
 * org.springframework.jdbc.core.JdbcTemplate}, Spring Data, or Flyway — will
 * automatically target the correct tenant schema without any changes to
 * application-level query code.
 *
 * <p>When {@link TenantContext} is not set (unauthenticated requests, startup
 * migrations), the search_path defaults to {@code public}, making the shared
 * registry tables accessible.
 *
 * <p>Schema name validation is enforced before interpolation into the SQL
 * statement to prevent SQL injection.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

    // Only alphanumeric and underscores — prevents SQL injection in search_path
    private static final java.util.regex.Pattern SAFE_SCHEMA = java.util.regex.Pattern.compile("^[a-zA-Z0-9_]{1,63}$");

    public TenantAwareDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = obtainTargetDataSource().getConnection();
        applySearchPath(connection);
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection connection = obtainTargetDataSource().getConnection(username, password);
        applySearchPath(connection);
        return connection;
    }

    private void applySearchPath(Connection connection) throws SQLException {
        String schema = TenantContext.getSchema();
        if (!SAFE_SCHEMA.matcher(schema).matches()) {
            // Never reaches production — schema names are validated on write path
            throw new IllegalStateException(
                    "Tenant schema name contains invalid characters: " + schema);
        }
        // Always include public so shared tables (nexus_tenant, nexus_session_index,
        // pg system catalogs) are reachable regardless of the current tenant schema.
        String searchPath = schema.equals(TenantContext.PUBLIC_SCHEMA)
                ? "public"
                : schema + ", public";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("SET search_path TO " + searchPath);
        } catch (SQLException ex) {
            log.error("Failed to set search_path to '{}': {}", searchPath, ex.getMessage());
            connection.close();
            throw ex;
        }
    }
}
