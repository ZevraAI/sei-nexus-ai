package com.sei.nexus.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class UserProfileRepository {

    private final JdbcTemplate jdbc;

    public UserProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserProfile> findByEmail(String email) {
        List<UserProfile> rows = jdbc.query(
                "SELECT email, tenant_schema, role, status, display_name, " +
                "invited_by, created_at, updated_at " +
                "FROM public.nexus_user_profile WHERE email = ?",
                mapper(), email);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<UserProfile> findByTenantSchema(String tenantSchema) {
        return jdbc.query(
                "SELECT email, tenant_schema, role, status, display_name, " +
                "invited_by, created_at, updated_at " +
                "FROM public.nexus_user_profile WHERE tenant_schema = ? " +
                "ORDER BY created_at ASC",
                mapper(), tenantSchema);
    }

    public void create(UserProfile profile) {
        jdbc.update("""
                INSERT INTO public.nexus_user_profile
                    (email, tenant_schema, role, status, display_name, invited_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
                """,
                profile.email(), profile.tenantSchema(), profile.role(),
                profile.status(), profile.displayName(), profile.invitedBy());
    }

    public void updateRoleAndStatus(String email, String role, String status) {
        jdbc.update("""
                UPDATE public.nexus_user_profile
                   SET role = ?, status = ?, updated_at = NOW()
                 WHERE email = ?
                """, role, status, email);
    }

    public void deactivate(String email) {
        jdbc.update("""
                UPDATE public.nexus_user_profile
                   SET status = 'INACTIVE', updated_at = NOW()
                 WHERE email = ?
                """, email);
    }

    private RowMapper<UserProfile> mapper() {
        return (rs, rowNum) -> new UserProfile(
                rs.getString("email"),
                rs.getString("tenant_schema"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getString("display_name"),
                rs.getString("invited_by"),
                rs.getTimestamp("created_at") != null
                        ? rs.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC) : null,
                rs.getTimestamp("updated_at") != null
                        ? rs.getTimestamp("updated_at").toInstant().atOffset(ZoneOffset.UTC) : null);
    }
}
