package com.sei.nexus.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TenantDomainRepository {

    private final JdbcTemplate jdbc;

    public TenantDomainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TenantDomain> findByDomain(String domain) {
        List<TenantDomain> rows = jdbc.query(
                "SELECT domain, tenant_schema, default_role, created_by, created_at " +
                "FROM public.nexus_tenant_domain WHERE domain = ?",
                mapper(), domain.toLowerCase());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<TenantDomain> findByTenantSchema(String tenantSchema) {
        return jdbc.query(
                "SELECT domain, tenant_schema, default_role, created_by, created_at " +
                "FROM public.nexus_tenant_domain WHERE tenant_schema = ? ORDER BY domain",
                mapper(), tenantSchema);
    }

    public void create(TenantDomain d) {
        jdbc.update("""
                INSERT INTO public.nexus_tenant_domain
                    (domain, tenant_schema, default_role, created_by, created_at)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (domain) DO NOTHING
                """,
                d.domain().toLowerCase(), d.tenantSchema(), d.defaultRole(), d.createdBy());
    }

    public void delete(String domain, String tenantSchema) {
        jdbc.update(
                "DELETE FROM public.nexus_tenant_domain WHERE domain = ? AND tenant_schema = ?",
                domain.toLowerCase(), tenantSchema);
    }

    // ── mapper ────────────────────────────────────────────────────────────────

    private RowMapper<TenantDomain> mapper() {
        return (rs, i) -> new TenantDomain(
                rs.getString("domain"),
                rs.getString("tenant_schema"),
                rs.getString("default_role"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at") != null
                        ? rs.getTimestamp("created_at").toInstant()
                              .atOffset(java.time.ZoneOffset.UTC) : null);
    }
}
