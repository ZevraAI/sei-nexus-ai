package com.sei.nexus.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes tenant registry data in the {@code public} schema.
 *
 * <p>All methods in this repository operate on {@code public.nexus_tenant} and
 * {@code public.nexus_session_index}. Because {@link TenantContext} is either
 * empty or set to {@code "public"} when these methods are called (auth filter
 * runs before tenant resolution), the {@link TenantAwareDataSource} will always
 * route these queries to the {@code public} schema — which is the correct
 * behaviour.
 */
@Repository
public class TenantRepository {

    private final JdbcTemplate jdbc;

    public TenantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Tenant ────────────────────────────────────────────────────────────────

    public List<Tenant> findAll() {
        return jdbc.query(
                "SELECT * FROM nexus_tenant ORDER BY name",
                tenantMapper());
    }

    public Optional<Tenant> findBySlug(String slug) {
        List<Tenant> rows = jdbc.query(
                "SELECT * FROM nexus_tenant WHERE slug = ?",
                tenantMapper(), slug);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Tenant> findBySchemaName(String schemaName) {
        List<Tenant> rows = jdbc.query(
                "SELECT * FROM nexus_tenant WHERE schema_name = ?",
                tenantMapper(), schemaName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Tenant save(Tenant tenant) {
        jdbc.update("""
                INSERT INTO nexus_tenant
                    (tenant_id, slug, name, schema_name, plan, status,
                     contact_email, max_users, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (slug) DO UPDATE SET
                    name          = EXCLUDED.name,
                    plan          = EXCLUDED.plan,
                    status        = EXCLUDED.status,
                    contact_email = EXCLUDED.contact_email,
                    max_users     = EXCLUDED.max_users,
                    updated_at    = NOW()
                """,
                tenant.tenantId(),
                tenant.slug(),
                tenant.name(),
                tenant.schemaName(),
                tenant.plan(),
                tenant.status(),
                tenant.contactEmail(),
                tenant.maxUsers(),
                Timestamp.from(tenant.createdAt() != null ? tenant.createdAt() : Instant.now()),
                Timestamp.from(tenant.updatedAt() != null ? tenant.updatedAt() : Instant.now()));
        return findBySlug(tenant.slug()).orElse(tenant);
    }

    public void updateStatus(String slug, String status) {
        jdbc.update("""
                UPDATE nexus_tenant
                   SET status = ?, updated_at = NOW()
                 WHERE slug = ?
                """, status, slug);
    }

    public void updatePlan(String slug, String plan, int maxUsers) {
        jdbc.update("""
                UPDATE nexus_tenant
                   SET plan = ?, max_users = ?, updated_at = NOW()
                 WHERE slug = ?
                """, plan, maxUsers, slug);
    }

    // ── Session index ─────────────────────────────────────────────────────────

    public record SessionIndex(String tokenHash, String tenantSchema, String userEmail, Instant expiresAt) {}

    public Optional<SessionIndex> findSessionIndex(String tokenHash) {
        List<SessionIndex> rows = jdbc.query(
                "SELECT token_hash, tenant_schema, user_email, expires_at" +
                " FROM nexus_session_index" +
                " WHERE token_hash = ? AND expires_at > NOW()",
                (rs, i) -> new SessionIndex(
                        rs.getString("token_hash"),
                        rs.getString("tenant_schema"),
                        rs.getString("user_email"),
                        toInstant(rs, "expires_at")),
                tokenHash);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void writeSessionIndex(String tokenHash, String tenantSchema,
                                   String userEmail, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO nexus_session_index
                    (token_hash, tenant_schema, user_email, expires_at, created_at)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (token_hash) DO UPDATE SET
                    tenant_schema = EXCLUDED.tenant_schema,
                    user_email    = EXCLUDED.user_email,
                    expires_at    = EXCLUDED.expires_at
                """,
                tokenHash, tenantSchema, userEmail, Timestamp.from(expiresAt));
    }

    public void deleteSessionIndex(String tokenHash) {
        jdbc.update("DELETE FROM nexus_session_index WHERE token_hash = ?", tokenHash);
    }

    public int deleteExpiredSessionIndexes() {
        return jdbc.update("DELETE FROM nexus_session_index WHERE expires_at <= NOW()");
    }

    // ── Row mapper ────────────────────────────────────────────────────────────

    private RowMapper<Tenant> tenantMapper() {
        return (rs, i) -> new Tenant(
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("schema_name"),
                rs.getString("plan"),
                rs.getString("status"),
                rs.getString("contact_email"),
                rs.getInt("max_users"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private Instant toInstant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }
}
