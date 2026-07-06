package com.sei.nexus.usage;

import com.sei.nexus.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    // Model pricing (USD per token). Update as OpenAI changes rates.
    private static final Map<String, double[]> PRICING = Map.of(
            "gpt-4o",      new double[]{ 2.50  / 1_000_000.0, 10.00 / 1_000_000.0 },
            "gpt-4o-mini", new double[]{ 0.15  / 1_000_000.0,  0.60 / 1_000_000.0 },
            "gpt-4-turbo", new double[]{ 10.00 / 1_000_000.0, 30.00 / 1_000_000.0 },
            "gpt-4",       new double[]{ 30.00 / 1_000_000.0, 60.00 / 1_000_000.0 }
    );

    private final UsageRepository repo;

    public UsageService(UsageRepository repo) {
        this.repo = repo;
    }

    /**
     * Records one LLM call. Called by AzureOpenAiClient after every successful response.
     * Reads attribution from UsageContext (set by the calling service).
     * Silently swallows errors — usage tracking must never break the main flow.
     */
    public void record(String model, int promptTokens, int completionTokens) {
        try {
            UsageContext.Ctx ctx = UsageContext.get();
            String feature   = ctx != null ? ctx.feature()   : "chat";
            String userEmail = ctx != null ? ctx.userEmail()  : null;
            String agentName = ctx != null ? ctx.agentName()  : null;
            String schema    = TenantContext.getSchema();

            double[] price  = PRICING.getOrDefault(model, PRICING.get("gpt-4o"));
            double costUsd  = promptTokens * price[0] + completionTokens * price[1];

            repo.insert(schema, userEmail, feature, agentName,
                        model, promptTokens, completionTokens, costUsd);
        } catch (Exception e) {
            log.debug("Usage recording failed (non-fatal): {}", e.getMessage());
        }
    }

    // ── Tenant admin view (no cost figures) ───────────────────────────────────

    public Map<String, Object> tenantSummary(String tenantSchema, String period) {
        String p = resolvePeriod(period);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period",       p);
        result.put("totals",       repo.tenantMonthlyCost(tenantSchema, p));
        result.put("by_feature",   repo.summaryByFeature(tenantSchema, p));
        result.put("by_user",      repo.summaryByUser(tenantSchema, p));
        result.put("by_agent",     repo.summaryByAgent(tenantSchema, p));
        result.put("daily",        repo.dailyTotals(tenantSchema, p));
        return result;
    }

    // ── Platform admin view (includes cost) ───────────────────────────────────

    public Map<String, Object> platformSummary(String period) {
        String p = resolvePeriod(period);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period",        p);
        result.put("by_tenant",     repo.allTenantsSummary(p));
        result.put("daily",         repo.platformDailyTotals(p));
        return result;
    }

    private String resolvePeriod(String period) {
        if (period != null && period.matches("\\d{4}-\\d{2}")) return period;
        return YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }
}
