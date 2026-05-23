package com.sei.nexus.governance;

import com.sei.nexus.common.Keys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class RlsPolicyRepository {

    private final JdbcTemplate jdbc;

    public RlsPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RlsPolicy save(RlsPolicy p) {
        String key = p.policyKey() != null ? p.policyKey() : Keys.uniqueKey("rls");
        jdbc.update("""
                INSERT INTO nexus_rls_policy
                    (policy_key, policy_name, object_key, filter_template,
                     applies_to_roles, is_active, created_by, created_at, updated_at)
                VALUES (?,?,?,?,?::text[],?,?,NOW(),NOW())
                ON CONFLICT (policy_key) DO UPDATE SET
                    policy_name      = EXCLUDED.policy_name,
                    object_key       = EXCLUDED.object_key,
                    filter_template  = EXCLUDED.filter_template,
                    applies_to_roles = EXCLUDED.applies_to_roles,
                    is_active        = EXCLUDED.is_active,
                    updated_at       = NOW()
                """,
                key, p.policyName(), p.objectKey(), p.filterTemplate(),
                toArrayLiteral(p.appliesToRoles()),
                p.isActive(), p.createdBy());
        return findByKey(key).orElseThrow();
    }

    public Optional<RlsPolicy> findByKey(String policyKey) {
        List<RlsPolicy> rows = jdbc.query(
                "SELECT * FROM nexus_rls_policy WHERE policy_key = ?", mapper(), policyKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<RlsPolicy> findAll() {
        return jdbc.query(
                "SELECT * FROM nexus_rls_policy ORDER BY object_key, policy_name",
                mapper());
    }

    public List<RlsPolicy> findActiveByObjectKeys(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) return List.of();
        String placeholders = objectKeys.stream().map(k -> "?")
                .collect(java.util.stream.Collectors.joining(","));
        List<Object> params = new java.util.ArrayList<>(objectKeys);
        return jdbc.query(
                "SELECT * FROM nexus_rls_policy WHERE object_key IN (" + placeholders + ") AND is_active = TRUE",
                mapper(), params.toArray());
    }

    public void setActive(String policyKey, boolean active) {
        jdbc.update("UPDATE nexus_rls_policy SET is_active = ?, updated_at = NOW() WHERE policy_key = ?",
                active, policyKey);
    }

    public void delete(String policyKey) {
        jdbc.update("DELETE FROM nexus_rls_policy WHERE policy_key = ?", policyKey);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RowMapper<RlsPolicy> mapper() {
        return (rs, i) -> {
            Array rolesArr = rs.getArray("applies_to_roles");
            String[] roles = rolesArr != null ? (String[]) rolesArr.getArray() : new String[0];
            return new RlsPolicy(
                    rs.getString("policy_key"),
                    rs.getString("policy_name"),
                    rs.getString("object_key"),
                    rs.getString("filter_template"),
                    roles,
                    rs.getBoolean("is_active"),
                    rs.getString("created_by"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at")));
        };
    }

    private String toArrayLiteral(String[] values) {
        if (values == null || values.length == 0) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values[i].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.now();
    }
}
