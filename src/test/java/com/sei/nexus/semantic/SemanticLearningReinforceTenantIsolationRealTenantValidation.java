package com.sei.nexus.semantic;

import com.sei.nexus.tenant.TenantAwareDataSource;
import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DIAGNOSTIC / LIVE VALIDATION — real database, two real tenant schemas. Excluded from
 * Surefire's default run (class name does not end in {@code *Test.java}).
 *
 * <p>Covers, specifically for {@code SemanticLearningService.reinforceFromFeedback} now that it
 * runs on {@code @Async("semanticLearningExecutor")}, the same properties already proven live for
 * {@code learnFromRun}/{@code captureLiteralBinding} in {@link
 * SemanticLearningTenantIsolationRealTenantValidation}: tenant A propagation, tenant B
 * propagation, missing-context fail-closed (no {@code public} access), and concurrent tenant
 * isolation — using the exact repository calls {@code reinforceFromFeedback} makes
 * ({@code mappingRepository.reinforce}), routed through the real, unmodified production {@link
 * SemanticLearningAsyncConfig#semanticLearningExecutor} bean.
 *
 * <p>Writes only diagnostic-tagged rows (business_term prefixed {@code zz_diag_reinforce_}),
 * cleaned up by each test; nothing here mutates or removes any pre-existing data.
 */
class SemanticLearningReinforceTenantIsolationRealTenantValidation {

    private static final String SCHEMA_A = "tenant_persistent_ai_test";
    private static final String SCHEMA_B = "tenant_maryland_corporations";

    @Test
    void tenantAAndTenantBReinforceWritesLandInTheirOwnSchemasOnly() throws Exception {
        Db db = Db.connectOrSkip();
        if (db == null) return;

        String termA = "zz_diag_reinforce_a_" + System.nanoTime();
        String termB = "zz_diag_reinforce_b_" + System.nanoTime();
        String keyA, keyB;

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor)
                new SemanticLearningAsyncConfig().semanticLearningExecutor();
        try {
            // Seed each mapping synchronously in its own schema (mirrors a prior learnFromRun write).
            TenantContext.set(SCHEMA_A);
            keyA = db.mappingRepository.upsert(new LearnedMapping(null, "PLATFORM", termA,
                    "status = 'diagnostic_reinforce_a'", "run-diag-a", "QUERY_SUCCESS",
                    0.5, 1, Instant.now(), false, null, null)).mappingKey();
            TenantContext.clear();

            TenantContext.set(SCHEMA_B);
            keyB = db.mappingRepository.upsert(new LearnedMapping(null, "PLATFORM", termB,
                    "status = 'diagnostic_reinforce_b'", "run-diag-b", "QUERY_SUCCESS",
                    0.5, 1, Instant.now(), false, null, null)).mappingKey();
            TenantContext.clear();

            CountDownLatch latch = new CountDownLatch(2);
            AtomicInteger errors = new AtomicInteger(0);

            // Submit reinforce(...) the exact way reinforceFromFeedback does — via the real
            // executor, with TenantContext set to the correct tenant on the submitting thread.
            TenantContext.set(SCHEMA_A);
            executor.execute(() -> {
                try { db.mappingRepository.reinforce(keyA); }
                catch (Exception e) { errors.incrementAndGet(); }
                finally { latch.countDown(); }
            });
            TenantContext.clear();

            TenantContext.set(SCHEMA_B);
            executor.execute(() -> {
                try { db.mappingRepository.reinforce(keyB); }
                catch (Exception e) { errors.incrementAndGet(); }
                finally { latch.countDown(); }
            });
            TenantContext.clear();

            assertTrue(latch.await(15, TimeUnit.SECONDS), "both async reinforcements must complete");
            assertEquals(0, errors.get(), "neither reinforcement should throw");

            Double confA = db.jdbc.queryForObject(
                    "SELECT confidence FROM " + SCHEMA_A + ".nexus_learned_mapping WHERE mapping_key = ?",
                    Double.class, keyA);
            Double confB = db.jdbc.queryForObject(
                    "SELECT confidence FROM " + SCHEMA_B + ".nexus_learned_mapping WHERE mapping_key = ?",
                    Double.class, keyB);
            Integer aInPublic = db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM public.nexus_learned_mapping WHERE business_term = ?",
                    Integer.class, termA);
            Integer bInPublic = db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM public.nexus_learned_mapping WHERE business_term = ?",
                    Integer.class, termB);

            System.out.println("Tenant A confidence after reinforce (0.58 expected): " + confA);
            System.out.println("Tenant B confidence after reinforce (0.58 expected): " + confB);
            System.out.println("Tenant A term leaked into public (must be 0): " + aInPublic);
            System.out.println("Tenant B term leaked into public (must be 0): " + bInPublic);

            assertEquals(0.58, confA, 0.0001, "tenant A's own mapping must be reinforced in tenant A's own schema");
            assertEquals(0.58, confB, 0.0001, "tenant B's own mapping must be reinforced in tenant B's own schema");
            assertEquals(0, aInPublic);
            assertEquals(0, bInPublic);
        } finally {
            executor.shutdown();
            TenantContext.clear();
            db.jdbc.update("DELETE FROM " + SCHEMA_A + ".nexus_learned_mapping WHERE business_term = ?", termA);
            db.jdbc.update("DELETE FROM " + SCHEMA_B + ".nexus_learned_mapping WHERE business_term = ?", termB);
        }
    }

    @Test
    void missingTenantContextNeverReinforcesViaTheRealExecutor() throws Exception {
        Db db = Db.connectOrSkip();
        if (db == null) return;

        String term = "zz_diag_reinforce_no_ctx_" + System.nanoTime();
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor)
                new SemanticLearningAsyncConfig().semanticLearningExecutor();
        String key;
        try {
            TenantContext.set(SCHEMA_A);
            key = db.mappingRepository.upsert(new LearnedMapping(null, "PLATFORM", term,
                    "status = 'diagnostic_reinforce_no_ctx'", "run-diag-no-ctx", "QUERY_SUCCESS",
                    0.5, 1, Instant.now(), false, null, null)).mappingKey();
            TenantContext.clear();

            AtomicInteger ran = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            TenantContext.clear(); // deliberately no tenant context established at submission
            executor.execute(() -> {
                ran.incrementAndGet();
                db.mappingRepository.reinforce(key);
                latch.countDown();
            });
            latch.await(3, TimeUnit.SECONDS);

            assertEquals(0, ran.get(), "the wrapped reinforcement must never execute without a tenant context");

            Double confA = db.jdbc.queryForObject(
                    "SELECT confidence FROM " + SCHEMA_A + ".nexus_learned_mapping WHERE mapping_key = ?",
                    Double.class, key);
            Integer inPublic = db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM public.nexus_learned_mapping WHERE business_term = ?",
                    Integer.class, term);
            System.out.println("Tenant A confidence, must remain 0.5 (not reinforced): " + confA);
            System.out.println("Rows written to public with no tenant context (must be 0): " + inPublic);

            assertEquals(0.5, confA, 0.0001, "no tenant context ⇒ the mapping must remain un-reinforced");
            assertEquals(0, inPublic);
        } finally {
            executor.shutdown();
            TenantContext.clear();
            db.jdbc.update("DELETE FROM " + SCHEMA_A + ".nexus_learned_mapping WHERE business_term = ?", term);
        }
    }

    @Test
    void concurrentTenantATenantBReinforceSubmissionsNeverCrossContaminate() throws Exception {
        Db db = Db.connectOrSkip();
        if (db == null) return;

        String termA = "zz_diag_reinforce_conc_a_" + System.nanoTime();
        String termB = "zz_diag_reinforce_conc_b_" + System.nanoTime();
        String keyA, keyB;

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor)
                new SemanticLearningAsyncConfig().semanticLearningExecutor();
        try {
            TenantContext.set(SCHEMA_A);
            keyA = db.mappingRepository.upsert(new LearnedMapping(null, "PLATFORM", termA,
                    "status = 'diagnostic_reinforce_conc_a'", "run-diag-conc-a", "QUERY_SUCCESS",
                    0.5, 1, Instant.now(), false, null, null)).mappingKey();
            TenantContext.clear();

            TenantContext.set(SCHEMA_B);
            keyB = db.mappingRepository.upsert(new LearnedMapping(null, "PLATFORM", termB,
                    "status = 'diagnostic_reinforce_conc_b'", "run-diag-conc-b", "QUERY_SUCCESS",
                    0.5, 1, Instant.now(), false, null, null)).mappingKey();
            TenantContext.clear();

            int rounds = 10;
            CountDownLatch latch = new CountDownLatch(rounds * 2);
            ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
            Map<String, AtomicInteger> reinforceCounts = new ConcurrentHashMap<>();
            reinforceCounts.put(keyA, new AtomicInteger());
            reinforceCounts.put(keyB, new AtomicInteger());

            for (int i = 0; i < rounds; i++) {
                TenantContext.set(SCHEMA_A);
                executor.execute(() -> {
                    try {
                        db.mappingRepository.reinforce(keyA);
                        reinforceCounts.get(keyA).incrementAndGet();
                    } catch (Exception e) { errors.add("A: " + e); }
                    finally { latch.countDown(); }
                });
                TenantContext.clear();

                TenantContext.set(SCHEMA_B);
                executor.execute(() -> {
                    try {
                        db.mappingRepository.reinforce(keyB);
                        reinforceCounts.get(keyB).incrementAndGet();
                    } catch (Exception e) { errors.add("B: " + e); }
                    finally { latch.countDown(); }
                });
                TenantContext.clear();
            }

            assertTrue(latch.await(20, TimeUnit.SECONDS), "all submitted reinforcements must complete");
            assertEquals(List.of(), List.copyOf(errors), "no reinforcement may target the wrong tenant's schema");
            assertEquals(rounds, reinforceCounts.get(keyA).get());
            assertEquals(rounds, reinforceCounts.get(keyB).get());

            Integer aInB = db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + SCHEMA_B + ".nexus_learned_mapping WHERE business_term = ?",
                    Integer.class, termA);
            Integer bInA = db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + SCHEMA_A + ".nexus_learned_mapping WHERE business_term = ?",
                    Integer.class, termB);
            assertEquals(0, aInB, "tenant A's term must never appear in tenant B's schema");
            assertEquals(0, bInA, "tenant B's term must never appear in tenant A's schema");
        } finally {
            executor.shutdown();
            TenantContext.clear();
            db.jdbc.update("DELETE FROM " + SCHEMA_A + ".nexus_learned_mapping WHERE business_term = ?", termA);
            db.jdbc.update("DELETE FROM " + SCHEMA_B + ".nexus_learned_mapping WHERE business_term = ?", termB);
        }
    }

    /** Tiny holder for the real JDBC wiring, or null if DB env vars are not set (skip). */
    private static final class Db {
        final JdbcTemplate jdbc;
        final LearnedMappingRepository mappingRepository;

        private Db(JdbcTemplate jdbc, LearnedMappingRepository mappingRepository) {
            this.jdbc = jdbc;
            this.mappingRepository = mappingRepository;
        }

        static Db connectOrSkip() {
            String dbUrl  = System.getenv("NEXUS_DB_URL");
            String dbUser = System.getenv("NEXUS_DB_USERNAME");
            String dbPass = System.getenv("NEXUS_DB_PASSWORD");
            if (isBlank(dbUrl) || isBlank(dbUser) || isBlank(dbPass)) {
                System.out.println("Skipping — NEXUS_DB_URL/NEXUS_DB_USERNAME/NEXUS_DB_PASSWORD required.");
                return null;
            }
            DriverManagerDataSource raw = new DriverManagerDataSource(dbUrl, dbUser, dbPass);
            raw.setDriverClassName("org.postgresql.Driver");
            TenantAwareDataSource dataSource = new TenantAwareDataSource(raw);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            return new Db(jdbc, new LearnedMappingRepository(jdbc));
        }

        private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    }
}
