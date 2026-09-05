package com.sei.nexus.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.run.RunRepository;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantAwareDataSource;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIAGNOSTIC ONLY — real DB, real OpenAI, real tenant {@code persistent-ai-test}. Excluded from
 * Surefire's default run.
 *
 * <p>Calls the REAL, unmodified {@link TermExtractor#extract} and {@link
 * LearnedMappingRepository#upsert} directly, and — the decisive test — the REAL, unmodified
 * {@link SemanticLearningService#learnFromRun} method itself, constructed with real
 * collaborators (real DB, real OpenAI) but NOT through a Spring container — so {@code @Async}
 * has no effect and the call executes synchronously in this thread, in the exact order
 * production code follows internally, letting every step and any exception be observed directly
 * rather than swallowed by a background thread. This bypasses only the Spring-async
 * *infrastructure* itself (thread pool, async exception handler) — a disclosed, narrow gap, not
 * the learning logic, which runs completely unmodified.
 *
 * <p>Uses the EXACT turn 2 text and SQL from the real observed interaction:
 * question = "open means status in submitted, acknowledged, partially_received",
 * sql = the real executed query. No production code, prompt, or schema is changed.
 */
class OpenPurchaseOrdersClarificationLearningRealTenantValidation {

    private static final String TENANT_SLUG = "persistent-ai-test";

    @Test
    void traceLearningPipelineForTheClarificationAnswerTurn() throws Exception {
        String dbUrl  = System.getenv("NEXUS_DB_URL");
        String dbUser = System.getenv("NEXUS_DB_USERNAME");
        String dbPass = System.getenv("NEXUS_DB_PASSWORD");
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (isBlank(dbUrl) || isBlank(dbUser) || isBlank(dbPass) || isBlank(apiKey)) {
            System.out.println("Skipping — NEXUS_DB_URL/NEXUS_DB_USERNAME/NEXUS_DB_PASSWORD/OPENAI_API_KEY required.");
            return;
        }

        DriverManagerDataSource raw = new DriverManagerDataSource(dbUrl, dbUser, dbPass);
        raw.setDriverClassName("org.postgresql.Driver");
        TenantAwareDataSource dataSource = new TenantAwareDataSource(raw);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper();

        TenantRepository tenantRepository = new TenantRepository(jdbc);
        RunRepository runRepository = new RunRepository(jdbc);
        LearnedMappingRepository mappingRepository = new LearnedMappingRepository(jdbc);
        CorrectionRepository correctionRepository = new CorrectionRepository(jdbc);
        SemanticRepository semanticRepository = new SemanticRepository(jdbc);
        SemanticService semanticService = new SemanticService(jdbc, null, semanticRepository);

        AzureOpenAiClient aiClient = new AzureOpenAiClient(objectMapper, null);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        TermExtractor termExtractor = new TermExtractor(aiClient, objectMapper);
        CorrectionDetector correctionDetector = new CorrectionDetector(aiClient, objectMapper);
        SemanticLearningService learningService = new SemanticLearningService(
                termExtractor, correctionDetector, mappingRepository, correctionRepository,
                runRepository, tenantRepository, semanticService);

        Tenant tenant = tenantRepository.findBySlug(TENANT_SLUG).orElseThrow();
        String schema = tenant.schemaName();

        String turn2Question = "open means status in submitted, acknowledged, partially_received";
        String turn2Sql = "SELECT po_number, buyer_name, ordered_date, expected_delivery_date, total_ordered_amount, status "
                + "FROM retail_core.purchase_orders WHERE status IN ('submitted', 'acknowledged', 'partially_received')";
        String domainKey = "PLATFORM";
        String runKey = "run-diag-clarification-learning";
        String conversationId = "conv-diag-clarification-learning";

        try {
            TenantContext.set(schema);

            // ── Step 1: TermExtractor in isolation — exact production method, exact turn-2 text ──
            System.out.println("=== STEP 1: TermExtractor.extract(question, sql) ===");
            System.out.println("question=" + turn2Question);
            System.out.println("sql=" + turn2Sql);
            List<TermExtractor.ExtractedTerm> terms;
            try {
                terms = termExtractor.extract(turn2Question, turn2Sql);
                System.out.println("TermExtractor result: " + terms);
            } catch (Exception e) {
                System.out.println("TermExtractor THREW: " + e);
                e.printStackTrace();
                terms = List.of();
            }

            // ── Step 2: count BEFORE ──────────────────────────────────────────────────────────
            Integer countBefore = jdbc.queryForObject("SELECT COUNT(*) FROM nexus_learned_mapping", Integer.class);
            System.out.println("\nnexus_learned_mapping count BEFORE = " + countBefore);

            // ── Step 3: the REAL, unmodified learnFromRun(), called directly (bypasses only the
            //    Spring @Async proxy — the learning LOGIC itself is completely unmodified) ──────
            System.out.println("\n=== STEP 2: SemanticLearningService.learnFromRun(...) — the real production method ===");
            System.out.println("runKey=" + runKey + " question=" + turn2Question + " domainKey=" + domainKey
                    + " conversationId=" + conversationId);
            Exception thrown = null;
            try {
                learningService.learnFromRun(runKey, turn2Question, turn2Sql, domainKey, conversationId);
            } catch (Exception e) {
                thrown = e;
                System.out.println("learnFromRun THREW (should not happen — method catches internally): " + e);
                e.printStackTrace();
            }
            System.out.println("learnFromRun returned normally=" + (thrown == null));

            // ── Step 4: count AFTER + actual persisted rows ──────────────────────────────────
            Integer countAfter = jdbc.queryForObject("SELECT COUNT(*) FROM nexus_learned_mapping", Integer.class);
            System.out.println("\nnexus_learned_mapping count AFTER = " + countAfter);

            List<LearnedMapping> rows = jdbc.query(
                    "SELECT mapping_key, domain_key, business_term, sql_pattern, source_run_key, source, "
                            + "confidence, use_count, promoted FROM nexus_learned_mapping WHERE source_run_key = ?",
                    (rs, i) -> new LearnedMapping(
                            rs.getString("mapping_key"), rs.getString("domain_key"), rs.getString("business_term"),
                            rs.getString("sql_pattern"), rs.getString("source_run_key"), rs.getString("source"),
                            rs.getDouble("confidence"), rs.getInt("use_count"), null,
                            rs.getBoolean("promoted"), null, null, null),
                    runKey);
            System.out.println("Rows persisted for runKey=" + runKey + ": " + rows);

            if (!terms.isEmpty() && !rows.isEmpty()) {
                System.out.println("\nCONCLUSION: TermExtractor produced a mapping AND it was persisted successfully.");
            } else if (!terms.isEmpty() && rows.isEmpty()) {
                System.out.println("\nCONCLUSION: TermExtractor produced a mapping but persistence did not occur — "
                        + "check the exception output above.");
            } else {
                System.out.println("\nCONCLUSION: TermExtractor itself returned no usable terms for this exact "
                        + "question/SQL pair — persistence was correctly skipped (nothing to persist).");
            }

            assertTrue(true); // diagnostic — evidence is in the console output above
        } finally {
            TenantContext.clear();
        }
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
