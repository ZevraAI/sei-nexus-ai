package com.sei.nexus.pack;

import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DIAGNOSTIC ONLY (real DB, real tenant data — never runs in the normal suite, per the
 * {@code *Validation} naming convention established this session).
 *
 * <p>Stop Apply Pack From Creating Tenant Business Entities — real-tenant proof against {@code
 * tenant_retail_industry}. {@code logistics-v1} has never been applied to this tenant, making it
 * a clean target for proving the FIXED behavior (unlike {@code retail-v1}, whose {@code
 * connection_key = 'conn-25c3ce28'} ACTIVE row and its 6 real, live {@code retail-v1-*} {@code
 * nexus_business_entity} duplicate rows — observed directly, unprompted, in this same
 * investigation — are the exact, current, real evidence of the bug this task fixes, produced by
 * the pre-fix {@code applyPack} against real Discover-onboarded tables).
 */
@SpringBootTest
class StopApplyPackEntityCreationRealTenantValidation {

    @Autowired private IndustryPackService packService;
    @Autowired private JdbcTemplate jdbc;

    private static final String TENANT_SCHEMA  = "tenant_retail_industry";
    private static final String CONNECTION_KEY = "Logistics_DB";
    private static final String PACK_KEY       = "logistics-v1";

    @Test
    void applyPackCreatesNoBusinessEntitiesAgainstRealTenantData() {
        TenantContext.set(TENANT_SCHEMA);
        try {
            run();
        } finally {
            TenantContext.clear();
        }
    }

    private void run() {
        System.out.println("\n========== Stop Apply Pack From Creating Tenant Business Entities — REAL TENANT ==========");

        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM nexus_business_entity", Long.class);
        System.out.println("nexus_business_entity count BEFORE apply: " + before);

        PackApplicationResult result = packService.applyPack(PACK_KEY, "PLATFORM", CONNECTION_KEY, "validation@zevra.test");
        System.out.println("applyPack() result: entitiesCreated=" + result.entitiesCreated()
                + " vocabularyTermsAdded=" + result.vocabularyTermsAdded()
                + " coverageScore=" + result.coverageScore());

        Long after = jdbc.queryForObject("SELECT COUNT(*) FROM nexus_business_entity", Long.class);
        System.out.println("nexus_business_entity count AFTER apply: " + after
                + " (delta " + (after - before) + " — must be 0)");

        Long logisticsRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nexus_business_entity WHERE entity_key LIKE 'logistics-v1-%'", Long.class);
        System.out.println("logistics-v1-* entity rows (must be 0): " + logisticsRows);

        var tenantPackRows = jdbc.queryForList(
                "SELECT pack_key, connection_key, status FROM nexus_tenant_pack WHERE pack_key = ?", PACK_KEY);
        System.out.println("nexus_tenant_pack row for '" + PACK_KEY + "': " + tenantPackRows);

        System.out.println("\n-- For comparison, the PRE-FIX retail-v1 duplicates still present from before this task --");
        var retailDuplicates = jdbc.queryForList(
                "SELECT entity_key, primary_object_key, pack_key, concept_key FROM nexus_business_entity "
                        + "WHERE entity_key LIKE 'retail-v1-%' ORDER BY entity_key");
        retailDuplicates.forEach(r -> System.out.println("  " + r));
        System.out.println("=============================================================================================\n");
    }
}
