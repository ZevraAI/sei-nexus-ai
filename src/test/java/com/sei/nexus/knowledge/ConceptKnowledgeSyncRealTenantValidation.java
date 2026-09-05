package com.sei.nexus.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.onboarding.TenantSettingsRepository;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.semantic.SemanticRepository;
import com.sei.nexus.semantic.SemanticService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DIAGNOSTIC / LIVE VALIDATION — real database, real OpenAI, real tenant {@code
 * persistent-ai-test}. Excluded from Surefire's default {@code **&#47;*Test.java} run (same
 * {@code *RealTenantValidation} naming convention as this session's other real-tenant diagnostics).
 *
 * <p><b>IMPORTANT DESIGN NOTE, learned the hard way while building this test</b>: this tenant's
 * real, applied Industry Pack means its Concept Knowledge documents are genuine, PERMANENT
 * production-equivalent data — not test fixtures. An earlier version of this test cleaned up
 * "whatever it created," which is only safe if what it creates is genuinely disposable; here it is
 * not, because synchronize() converging a real tenant's real concepts into the Vector Store is
 * exactly the intended, permanent effect of this feature. That earlier version transiently deleted
 * real concept documents it should never have touched. This version NEVER deletes anything that
 * corresponds to this tenant's real authoritative projection — the one artificial, synthetic stale
 * entry this test injects for the deletion proof is the only thing it ever removes.
 *
 * <p>Proves, against the actual OpenAI account:
 * <ul>
 *   <li><b>Convergence</b>: synchronize() creates whatever authoritative concepts are missing and
 *       leaves already-correct ones alone (no failures).</li>
 *   <li><b>Retrieval</b>: native {@code file_search} can retrieve the tenant's real Concept
 *       Knowledge after synchronize() runs.</li>
 *   <li><b>Idempotent no-op</b>: an immediate second synchronize() call uploads/replaces nothing.</li>
 *   <li><b>Stale removal</b>: a synthetic, non-authoritative "fake" concept document (uploaded by
 *       this test only, never derived from real Postgres data) is correctly detected as stale and
 *       removed by synchronize() — proving the delete path against the real API without risking
 *       any of the tenant's genuine data.</li>
 * </ul>
 */
class ConceptKnowledgeSyncRealTenantValidation {

    private static final String TENANT_SLUG = "persistent-ai-test";

    @Test
    void realSyncConvergesRetrievesAndRemovesOnlyASyntheticStaleEntry() throws Exception {
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

        AzureOpenAiClient aiClient = new AzureOpenAiClient(objectMapper, null);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        com.sei.nexus.semantic.LearnedMappingRepository learnedMappingRepository =
                new com.sei.nexus.semantic.LearnedMappingRepository(jdbc);
        ConceptKnowledgeMaterializationService materializer = new ConceptKnowledgeMaterializationService(
                tenantRepository, packRepository, semanticService, aiClient, objectMapper, learnedMappingRepository);
        ConceptKnowledgeSynchronizationService sync = new ConceptKnowledgeSynchronizationService(
                tenantRepository, aiClient, materializer, tenantSettingsRepository, learnedMappingRepository);

        Tenant tenant = tenantRepository.findBySlug(TENANT_SLUG).orElseThrow();
        String schema = tenant.schemaName();
        String vectorStoreId = tenant.aiKnowledgeVectorStoreId();
        if (vectorStoreId == null || vectorStoreId.isBlank()) {
            System.out.println("Skipping — tenant '" + TENANT_SLUG + "' has no Vector Store provisioned.");
            return;
        }

        try {
            TenantContext.set(schema);

            // ── Convergence: create whatever's missing, leave the rest alone ────────────────
            ConceptKnowledgeSynchronizationService.SyncResult first = sync.synchronize();
            System.out.println("Convergence run — status=" + first.status() + " created=" + first.createdCount()
                    + " updated=" + first.updatedCount() + " deleted=" + first.deletedCount()
                    + " unchanged=" + first.unchangedCount() + " failed=" + first.failedCount());
            assertEquals(ConceptKnowledgeSynchronizationService.Status.IN_SYNC, first.status(),
                    "a real sync against this tenant's real state must converge without failures");
            assertEquals(0, first.failedCount());

            long totalManaged = aiClient.listVectorStoreFiles(vectorStoreId).stream()
                    .filter(r -> r.attributes() != null && "business-concept".equals(r.attributes().get("knowledge_type")))
                    .count();
            System.out.println("Vector store now has " + totalManaged + " Zevra-managed concept document(s)");
            if (totalManaged == 0) {
                System.out.println("Tenant has no applicable Pack/concept projection — nothing further to prove.");
                return;
            }

            // ── Retrieval proof ──────────────────────────────────────────────────────────────
            String retrieval = aiClient.fileSearchQuery(vectorStoreId, "What business concepts are defined for this tenant?");
            System.out.println("File Search retrieval (first 300 chars): "
                    + retrieval.substring(0, Math.min(300, retrieval.length())));
            assertNotNull(retrieval);
            assertFalse(retrieval.isBlank());

            // ── Idempotent no-op on immediate re-run ────────────────────────────────────────
            ConceptKnowledgeSynchronizationService.SyncResult second = sync.synchronize();
            System.out.println("Immediate re-run — created=" + second.createdCount()
                    + " updated=" + second.updatedCount() + " deleted=" + second.deletedCount()
                    + " unchanged=" + second.unchangedCount());
            assertEquals(0, second.createdCount(), "nothing should be re-created on an immediate re-run");
            assertEquals(0, second.updatedCount(), "content is unchanged — nothing should be replaced");

            // ── Stale-removal proof, using a SYNTHETIC entry only — never real tenant data ──
            // Upload a fake "concept" that does not correspond to anything in the authoritative
            // projection (a concept_key/pack_key combination that cannot exist for this
            // connection). synchronize() must detect and remove it as stale, proving the
            // detach+delete path against the real API without risking any genuine concept.
            String fakeFileId = aiClient.uploadFile(
                    "{\"concept_key\":\"zz-synthetic-test-concept\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "concept-zz-synthetic-test.json", "application/json");
            String fakeUid = "conn-c1590229::retail-v1::zz-synthetic-test-concept";
            aiClient.attachFileToVectorStore(vectorStoreId, fakeFileId, Map.of(
                    "concept_uid", fakeUid,
                    "concept_key", "zz-synthetic-test-concept",
                    "knowledge_type", "business-concept",
                    "pack_key", "retail-v1",
                    "connection_key", "conn-c1590229",
                    "content_hash", "synthetic",
                    "projection_version", "1"));
            System.out.println("Uploaded synthetic stale entry '" + fakeUid + "' (file " + fakeFileId + ")");

            ConceptKnowledgeSynchronizationService.SyncResult third = sync.synchronize();
            System.out.println("Post-synthetic-injection run — created=" + third.createdCount()
                    + " updated=" + third.updatedCount() + " deleted=" + third.deletedCount());
            assertEquals(1, third.deletedCount(), "the synthetic stale entry must be detected and removed");

            boolean synthEntryStillPresent = aiClient.listVectorStoreFiles(vectorStoreId).stream()
                    .anyMatch(r -> r.attributes() != null && fakeUid.equals(r.attributes().get("concept_uid")));
            System.out.println("Synthetic entry still present after removal (must be false): " + synthEntryStillPresent);
            assertFalse(synthEntryStillPresent, "the synthetic stale entry must actually disappear from the Vector Store");
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
