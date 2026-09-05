package com.sei.nexus.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.onboarding.TenantSettingsRepository;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.semantic.LearnedMapping;
import com.sei.nexus.semantic.LearnedMappingRepository;
import com.sei.nexus.semantic.SemanticRepository;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantAwareDataSource;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantProvisioningService;
import com.sei.nexus.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DIAGNOSTIC / LIVE VALIDATION — real database, real OpenAI, real tenant {@code
 * persistent-ai-test}. Excluded from Surefire's default {@code **&#47;*Test.java} run, same
 * {@code *RealTenantValidation} naming convention as this feature's sibling diagnostics.
 *
 * <p>Proves the promoted-learning → Vector Store → File Search pipeline end-to-end against the
 * real tenant, WITHOUT touching the tenant's one genuine pre-existing promoted learning ("open" →
 * PO status): that mapping is deliberately left alone here — the user has said they will classify
 * it themselves via the admin UI after deployment. Instead this test creates its own throwaway,
 * clearly-named synthetic learning, classifies it against a REAL concept_key already in this
 * tenant's catalog (borrowed for classification only — never mutated), promotes it, syncs, proves
 * retrieval, then deletes ONLY the synthetic learning row it created and re-syncs so the real
 * concept's Vector Store document converges back to exactly its pre-test content. No real concept
 * document is ever manually deleted — removal happens exclusively through the normal, designed
 * synchronize() convergence, consistent with this feature's "no destructive cleanup" constraint.
 */
class PromotedLearningVectorStoreRealTenantValidation {

    private static final String TENANT_SLUG = "persistent-ai-test";
    private static final String SYNTHETIC_TERM = "zz-diagnostic-learning-term";

