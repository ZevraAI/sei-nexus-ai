package com.sei.nexus.usage;

import com.sei.nexus.common.Keys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class UsageRepository {

    private final JdbcTemplate jdbc;

    public UsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String tenantSchema, String userEmail, String feature,
                       String agentName, String model,
                       int promptTokens, int completionTokens, double costUsd) {
        jdbc.update("""
                INSERT INTO public.nexus_usage_event
                    (id, tenant_schema, user_email, feature, agent_name, model,
                     prompt_tokens, completion_tokens, cost_usd, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """,
                Keys.uniqueKey("usg"), tenantSchema, userEmail, feature,
                agentName, model, promptTokens, completionTokens, costUsd);
    }

    // ── Tenant-level aggregates (no cost) ─────────────────────────────────────

    public List<Map<String, Object>> summaryByFeature(String tenantSchema, String period) {
        return jdbc.queryForList("""
                SELECT feature,
                       SUM(prompt_tokens + completion_tokens) AS total_tokens,
                       SUM(prompt_tokens)     AS prompt_tokens,
                       SUM(completion_tokens) AS completion_tokens,
                       COUNT(*)               AS calls
                  FROM public.nexus_usage_event
                 WHERE tenant_schema = ?
                   AND TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM') = ?
                 GROUP BY feature
                 ORDER BY total_tokens DESC
                """, tenantSchema, period);
    }

    public List<Map<String, Object>> summaryByUser(String tenantSchema, String period) {
        return jdbc.queryForList("""
                SELECT user_email,
                       SUM(prompt_tokens + completion_tokens) AS total_tokens,
                       COUNT(*) AS calls
                  FROM public.nexus_usage_event
                 WHERE tenant_schema = ?
                   AND TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM') = ?
                   AND user_email IS NOT NULL
                 GROUP BY user_email
                 ORDER BY total_tokens DESC
                 LIMIT 20
                """, tenantSchema, period);
    }

    public List<Map<String, Object>> summaryByAgent(String tenantSchema, String period) {
        return jdbc.queryForList("""
                SELECT agent_name,
                       SUM(prompt_tokens + completion_tokens) AS total_tokens,
                       COUNT(*) AS calls
                  FROM public.nexus_usage_event
                 WHERE tenant_schema = ?
                   AND TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM') = ?
                   AND agent_name IS NOT NULL
                 GROUP BY agent_name
                 ORDER BY total_tokens DESC
                """, tenantSchema, period);
    }

    public List<Map<String, Object>> dailyTotals(String tenantSchema, String period) {
        return jdbc.queryForList("""
                SELECT TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day,
                       SUM(prompt_tokens + completion_tokens) AS total_tokens,
                       COUNT(*) AS calls
                  FROM public.nexus_usage_event
                 WHERE tenant_schema = ?
                   AND TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM') = ?
                 GROUP BY day
                 ORDER BY day
                """, tenantSchema, period);
    }

    // ── Platform admin — cross-tenant with cost ───────────────────────────────

    public List<Map<String, Object>> allTenantsSummary(String period) {
        return jdbc.queryForList("""
                SELECT tenant_schema,
                       SUM(prompt_tokens + completion_tokens) AS total_tokens,
                       SUM(prompt_tokens)     AS prompt_tokens,
                       SUM(completion_tokens) AS completion_tokens,
                       COUNT(*)               AS calls,
                       ROUND(SUM(cost_usd)::NUMERIC, 4)  AS cost_usd
                  FROM public.nexus_usage_event
                 WHERE TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM') = ?
                 GROUP BY tenant_schema
                 ORDER BY cost_usd DESC
                """, period);
    }

    public List<Map<String, Object>> platformDailyTotals(String period) {
        return jdbc.queryForList("""
                SELECT TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day,
                       SUM(prompt_tokens + completion_tokens) AS total_tokens,
                       ROUND(SUM(cost_usd)::NUMERIC, 4) AS cost_usd,
                       COUNT(*) AS calls
                  FROM public.nexus_usage_event
                 WHERE TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM') = ?
                 GROUP BY day
                 ORDER BY day
                """, period);
    }

    public Map<String, Object> tenantMonthlyCost(String tenantSchema, String period) {
        return jdbc.queryForMap("""
                SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS total_tokens,
                       COALESCE(SUM(prompt_tokens), 0)     AS prompt_tokens,
                       COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                       COALESCE(COUNT(*), 0)               AS calls,
                       ROUND(COALESCE(SUM(cost_usd), 0)::NUMERIC, 4) AS cost_usd
                  FROM public.nexus_usage_event
                 WHERE tenant_schema = ?
                   AND TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM') = ?
                """, tenantSchema, period);
    }
}
