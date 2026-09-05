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
            "gpt-4o",                  new double[]{ 2.50  / 1_000_000.0, 10.00 / 1_000_000.0 },
            "gpt-4o-mini",             new double[]{ 0.15  / 1_000_000.0,  0.60 / 1_000_000.0 },
            "gpt-4-turbo",             new double[]{ 10.00 / 1_000_000.0, 30.00 / 1_000_000.0 },
            "gpt-4",                   new double[]{ 30.00 / 1_000_000.0, 60.00 / 1_000_000.0 },
            // Cost baseline instrumentation: embed() previously had no entry here at all, so any
            // embedding call that DID get recorded would have silently fallen through to gpt-4o's
            // rate below (16x too expensive for this model). Embeddings have no output/completion
            // tokens — the second element is unused (embed()'s recordUsage() call always passes 0
            // completion tokens) but kept non-zero-shaped for consistency with every other entry.
            "text-embedding-ada-002",  new double[]{ 0.10  / 1_000_000.0,  0.10 / 1_000_000.0 }
    );

    // Cost baseline instrumentation: OpenAI prices a cache-hit prompt token at half the model's
    // normal input rate (confirmed current published rate for gpt-4o-family models as of this
    // baseline). Applied uniformly since every priced model above follows that same 50% discount;
    // revisit if a future model's cached rate diverges from a flat half-price.
    private static final double CACHED_INPUT_DISCOUNT = 0.5;

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
        record(model, promptTokens, completionTokens, 0);
    }

    /**
     * Same as {@link #record(String, int, int)}, additionally given the cache-hit portion of
     * {@code promptTokens} so the cost baseline can price those tokens at OpenAI's discounted
     * cached-input rate instead of the full input rate. {@code cachedTokens} is assumed to already
     * be counted within {@code promptTokens} (that's how OpenAI's own {@code
     * prompt_tokens_details.cached_tokens} / {@code input_tokens_details.cached_tokens} fields
     * report it) — never added on top of it.
     */
    public void record(String model, int promptTokens, int completionTokens, int cachedTokens) {
        try {
            UsageContext.Ctx ctx = UsageContext.get();
            String feature   = ctx != null ? ctx.feature()   : "chat";
            String userEmail = ctx != null ? ctx.userEmail()  : null;
            String agentName = ctx != null ? ctx.agentName()  : null;
            String schema    = TenantContext.getSchema();

            double[] price      = PRICING.getOrDefault(model, PRICING.get("gpt-4o"));
            int      cached     = Math.min(Math.max(cachedTokens, 0), promptTokens);
            int      uncached   = promptTokens - cached;
            double   costUsd    = uncached * price[0]
                                 + cached   * price[0] * CACHED_INPUT_DISCOUNT
                                 + completionTokens * price[1];

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