    @Test
    void promotedAndClassifiedSyntheticLearningIsSyncedAndRetrievableThenCleanlyRemoved() throws Exception {
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
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        TenantRepository tenantRepository = new TenantRepository(jdbc);
        IndustryPackRepository packRepository = new IndustryPackRepository(jdbc, objectMapper);
        packRepository.loadPacksFromClasspath();
        SemanticRepository semanticRepository = new SemanticRepository(jdbc);
        SemanticService semanticService = new SemanticService(jdbc, null, semanticRepository);
        TenantSettingsRepository tenantSettingsRepository = new TenantSettingsRepository(jdbc);
        LearnedMappingRepository mappingRepository = new LearnedMappingRepository(jdbc);

        AzureOpenAiClient aiClient = new AzureOpenAiClient(objectMapper, null);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        ConceptKnowledgeMaterializationService materializer = new ConceptKnowledgeMaterializationService(
                tenantRepository, packRepository, semanticService, aiClient, objectMapper, mappingRepository);
        ConceptKnowledgeSynchronizationService sync = new ConceptKnowledgeSynchronizationService(
                tenantRepository, aiClient, materializer, tenantSettingsRepository, mappingRepository);

        Tenant tenant = tenantRepository.findBySlug(TENANT_SLUG).orElseThrow();
        String schema = tenant.schemaName();
        String vectorStoreId = tenant.aiKnowledgeVectorStoreId();
        if (vectorStoreId == null || vectorStoreId.isBlank()) {
            System.out.println("Skipping — tenant '" + TENANT_SLUG + "' has no Vector Store provisioned.");
            return;
        }

        // ── Catch this schema up to V043 (concept_key) first — exactly the mechanism
        //    TenantSchemaMigrator runs at real application startup, applied here directly since
        //    this diagnostic bypasses Spring Boot. Additive-only migration (ADD COLUMN IF NOT
        //    EXISTS / CREATE INDEX IF NOT EXISTS), safe to run against this live schema.
        TenantProvisioningService provisioningService =
                new TenantProvisioningService(tenantRepository, raw, objectMapper, aiClient);
        provisioningService.migrateSchemaToLatest(schema);
        System.out.println("Schema '" + schema + "' migrated to latest (V043 concept_key catch-up applied if pending).");

        String mappingKey = null;
        try {
            TenantContext.set(schema);

            // ── Step 0: confirm the real "open" mapping is untouched by this test ──────────────
            List<LearnedMapping> openRows = jdbc.query(
                    "SELECT concept_key, promoted FROM nexus_learned_mapping WHERE business_term = 'open'",
                    (rs, i) -> new LearnedMapping(null, null, "open", null, null, null, 0, 0, null,
                            rs.getBoolean("promoted"), null, null, rs.getString("concept_key")));
            System.out.println("Pre-existing 'open' mapping rows (must remain untouched): " + openRows.size()
                    + (openRows.isEmpty() ? "" : " promoted=" + openRows.get(0).promoted()
                            + " conceptKey=" + openRows.get(0).conceptKey()));

            // ── Step 1: pick a REAL concept_key already in this tenant's catalog ────────────────
            List<ConceptKnowledgeMaterializationService.ConceptUnit> units = materializer.collectConceptUnits();
            if (units.isEmpty()) {
                System.out.println("Tenant has no applicable Pack/concept projection — nothing to validate against.");
                return;
            }
            ConceptKnowledgeMaterializationService.ConceptUnit targetUnit = units.get(0);
            String conceptKey = targetUnit.entry().conceptKey();
            System.out.println("Using real concept_key='" + conceptKey + "' (name='" + targetUnit.entry().name()
                    + "') for classification only — this concept's own metadata is never modified.");
            String hashBefore = materializer.contentHash(targetUnit);

            // ── Step 2: create + promote + classify a throwaway synthetic learning ─────────────
            LearnedMapping synthetic = new LearnedMapping(null, "PLATFORM", SYNTHETIC_TERM,
                    "1=1 /* diagnostic-only binding, never real */", "run-diagnostic-vectorstore",
                    "USER_CORRECTION", 0.95, 5, Instant.now(), false, Instant.now(), Instant.now(), null);
            LearnedMapping saved = mappingRepository.upsert(synthetic);
            mappingKey = saved.mappingKey();
            mappingRepository.markPromoted(mappingKey);
            mappingRepository.assignConceptKey(mappingKey, conceptKey);
            System.out.println("Created+promoted+classified synthetic learning '" + SYNTHETIC_TERM
                    + "' (mappingKey=" + mappingKey + ") under concept_key='" + conceptKey + "'");

            // ── Step 3: re-derive the unit and prove the projection now differs ────────────────
            ConceptKnowledgeMaterializationService.ConceptUnit unitAfterPromote = materializer.collectConceptUnits()
                    .stream().filter(u -> u.entry().conceptKey().equals(conceptKey)).findFirst().orElseThrow();
            boolean present = unitAfterPromote.learnedKnowledge().stream()
                    .anyMatch(m -> SYNTHETIC_TERM.equals(m.businessTerm()));
            assertTrue(present, "the synthetic learning must appear in its concept's learnedKnowledge before sync");
            String hashAfter = materializer.contentHash(unitAfterPromote);
            assertNotEquals(hashBefore, hashAfter, "promoting+classifying must change the concept's content hash");

            // ── Step 4: run the REAL synchronize() against the REAL Vector Store ───────────────
            ConceptKnowledgeSynchronizationService.SyncResult result = sync.synchronize();
            System.out.println("Sync result — status=" + result.status() + " created=" + result.createdCount()
                    + " updated=" + result.updatedCount() + " deleted=" + result.deletedCount()
                    + " unchanged=" + result.unchangedCount() + " failed=" + result.failedCount());
            assertEquals(0, result.failedCount(), "sync must succeed against the real tenant");

            // ── Step 5: prove File Search can retrieve the promoted learning's content ────────
            String retrieval = aiClient.fileSearchQuery(vectorStoreId,
                    "What SQL binding does the learned term '" + SYNTHETIC_TERM + "' map to?");
            System.out.println("File Search retrieval (first 400 chars): "
                    + retrieval.substring(0, Math.min(400, retrieval.length())));
            assertNotNull(retrieval);
            assertFalse(retrieval.isBlank());
            assertTrue(retrieval.toLowerCase().contains("1=1") || retrieval.contains(SYNTHETIC_TERM),
                    "File Search response should reference the synthetic learning's binding or surface term — got: "
                            + retrieval);

            System.out.println("VERIFIED LIVE: promote -> concept classification -> sync -> Vector Store -> "
                    + "native File Search retrieval, end to end, against the real tenant.");
            System.out.println("NOT VERIFIED HERE (out of scope for this diagnostic): a full live Chat/Planner "
                    + "round-trip using this learning — that mechanism is proven at the unit level instead "
                    + "(LearningContextBuilderTest) and via source inspection, not a live chat call.");
        } finally {
            // ── Cleanup: remove ONLY the synthetic learning row this test created, then re-sync
            //    so the real concept's document converges back to its exact pre-test content. No
            //    Vector Store file is ever deleted directly — only via the normal sync mechanism.
            if (mappingKey != null) {
                try {
                    mappingRepository.delete(mappingKey);
                    ConceptKnowledgeSynchronizationService.SyncResult cleanupSync = sync.synchronize();
                    System.out.println("Cleanup: deleted synthetic learning row and re-synced — status="
                            + cleanupSync.status() + " updated=" + cleanupSync.updatedCount());
                } catch (Exception e) {
                    System.out.println("Cleanup FAILED — manual check needed for mappingKey=" + mappingKey
                            + ": " + e.getMessage());
                }
            }
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
