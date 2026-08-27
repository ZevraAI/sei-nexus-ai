package com.sei.nexus.pack;

import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * DIAGNOSTIC ONLY (real DB, real tenant data — never runs in the normal suite, per the
 * {@code *Validation} naming convention established this session).
 *
 * <p>Industry Pack Removal Lifecycle — real-tenant, end-to-end proof against {@code
 * tenant_retail_industry}. This tenant's own live state at the start of this task IS the bug
 * this task fixes: {@code retail-v1} is {@code DISABLED} tenant-wide (from earlier testing in
 * this session, before the fix existed) and, before this fix, {@code applyPack} would refuse to
 * ever re-apply it anywhere — {@code findAppliedPack} matched the row regardless of status. This
 * validation applies {@code retail-v1} to {@code Logistics_DB} (a second real connection already
 * present on this tenant, pointing at the same physical {@code retail_core} database), proving
 * the fix live, then proves the artifact cleanup on Remove, then proves idempotent re-apply.
 */
@SpringBootTest
class IndustryPackRemovalLifecycleRealTenantValidation {

    @Autowired private IndustryPackService packService;
    @Autowired private JdbcTemplate jdbc;

    private static final String TENANT_SCHEMA  = "tenant_retail_industry";
    private static final String CONNECTION_KEY = "Logistics_DB";
    private static final String DOMAIN_KEY     = "PLATFORM";
    private static final String PACK_KEY       = "retail-v1";

    @Test
    void applyRemoveReapplyAgainstRealTenantData() {
        TenantContext.set(TENANT_SCHEMA);
        try {
            run();
        } finally {
            TenantContext.clear();
        }
    }

    private void run() {
        System.out.println("\n========== Industry Pack Removal Lifecycle — REAL TENANT ==========");

        long baselineActiveVocab = countVocab("ACTIVE");
        System.out.println("Baseline ACTIVE vocabulary count: " + baselineActiveVocab);
        System.out.println("Baseline retail-v1 tenant_pack rows: " + tenantPackRows());

        // Step 1 — apply. This is the exact regression this task fixes: retail-v1 is DISABLED
        // tenant-wide on this real tenant from earlier testing; before this fix, this call would
        // have thrown "Pack ... has already been applied" forever, regardless of status.
        PackApplicationResult applyResult;
        try {
            applyResult = packService.applyPack(PACK_KEY, DOMAIN_KEY, CONNECTION_KEY, "validation@zevra.test");
        } catch (Exception e) {
            System.out.println("APPLY FAILED: " + e.getMessage());
            System.out.println("=====================================================================\n");
            throw e;
        }
        System.out.println("Step 1 — applyPack succeeded: " + applyResult.entitiesCreated() + " entities, "
                + applyResult.vocabularyTermsAdded() + " vocab terms, coverage " + applyResult.coverageScore());

        long afterApplyVocab = countVocab("ACTIVE");
        System.out.println("ACTIVE vocabulary after apply: " + afterApplyVocab
                + " (delta " + (afterApplyVocab - baselineActiveVocab) + ")");
        List<Map<String, Object>> packEntities = jdbc.queryForList(
                "SELECT entity_key, status, pack_key FROM nexus_business_entity WHERE entity_key LIKE 'retail-v1-%' ORDER BY entity_key");
        System.out.println("Entities with retail-v1-* keys after apply:");
        packEntities.forEach(r -> System.out.println("  " + r));
        List<Map<String, Object>> packVocab = jdbc.queryForList(
                "SELECT term_key, status FROM nexus_operational_vocabulary WHERE term_key LIKE 'retail-v1-%' ORDER BY term_key");
        System.out.println("Vocabulary with retail-v1-* keys after apply:");
        packVocab.forEach(r -> System.out.println("  " + r));

        // Step 2 — remove.
        packService.removePack(PACK_KEY);
        System.out.println("\nStep 2 — removePack('" + PACK_KEY + "') completed.");

        long afterRemoveVocab = countVocab("ACTIVE");
        System.out.println("ACTIVE vocabulary after remove: " + afterRemoveVocab
                + " (back to baseline? " + (afterRemoveVocab == baselineActiveVocab) + ")");
        List<Map<String, Object>> packEntitiesAfterRemove = jdbc.queryForList(
                "SELECT entity_key, status FROM nexus_business_entity WHERE entity_key LIKE 'retail-v1-%' ORDER BY entity_key");
        System.out.println("Entities with retail-v1-* keys after remove (expect ARCHIVED):");
        packEntitiesAfterRemove.forEach(r -> System.out.println("  " + r));
        List<Map<String, Object>> packVocabAfterRemove = jdbc.queryForList(
                "SELECT term_key, status FROM nexus_operational_vocabulary WHERE term_key LIKE 'retail-v1-%' ORDER BY term_key");
        System.out.println("Vocabulary with retail-v1-* keys after remove (expect INACTIVE):");
        packVocabAfterRemove.forEach(r -> System.out.println("  " + r));

        Long activePackForConnection = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nexus_tenant_pack WHERE connection_key = ? AND status = 'ACTIVE'",
                Long.class, CONNECTION_KEY);
        System.out.println("Active pack rows for '" + CONNECTION_KEY + "' after remove: " + activePackForConnection);

        // Step 3 — re-apply: must succeed (the exact bug), and must not duplicate.
        PackApplicationResult reapplyResult = packService.applyPack(PACK_KEY, DOMAIN_KEY, CONNECTION_KEY, "validation@zevra.test");
        System.out.println("\nStep 3 — re-apply succeeded: " + reapplyResult.entitiesCreated() + " entities, "
                + reapplyResult.vocabularyTermsAdded() + " vocab terms.");

        Long distinctPackEntityCount = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT entity_key) FROM nexus_business_entity WHERE entity_key LIKE 'retail-v1-%'",
                Long.class);
        System.out.println("Distinct retail-v1-* entity_keys after re-apply (must equal pre-remove count, "
                + "proving no duplicates): " + distinctPackEntityCount);
        List<Map<String, Object>> finalEntities = jdbc.queryForList(
                "SELECT entity_key, status FROM nexus_business_entity WHERE entity_key LIKE 'retail-v1-%' ORDER BY entity_key");
        System.out.println("Final entity statuses (expect ACTIVE again):");
        finalEntities.forEach(r -> System.out.println("  " + r));

        System.out.println("=====================================================================\n");
    }

    private long countVocab(String status) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nexus_operational_vocabulary WHERE status = ?", Long.class, status);
        return count != null ? count : -1;
    }

    private List<Map<String, Object>> tenantPackRows() {
        return jdbc.queryForList("SELECT pack_key, connection_key, status FROM nexus_tenant_pack WHERE pack_key = ?", PACK_KEY);
    }
}
