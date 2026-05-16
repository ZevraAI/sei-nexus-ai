package com.sei.nexus.onboarding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Per-tenant key-value settings store backed by {@code nexus_tenant_settings}.
 *
 * <p>All queries run against the current tenant's schema because the
 * {@link com.sei.nexus.tenant.TenantAwareDataSource} sets {@code search_path}
 * on every connection checkout. There is no cross-tenant data access here.
 */
@Repository
public class TenantSettingsRepository {

    private final JdbcTemplate jdbc;

    public TenantSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        var rows = jdbc.query(
                "SELECT setting_value FROM nexus_tenant_settings WHERE setting_key = ?",
                (rs, i) -> rs.getString("setting_value"),
                key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void set(String key, String value) {
        jdbc.update("""
                INSERT INTO nexus_tenant_settings (setting_key, setting_value, updated_at)
                VALUES (?, ?, NOW())
                ON CONFLICT (setting_key) DO UPDATE
                    SET setting_value = EXCLUDED.setting_value,
                        updated_at    = NOW()
                """, key, value);
    }

    public boolean isTrue(String key) {
        return get(key).map("true"::equalsIgnoreCase).orElse(false);
    }

    public void delete(String key) {
        jdbc.update("DELETE FROM nexus_tenant_settings WHERE setting_key = ?", key);
    }
}
