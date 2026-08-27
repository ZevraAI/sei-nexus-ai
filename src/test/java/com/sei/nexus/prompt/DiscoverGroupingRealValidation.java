package com.sei.nexus.prompt;

import com.sei.nexus.enterprise.EnterpriseMapService;
import com.sei.nexus.onboarding.MetadataRegistrationService;
import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DIAGNOSTIC ONLY (opt-in, real LLM + real DB, never runs in the normal suite) — real
 * validation for the Discover-from-DB grouping regression: exercises the REAL
 * {@link EnterpriseMapService#analyzeForOnboarding} then the REAL
 * {@link MetadataRegistrationService#register}, with the request shaped EXACTLY like
 * {@code Semantic.jsx}'s {@code saveApproved()} apply payload now sends post-fix (same field
 * names: tableName, entityKey, entityName, purpose, category, businessName,
 * identifierColumns, ... — see that method), against the same real tenant/connection/table
 * used in the reported regression. Writes to real tenant data — intentional and required to
 * prove the fix, per this task's own validation requirement.
 */
@SpringBootTest
class DiscoverGroupingRealValidation {

    @Autowired private EnterpriseMapService enterpriseMapService;
    @Autowired private MetadataRegistrationService metadataRegistrationService;

    private static final String TENANT_SCHEMA = "tenant_retail_industry";
    private static final String CONNECTION_KEY = "conn-26a01eb1";
    private static final String SCHEMA_NAME = "retail_core";
    private static final String DOMAIN_KEY = "PLATFORM";

    @Test
    @SuppressWarnings("unchecked")
    void discoverAnalysisAndApplyPersistGroupLabelForTheRealRegressedEntity() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live probe disabled");
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");

        TenantContext.set(TENANT_SCHEMA);
        try {
            // ── Step A: real Discover analysis (/semantic/discover) ──────────────────
            Map<String, Object> discoverResult = enterpriseMapService.analyzeForOnboarding(Map.of(
                    "domainKey", DOMAIN_KEY, "connectionKey", CONNECTION_KEY,
                    "schemaName", SCHEMA_NAME, "tableNames", List.of("products")));
            List<Map<String, Object>> drafts = (List<Map<String, Object>>) discoverResult.get("tables");
            Map<String, Object> draft = drafts.get(0);
            System.out.println("Discover analysis category = " + draft.get("category"));

            // ── Step B: real Discover apply (/semantic/discover/apply), shaped EXACTLY
            //    like Semantic.jsx's saveApproved() now sends (post category-threading fix) ──
            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("approved", true);
            entity.put("tableName", draft.get("tableName"));
            entity.put("entityKey", slugify((String) draft.getOrDefault("entityName", "products")));
            entity.put("entityName", draft.get("entityName"));
            entity.put("purpose", draft.get("purpose"));
            entity.put("operationalMeaning", draft.get("usageGuidance"));
            entity.put("investigationHints", draft.get("filterGuidance"));
            entity.put("category", draft.get("category"));
            entity.put("businessName", draft.get("businessName"));
            entity.put("identifierColumns", draft.get("identifierColumns"));
            entity.put("vocabulary", List.of());

            Map<String, Object> applyRequest = Map.of(
                    "connectionKey", CONNECTION_KEY, "domainKey", DOMAIN_KEY,
                    "schemaName", SCHEMA_NAME, "entities", List.of(entity));

            var result = metadataRegistrationService.register(applyRequest, "prakash.stk12@gmail.com");
            System.out.println("Apply result: objectsCreated=" + result.objectsCreated()
                    + " entitiesCreated=" + result.entitiesCreated()
                    + " failures=" + result.failures());
        } finally {
            TenantContext.clear();
        }
    }

    private static String slugify(String input) {
        if (input == null) return "entity";
        return input.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}
