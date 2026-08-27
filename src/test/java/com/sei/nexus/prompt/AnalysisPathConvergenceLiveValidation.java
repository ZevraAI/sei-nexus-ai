package com.sei.nexus.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.enterprise.EnterpriseMapService;
import com.sei.nexus.onboarding.OnboardingAnalysisJob;
import com.sei.nexus.onboarding.OnboardingAnalysisJobRepository;
import com.sei.nexus.onboarding.OnboardingService;
import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DIAGNOSTIC ONLY (opt-in, real LLM + real DB, never runs in the normal suite) — Business
 * Object Analysis Contract convergence, Step 10 validation. Exercises the REAL, fully-wired
 * {@link OnboardingService} (via a real analysis job) and the REAL {@link EnterpriseMapService}
 * (Discover from DB) against the same real tenant/connection/tables, and reports whether both
 * paths now produce the canonical fields — most importantly {@code category}, present on both,
 * which was the entire reason this convergence exists.
 *
 * <p>Guarded by {@code -Dnexus.live.openai=true} and {@code OPENAI_API_KEY}, mirroring every
 * other live probe in this repo. Makes no tenant-data assumptions beyond reading the existing
 * clean tenant's real connection.
 */
@SpringBootTest
class AnalysisPathConvergenceLiveValidation {

    @Autowired private OnboardingService onboardingService;
    @Autowired private EnterpriseMapService enterpriseMapService;
    @Autowired private OnboardingAnalysisJobRepository jobRepository;

    private static final String TENANT_SCHEMA = "tenant_retail_industry";
    private static final String CONNECTION_KEY = "conn-26a01eb1";
    private static final String SCHEMA_NAME = "retail_core";
    private static final String DOMAIN_KEY = "PLATFORM";

    @Test
    void bothAnalysisPathsProduceTheCanonicalFieldsForTheSameRealTables() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live probe disabled");
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");

        ObjectMapper mapper = new ObjectMapper();
        List<String> tables = List.of("purchase_orders", "stores");

        TenantContext.set(TENANT_SCHEMA);
        try {
            // ── Onboarding path (batched) ──────────────────────────────────────────
            System.out.println("\n########## ONBOARDING (analyzeTableBatch) ##########");
            String jobId = onboardingService.startAnalysisJob(CONNECTION_KEY, SCHEMA_NAME, DOMAIN_KEY, tables);
            OnboardingAnalysisJob job = waitForCompletion(jobId);
            @SuppressWarnings("unchecked")
            Map<String, Object> onboardingResults = mapper.readValue(job.resultsJson(), Map.class);
            for (String t : tables) {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) onboardingResults.get(t);
                System.out.println(t + " -> category=" + r.get("category")
                        + " entityName=" + r.get("entityName")
                        + " businessName=" + r.get("businessName")
                        + " identifierColumns=" + r.get("identifierColumns"));
            }

            // ── Discover path (per-table) ───────────────────────────────────────────
            System.out.println("\n########## DISCOVER (analyzeForOnboarding) ##########");
            Map<String, Object> discoverResult = enterpriseMapService.analyzeForOnboarding(Map.of(
                    "domainKey", DOMAIN_KEY, "connectionKey", CONNECTION_KEY,
                    "schemaName", SCHEMA_NAME, "tableNames", tables));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> discoverDrafts = (List<Map<String, Object>>) discoverResult.get("tables");
            for (Map<String, Object> d : discoverDrafts) {
                System.out.println(d.get("tableName") + " -> category=" + d.get("category")
                        + " entityName=" + d.get("entityName")
                        + " businessName=" + d.get("businessName")
                        + " identifierColumns=" + d.get("identifierColumns"));
            }
            System.out.println("########## END VALIDATION ##########\n");
        } finally {
            TenantContext.clear();
        }
    }

    private OnboardingAnalysisJob waitForCompletion(String jobId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            var job = jobRepository.findById(jobId);
            if (job.isPresent() && ("COMPLETE".equals(job.get().status()) || "FAILED".equals(job.get().status()))) {
                return job.get();
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("job did not complete in time: " + jobId);
    }
}
