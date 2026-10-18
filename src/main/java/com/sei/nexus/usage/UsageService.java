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

    /**
     * One model's USD-per-token rates: normal input, cache-hit input, and output. The cache-hit
     * rate is per model because it is NOT a flat half price: the gpt-4.1 family discounts cached
     * input by 75%, the gpt-4o family by 50%, and older models not at all. (A single flat 50%
     * applied to every model, with unlisted models silently priced as gpt-4o, made the usage
     * dashboard overstate gpt-4.1 spend by roughly 30% — see
     * docs/investigations/2026-10-18-llm-cost-analysis.md, finding F1.)
     */
    record Rates(double input, double cachedInput, double output) {
        static Rates perMillion(double input, double cachedInput, double output) {
            return new Rates(input / 1_000_000.0, cachedInput / 1_000_000.0, output / 1_000_000.0);
        }
    }

    // Model pricing (USD per 1M tokens → per token). OpenAI list prices; update as they change.
    private static final Map<String, Rates> PRICING = Map.ofEntries(
            Map.entry("gpt-4.1",                 Rates.perMillion( 2.00,  0.50,  8.00)),
            Map.entry("gpt-4.1-mini",            Rates.perMillion( 0.40,  0.10,  1.60)),
            Map.entry("gpt-4.1-nano",            Rates.perMillion( 0.10,  0.025, 0.40)),
            Map.entry("gpt-4o",                  Rates.perMillion( 2.50,  1.25, 10.00)),
            Map.entry("gpt-4o-mini",             Rates.perMillion( 0.15,  0.075, 0.60)),
            // Older models: no cached-input discount.
            Map.entry("gpt-4-turbo",             Rates.perMillion(10.00, 10.00, 30.00)),
            Map.entry("gpt-4",                   Rates.perMillion(30.00, 30.00, 60.00)),
            // Cost baseline instrumentation: embed() previously had no entry here at all, so any
            // embedding call that DID get recorded would have silently fallen through to gpt-4o's
            // rate (16x too expensive for this model). Embeddings have no output/completion
            // tokens (embed()'s recordUsage() call always passes 0 completion tokens).
            Map.entry("text-embedding-ada-002",  Rates.perMillion( 0.10,  0.10,  0.10))
    );

    /** Rates for a model name that is not listed — the pre-existing fallback, kept so an
     *  unrecognised model is never priced at zero (an under-count hides spend). */
    private static final Rates FALLBACK_RATES = PRICING.get("gpt-4o");

    /**
     * Resolves a model identifier to its rates: an exact match, else the LONGEST listed name that
     * the identifier starts with (so a dated snapshot such as {@code gpt-4.1-2025-04-14} prices as
     * {@code gpt-4.1}, and {@code gpt-4.1-mini-2025-04-14} as {@code gpt-4.1-mini} rather than
     * {@code gpt-4.1}), else {@link #FALLBACK_RATES}. Pure string handling of a model identifier —
     * it never looks at user text.
     */
    static Rates ratesFor(String model) {
        if (model == null) return FALLBACK_RATES;
        Rates exact = PRICING.get(model);
        if (exact != null) return exact;
        String best = null;
        for (String known : PRICING.keySet()) {
            if (model.startsWith(known) && (best == null || known.length() > best.length())) best = known;
        }
        return best != null ? PRICING.get(best) : FALLBACK_RATES;
    }

    /**
     * Estimated USD cost of one call. {@code cachedTokens} is assumed to be already counted within
     * {@code promptTokens} (how OpenAI reports {@code cached_tokens}) and is clamped to
     * {@code [0, promptTokens]}. Public and static so that the same figure that is persisted can be
     * logged on every {@code LLM_METRIC} line.
     */
    public static double estimateCostUsd(String model, int promptTokens, int cachedTokens, int completionTokens) {
        Rates r = ratesFor(model);
        int cached   = Math.min(Math.max(cachedTokens, 0), Math.max(promptTokens, 0));
        int uncached = Math.max(promptTokens, 0) - cached;
        return uncached * r.input() + cached * r.cachedInput() + Math.max(completionTokens, 0) * r.output();
    }

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
        record(model, promptTokens, completionTokens, 0, null);
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
        record(model, promptTokens, completionTokens, cachedTokens, null);
    }

    /**
     * Same as {@link #record(String, int, int, int)}, additionally given the call site's {@link
     * com.sei.nexus.ai.LlmCallTag} label (e.g. {@code "PLANNER"}, {@code "ANSWER_COMPOSER"},
     * {@code "MEMORY_SELECTION"}) so per-call-type cost/token breakdowns (finer-grained than the
     * existing {@code feature} column) can be queried later — see {@link
     * UsageRepository#summaryByCallType} / {@link UsageRepository#platformByCallType}.
     *
     * @param callType the caller's current {@code LlmCallTag}, or {@code null}/blank when the
     *                 caller has none to report — persisted as SQL {@code NULL}, never inferred
     *                 or guessed from {@code model}/{@code feature}/prompt content.
     */
    public void record(String model, int promptTokens, int completionTokens, int cachedTokens, String callType) {
        try {
            UsageContext.Ctx ctx = UsageContext.get();
            String feature   = ctx != null ? ctx.feature()   : "chat";
            String userEmail = ctx != null ? ctx.userEmail()  : null;
            String agentName = ctx != null ? ctx.agentName()  : null;
            String schema    = TenantContext.getSchema();
            String type      = (callType != null && !callType.isBlank()) ? callType : null;

            int      cached     = Math.min(Math.max(cachedTokens, 0), promptTokens);
            double   costUsd    = estimateCostUsd(model, promptTokens, cached, completionTokens);

            repo.insert(schema, userEmail, feature, agentName,
                        model, promptTokens, completionTokens, costUsd, cached, type);
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
        result.put("by_call_type", repo.summaryByCallType(tenantSchema, p));
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
        result.put("by_call_type",  repo.platformByCallType(p));
        result.put("daily",         repo.platformDailyTotals(p));
        return result;
    }

    private String resolvePeriod(String period) {
        if (period != null && period.matches("\\d{4}-\\d{2}")) return period;
        return YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }
}
