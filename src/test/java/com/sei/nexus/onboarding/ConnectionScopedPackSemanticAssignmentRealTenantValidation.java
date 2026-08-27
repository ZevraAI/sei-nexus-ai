package com.sei.nexus.onboarding;

import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.prompt.BusinessObjectBatchAnalyzer;
import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DIAGNOSTIC ONLY (real DB, real tenant data, real LLM calls — never runs in the normal suite,
 * per the {@code *Validation} naming convention already established this session).
 *
 * <p>Connection-Scoped Industry Pack Semantic Assignment — real-tenant, end-to-end proof against
 * {@code tenant_retail_industry}. {@code conn-25c3ce28} already has {@code retail-v1} applied
 * (a legacy row, {@code connection_key = NULL}, from an earlier live test against this same
 * tenant) — {@code nexus_tenant_pack} has a real {@code UNIQUE(pack_key)} constraint, so
 * {@code retail-v1} cannot be assigned to a second connection here, and per the earlier
 * Connection-Scoped task's own rule, that legacy row is never backfilled/touched. This
 * validation instead uses {@code Logistics_DB} — a second real connection already present on
 * this tenant (created earlier in this session, deliberately pointing at the SAME physical
 * {@code retail_core} database as {@code conn-25c3ce28}, for exactly this kind of "same
 * physical data, different Zevra connection" proof) — and assigns it the (previously unused on
 * this tenant) {@code logistics-v1} pack.
 *
 * <p>Writes real rows: one {@code nexus_tenant_pack} assignment (Logistics_DB -> logistics-v1)
 * and two {@code nexus_business_entity} rows under clearly-namespaced, verification-only entity
 * keys ({@code *-logistics-verify}) so nothing here can collide with or overwrite any of the
 * tenant's real, already-curated entities (whose entity_key values are the plain concept names,
 * e.g. {@code warehouse}, {@code supplier}).
 */
@SpringBootTest
class ConnectionScopedPackSemanticAssignmentRealTenantValidation {

    @Autowired private IndustryPackRepository packRepository;
    @Autowired private BusinessObjectBatchAnalyzer analyzer;
    @Autowired private MetadataRegistrationService registration;
    @Autowired private JdbcTemplate jdbc;

    private static final String TENANT_SCHEMA  = "tenant_retail_industry";
    private static final String CONNECTION_KEY = "Logistics_DB";
    private static final String SCHEMA_NAME    = "retail_core";
    private static final String DOMAIN_KEY     = "PLATFORM";
    private static final String PACK_KEY       = "logistics-v1";

    @Test
    void connectionScopedPackAssignmentAcrossTwoAnalysesNoReapplication() {
        TenantContext.set(TENANT_SCHEMA);
        try {
            run();
        } finally {
            TenantContext.clear();
        }
    }

    private void run() {
        System.out.println("\n========== Connection-Scoped Industry Pack Semantic Assignment — REAL TENANT ==========");

        // Step 1: "Apply Pack ONCE to the connection" — the one-time configuration step this
        // whole task's product story is built on. Uses the same repository method
        // IndustryPackService.applyPack itself uses; done directly here (not via the full
        // service) only to avoid this diagnostic depending on the entity-matching side effects
        // applyPack also performs, which are irrelevant to what this validation is proving.
        packRepository.saveTenantPack(new TenantPack(PACK_KEY, CONNECTION_KEY, "1.0.0",
                "Logistics & Supply Chain", "ACTIVE", Map.of(), 1.0, null, "validation@zevra.test"));
        System.out.println("Step 1: " + PACK_KEY + " is now the ACTIVE pack for connection '" + CONNECTION_KEY + "'.");

        // Step 2-3: analyze and register the FIRST object ("Day 1").
        analyzeAndRegister("warehouses", "warehouse-logistics-verify");

        // Step 4-7: a table is analyzed again, LATER, WITHOUT touching Packs at all — this is
        // the mandatory acceptance criterion ("Day 30: new table, no Apply Pack action").
        analyzeAndRegister("suppliers", "supplier-logistics-verify");

        // Step 3/8/9: real database evidence.
        System.out.println("\n-- Actual nexus_business_entity rows (real DB) --");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT entity_key, entity_name, pack_key, concept_key FROM nexus_business_entity "
                        + "WHERE entity_key IN ('warehouse-logistics-verify','supplier-logistics-verify') "
                        + "ORDER BY entity_key");
        for (Map<String, Object> row : rows) {
            System.out.println("  " + row);
        }
        System.out.println("=========================================================================================\n");
    }

    private void analyzeAndRegister(String tableName, String entityKey) {
        System.out.println("\n-- Analyzing '" + tableName + "' on connection '" + CONNECTION_KEY
                + "' (no Pack re-application) --");
        Map<String, Map<String, Object>> analyzed =
                analyzer.analyzeBatch(CONNECTION_KEY, SCHEMA_NAME, DOMAIN_KEY, List.of(tableName));
        Map<String, Object> analysis = analyzed.get(tableName);
        System.out.println("  LLM entityName:  " + analysis.get("entityName"));
        System.out.println("  LLM category:    " + analysis.get("category"));
        System.out.println("  Resolved packKey:    " + analysis.get("packKey"));
        System.out.println("  Resolved conceptKey: " + analysis.get("conceptKey"));

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("approved", true);
        entity.put("tableName", tableName);
        entity.put("entityKey", entityKey);
        entity.put("entityName", analysis.getOrDefault("entityName", tableName));
        entity.put("purpose", analysis.getOrDefault("purpose", ""));
        entity.put("category", analysis.get("category"));
        if (analysis.containsKey("conceptKey")) entity.put("conceptKey", analysis.get("conceptKey"));
        // This is exactly the field the existing frontend does not yet forward through its own
        // apply/discoverApply whitelist (see this task's final report, "UI forwarding gap") —
        // supplied directly here, the same way a direct API call would, to prove the backend
        // mechanism end-to-end without requiring any UI change.

        Map<String, Object> request = Map.of(
                "connectionKey", CONNECTION_KEY, "schemaName", SCHEMA_NAME, "domainKey", DOMAIN_KEY,
                "entities", List.of(entity));
        var result = registration.register(request, "validation@zevra.test");
        System.out.println("  register() -> entitiesCreated=" + result.entitiesCreated()
                + " failures=" + result.failures());
    }
}
