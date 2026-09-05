package com.sei.nexus.semantic;

import com.sei.nexus.tenant.TenantAwareDataSource;
import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DIAGNOSTIC / LIVE VALIDATION — real database, two real tenant schemas (no OpenAI call
 * needed — {@link LearnedMappingRepository#upsert} is a pure JDBC write). Excluded from
 * Surefire's default run.
 *
 * <p>Wires the REAL, unmodified production {@link SemanticLearningAsyncConfig#semanticLearningExecutor}
 * bean (real {@link TenantContextPropagatingTaskDecorator} attached) and a real {@link
 * LearnedMappingRepository} bound to the same {@link TenantAwareDataSource} production uses, then
 * submits one learning-shaped write for tenant {@code persistent-ai-test} and one for tenant
 * {@code maryland-corporations} — exactly the way {@code SemanticLearningService.learnFromRun}
 * would, via {@code executor.execute(...)}, with {@code TenantContext} set to the correct tenant
 * on the calling ("request") thread immediately before each submission. Verifies each row lands
 * in its own tenant's schema, not the other's, and not in {@code public}.
 *
 * <p>Writes two clearly diagnostic-tagged rows (business_term prefixed {@code zz_diag_}) — left
 * in place afterward (not deleted), since a learned-mapping row is exactly the intended, harmless
 * artifact this feature produces; nothing here mutates or removes any pre-existing data,
 * including the historical misplaced rows in {@code public.nexus_learned_mapping} from before
 * this fix, which this task explicitly does not remediate.
 */
class SemanticLearningTenantIsolationRealTenantValidation {

    @Test
    void tenantAAndTenantBAsyncLearningWritesLandInTheirOwnSchemasOnly() throws Exception {
        String dbUrl  = System.getenv("NEXUS_DB_URL");
        String dbUser = System.getenv("NEXUS_DB_USERNAME");
        String dbPass = System.getenv("NEXUS_DB_PASSWORD");
        if (isBlank(dbUrl) || isBlank(dbUser) || isBlank(dbPass)) {
            System.out.println("Skipping — NEXUS_DB_URL/NEXUS_DB_USERNAME/NEXUS_DB_PASSWORD required.");
            return;
        }

        DriverManagerDataSource raw = new DriverManagerDataSource(dbUrl, dbUser, dbPass);
        raw.setDriverClassName("org.postgresql.Driver");
        TenantAwareDataSource dataSource = new TenantAwareDataSource(raw);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LearnedMappingRepository mappingRepository = new LearnedMappingRepository(jdbc);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor)
                new SemanticLearningAsyncConfig().semanticLearningExecutor();

        String schemaA = "tenant_persistent_ai_test";
        String schemaB = "tenant_maryland_corporations";
        String termA = "zz_diag_tenant_a_" + System.nanoTime();
        String termB = "zz_diag_tenant_b_" + System.nanoTime();

        try {
            CountDownLatch latch = new CountDownLatch(2);
            AtomicInteger errors = new AtomicInteger(0);

            TenantContext.set(schemaA);
            executor.execute(() -> {
                try {
                    mappingRepository.upsert(new LearnedMapping(null, "PLATFORM", termA,
                            "status = 'diagnostic_a'", "run-diag-iso-a", "QUERY_SUCCESS",
                            0.5, 1, Instant.now(), false, null, null, null));
                } catch (Exception e) {
                    errors.incrementAndGet();
                    System.out.println("Tenant A task failed: " + e);
                } finally {
                    latch.countDown();
                }
            });
            TenantContext.clear();

            TenantContext.set(schemaB);
            executor.execute(() -> {
                try {
                    mappingRepository.upsert(new LearnedMapping(null, "PLATFORM", termB,
                            "status = 'diagnostic_b'", "run-diag-iso-b", "QUERY_SUCCESS",
                            0.5, 1, Instant.now(), false, null, null, null));
                } catch (Exception e) {
                    errors.incrementAndGet();
                    System.out.println("Tenant B task failed: " + e);
                } finally {
                    latch.countDown();
                }
            });
            TenantContext.clear();

            assertTrue(latch.await(15, TimeUnit.SECONDS), "both async writes must complete");
            assertEquals(0, errors.get(), "neither write should throw");

            // Verify directly with schema-qualified SQL (no TenantContext dependency for this check).
            Integer inA = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + schemaA + ".nexus_learned_mapping WHERE business_term = ?",
                    Integer.class, termA);
            Integer inB = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + schemaB + ".nexus_learned_mapping WHERE business_term = ?",
                    Integer.class, termB);
            Integer aInB = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + schemaB + ".nexus_learned_mapping WHERE business_term = ?",
                    Integer.class, termA);
            Integer bInA = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + schemaA + ".nexus_learned_mapping WHERE business_term = ?",
                    Integer.class, termB);
            Integer aInPublic = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM public.nexus_learned_mapping WHERE business_term = ?",
                    Integer.class, termA);
            Integer bInPublic = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM public.nexus_learned_mapping WHERE business_term = ?",
                    Integer.class, termB);

            System.out.println("Tenant A term found in tenant A schema: " + inA);
            System.out.println("Tenant B term found in tenant B schema: " + inB);
            System.out.println("Tenant A term found in tenant B schema (must be 0): " + aInB);
            System.out.println("Tenant B term found in tenant A schema (must be 0): " + bInA);
            System.out.println("Tenant A term found in public (must be 0): " + aInPublic);
            System.out.println("Tenant B term found in public (must be 0): " + bInPublic);

            assertEquals(1, inA, "tenant A's write must land in tenant A's own schema");
            assertEquals(1, inB, "tenant B's write must land in tenant B's own schema");
            assertEquals(0, aInB, "tenant A's term must never appear in tenant B's schema");
            assertEquals(0, bInA, "tenant B's term must never appear in tenant A's schema");
            assertEquals(0, aInPublic, "tenant A's term must never leak into public");
            assertEquals(0, bInPublic, "tenant B's term must never leak into public");
        } finally {
            executor.shutdown();
            TenantContext.clear();
        }
    }

    @Test
    void missingTenantContextNeverWritesToPublicViaTheRealExecutor() throws Exception {
        String dbUrl  = System.getenv("NEXUS_DB_URL");
        String dbUser = System.getenv("NEXUS_DB_USERNAME");
        String dbPass = System.getenv("NEXUS_DB_PASSWORD");
        if (isBlank(dbUrl) || isBlank(dbUser) || isBlank(dbPass)) {
            System.out.println("Skipping — NEXUS_DB_URL/NEXUS_DB_USERNAME/NEXUS_DB_PASSWORD required.");
            return;
        }

        DriverManagerDataSource raw = new DriverManagerDataSource(dbUrl, dbUser, dbPass);
        raw.setDriverClassName("org.postgresql.Driver");
        TenantAwareDataSource dataSource = new TenantAwareDataSource(raw);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LearnedMappingRepository mappingRepository = new LearnedMappingRepository(jdbc);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor)
                new SemanticLearningAsyncConfig().semanticLearningExecutor();

        String term = "zz_diag_no_context_" + System.nanoTime();
        try {
            TenantContext.clear(); // deliberately no tenant context established
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger ran = new AtomicInteger(0);

            executor.execute(() -> {
                ran.incrementAndGet();
                mappingRepository.upsert(new LearnedMapping(null, "PLATFORM", term,
                        "status = 'should_never_be_written'", "run-diag-no-ctx", "QUERY_SUCCESS",
                        0.5, 1, Instant.now(), false, null, null, null));
                latch.countDown();
            });

            // Give the pool a moment; the task must never actually run (fail-closed no-op).
            latch.await(3, TimeUnit.SECONDS);
            assertEquals(0, ran.get(), "the wrapped task must never execute without a tenant context");

            Integer inPublic = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM public.nexus_learned_mapping WHERE business_term = ?",
                    Integer.class, term);
            System.out.println("Row written to public with no tenant context (must be 0): " + inPublic);
            assertEquals(0, inPublic, "a missing tenant context must never result in a public write");
        } finally {
            executor.shutdown();
            TenantContext.clear();
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
