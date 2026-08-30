package com.sei.nexus.agentbrain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.onboarding.TenantSettingsRepository;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Persistent AI Knowledge V1, Stage 1 File Search integration — {@link
 * ConceptScopedMetadataResolver}'s new dispatch between the File Search path ({@link
 * ConceptScopedMetadataResolver#resolveStage1Selection}, new) and the legacy catalog-in-prompt
 * path (existing, deprecated but retained). Hand-rolled fakes throughout, same convention as
 * {@code ConceptScopedMetadataResolverTest} (which this file does not modify or duplicate —
 * those 16 tests already prove the legacy path's own behavior byte-for-byte unchanged via the
 * resolver's 4-arg convenience constructor).
 */
class ConceptScopedMetadataResolverFileSearchTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ── fakes ─────────────────────────────────────────────────────────────────────────────────

    static class FakeIndustryPackRepository extends IndustryPackRepository {
        final Map<String, TenantPack> activeByConnection = new LinkedHashMap<>();
        final Map<String, IndustryPack> catalogue = new LinkedHashMap<>();
        FakeIndustryPackRepository() { super(null, new ObjectMapper()); }
        @Override public Optional<TenantPack> findActivePackForConnection(String connectionKey) {
            return Optional.ofNullable(activeByConnection.get(connectionKey));
        }
        @Override public Optional<IndustryPack> findPackById(String packId) {
            return Optional.ofNullable(catalogue.get(packId));
        }
        void assign(String connectionKey, IndustryPack pack) {
            catalogue.put(pack.packId(), pack);
            activeByConnection.put(connectionKey, new TenantPack(pack.packId(), connectionKey, "1.0.0",
                    pack.displayName(), "ACTIVE", Map.of(), 1.0, null, "user@x.com"));
        }
    }

    static class FakeSemanticService extends SemanticService {
        final Map<String, List<String>> usedConceptKeysByConnection = new LinkedHashMap<>();
        final Map<String, List<BusinessEntity>> entitiesByConnection = new LinkedHashMap<>();
        FakeSemanticService() { super(null, null, null); }
        @Override public List<String> findDistinctConceptKeysForConnection(String connectionKey) {
            return usedConceptKeysByConnection.getOrDefault(connectionKey, List.of());
        }
        @Override public List<BusinessEntity> findEntitiesByConnectionAndConcepts(String connectionKey, List<String> conceptKeys) {
            List<BusinessEntity> all = entitiesByConnection.getOrDefault(connectionKey, List.of());
            return all.stream().filter(e -> conceptKeys.contains(e.conceptKey())).toList();
        }
    }

    private static final String FLAG_KEY = "persistent_knowledge_stage1_enabled";

    static class FakeTenantSettingsRepository extends TenantSettingsRepository {
        Boolean flagValue = false; // null simulates a read failure for the flag key specifically
        final Map<String, String> store = new LinkedHashMap<>();
        FakeTenantSettingsRepository() { super(null); }
        @Override public Optional<String> get(String key) {
            if (FLAG_KEY.equals(key)) {
                if (flagValue == null) throw new RuntimeException("simulated settings read failure");
                return Optional.of(String.valueOf(flagValue));
            }
            return Optional.ofNullable(store.get(key));
        }
        @Override public void set(String key, String value) {
            store.put(key, value);
        }
    }

    static class FakeTenantRepository extends TenantRepository {
        final Map<String, Tenant> bySchema = new LinkedHashMap<>();
        FakeTenantRepository() { super(null); }
        @Override public Optional<Tenant> findBySchemaName(String schemaName) {
            return Optional.ofNullable(bySchema.get(schemaName));
        }
        void seed(String schema, String vectorStoreId) {
            bySchema.put(schema, new Tenant(UUID.randomUUID(), schema, schema, schema, "STANDARD", "ACTIVE",
                    "a@b.com", 50, Instant.now(), Instant.now(), vectorStoreId,
                    vectorStoreId != null ? "READY" : null, null, Instant.now()));
        }
    }

    /** Distinguishes which of the two AzureOpenAiClient call shapes was used. */
    static class SpyAiClient extends AzureOpenAiClient {
        boolean chatWithJsonCalled = false;
        boolean fileSearchCalled = false;
        int fileSearchCallCount = 0;
        String lastVectorStoreId;
        String lastFileSearchQuestion;
        String lastPreviousResponseId;
        String scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[]}}";
        String scriptedChatResponse = "{\"metadataRequest\":{\"conceptKeys\":[]}}";
        String scriptedNewResponseId = "resp_default";
        RuntimeException fileSearchFailure; // always throws, chained or not
        RuntimeException chainedOnlyFailure; // throws only when a previousResponseId is supplied

        SpyAiClient() { super(new ObjectMapper(), null); }

        @Override public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
            chatWithJsonCalled = true;
            return scriptedChatResponse;
        }

        Map<String, Object> lastJsonSchema;

        @Override public FileSearchResult chatWithFileSearch(String vectorStoreId, String instructions,
                                                              String question, String previousResponseId) {
            return chatWithFileSearch(vectorStoreId, instructions, question, previousResponseId, null);
        }

        @Override public FileSearchResult chatWithFileSearch(String vectorStoreId, String instructions,
                                                              String question, String previousResponseId,
                                                              Map<String, Object> jsonSchema) {
            fileSearchCalled = true;
            fileSearchCallCount++;
            lastVectorStoreId = vectorStoreId;
            lastFileSearchQuestion = question;
            lastPreviousResponseId = previousResponseId;
            lastJsonSchema = jsonSchema;
            if (fileSearchFailure != null) throw fileSearchFailure;
            if (chainedOnlyFailure != null && previousResponseId != null) throw chainedOnlyFailure;
            return new FileSearchResult(scriptedFileSearchResponse, scriptedNewResponseId);
        }
    }

    private static PackEntity concept(String conceptKey, String name) {
        return new PackEntity(name, List.of(), List.of(), List.of(), "desc", "meaning", conceptKey, "ACTIVE");
    }

    private static IndustryPack retailPack() {
        return new IndustryPack("retail-v1", "RETAIL", "Retail & E-commerce", "2.0.0", "desc",
                List.of(concept("product", "Product"), concept("store", "Store")),
                List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, null);
    }

    private static BusinessEntity entity(String entityKey, String primaryObjectKey, String conceptKey) {
        Instant now = Instant.now();
        return new BusinessEntity(entityKey, "PLATFORM", entityKey, "desc", primaryObjectKey,
                "", "", "ACTIVE", "steward@x.com", now, now, null, null, "retail-v1", conceptKey);
    }

    private FakeIndustryPackRepository packRepository;
    private FakeSemanticService semanticService;
    private SpyAiClient aiClient;
    private FakeTenantSettingsRepository tenantSettings;
    private FakeTenantRepository tenantRepository;

    private ConceptScopedMetadataResolver newResolver() {
        return new ConceptScopedMetadataResolver(packRepository, semanticService, aiClient,
                new ObjectMapper(), tenantSettings, tenantRepository);
    }

    private void setUpCommon() {
        packRepository = new FakeIndustryPackRepository();
        semanticService = new FakeSemanticService();
        aiClient = new SpyAiClient();
        tenantSettings = new FakeTenantSettingsRepository();
        tenantRepository = new FakeTenantRepository();
        packRepository.assign("conn-1", retailPack());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("product", "store"));
        semanticService.entitiesByConnection.put("conn-1", List.of(
                entity("product", "obj-product", "product"),
                entity("store", "obj-store", "store")));
    }

    // ── 1. Flag OFF preserves current behavior ───────────────────────────────────────────────

    @Test
    void flagOffUsesLegacyCatalogPathAndNeverCallsFileSearch() {
        setUpCommon();
        tenantSettings.flagValue = false;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_should_never_be_used");

        newResolver().resolveObjectKeys("conn-1", "any question");

        assertTrue(aiClient.chatWithJsonCalled, "flag off must use the legacy catalog-in-prompt call");
        assertFalse(aiClient.fileSearchCalled, "flag off must never invoke File Search");
    }

    // ── 2/5. Flag ON invokes File Search and skips the legacy catalog entirely ──────────────────

    @Test
    void flagOnWithVectorStoreInvokesFileSearchAndNeverSendsTheLegacyCatalog() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");

        newResolver().resolveObjectKeys("conn-1", "any question");

        assertTrue(aiClient.fileSearchCalled, "flag on with a vector store must use File Search");
        assertFalse(aiClient.chatWithJsonCalled,
                "the legacy catalog-in-prompt call must never fire when File Search Stage 1 succeeds");
    }

    // ── 3. File Search receives the current tenant's Vector Store ID ────────────────────────────

    @Test
    void fileSearchReceivesTheCurrentTenantsOwnVectorStoreId() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x_specific");

        newResolver().resolveObjectKeys("conn-1", "q");

        assertEquals("vs_tenant_x_specific", aiClient.lastVectorStoreId);
    }

    // ── 4. The user question reaches the File Search-enabled LLM verbatim ───────────────────────

    @Test
    void theExactUserQuestionReachesTheFileSearchCall() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");

        newResolver().resolveObjectKeys("conn-1", "Show me all open orders.");

        assertEquals("Show me all open orders.", aiClient.lastFileSearchQuestion);
    }

    // ── 6. File Search failure falls back safely to the legacy path ─────────────────────────────

    @Test
    void fileSearchFailureFallsBackToTheLegacyPathForThisCall() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.fileSearchFailure = new RuntimeException("simulated OpenAI failure");
        aiClient.scriptedChatResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]}}";

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "q");

        assertTrue(aiClient.fileSearchCalled, "File Search must still have been attempted");
        assertTrue(aiClient.chatWithJsonCalled, "a File Search failure must fall back to the legacy call");
        assertTrue(result.isPresent());
        assertEquals(List.of("obj-product"), result.get(), "the legacy fallback's own result must still be returned");
    }

    // ── 7. Missing Vector Store falls back safely, without ever attempting File Search ──────────

    @Test
    void missingVectorStoreFallsBackToLegacyPathWithoutAttemptingFileSearch() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", null); // provisioned tenant row, but no vector store yet

        newResolver().resolveObjectKeys("conn-1", "q");

        assertFalse(aiClient.fileSearchCalled, "no vector store ⇒ must not even attempt File Search");
        assertTrue(aiClient.chatWithJsonCalled, "must fall back to the legacy path");
    }

    @Test
    void unknownTenantSchemaFallsBackToLegacyPathWithoutAttemptingFileSearch() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_never_seeded");

        newResolver().resolveObjectKeys("conn-1", "q");

        assertFalse(aiClient.fileSearchCalled);
        assertTrue(aiClient.chatWithJsonCalled);
    }

    @Test
    void noTenantContextAtAllFallsBackToLegacyPathWithoutAttemptingFileSearch() {
        setUpCommon();
        tenantSettings.flagValue = true;
        // TenantContext deliberately left unset — resolves to "public"

        newResolver().resolveObjectKeys("conn-1", "q");

        assertFalse(aiClient.fileSearchCalled, "no tenant context (public schema) must never resolve a vector store");
        assertTrue(aiClient.chatWithJsonCalled);
    }

    // ── 8. No relevant knowledge preserves existing Optional.of(List.of()) semantics ────────────

    @Test
    void noRelevantKnowledgeReturnsOptionalOfEmptyListNotOptionalEmpty() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[]}}";

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "unrelated question");

        assertTrue(result.isPresent(), "Stage 1 IS applicable (File Search ran) — it just found nothing relevant");
        assertTrue(result.get().isEmpty());
    }

    // ── 9. Multiple retrieved concepts are all validated and resolved ───────────────────────────

    @Test
    void multipleRetrievedConceptsAreAllValidatedAndResolvedToObjectKeys() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\",\"store\"]}}";

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "q");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
        assertTrue(result.get().containsAll(List.of("obj-product", "obj-store")));
    }

    @Test
    void invalidConceptKeyFromFileSearchIsDiscardedNeverPassedToStage2() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\",\"made-up-concept\"]}}";

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "q");

        assertEquals(List.of("obj-product"), result.orElseThrow(),
                "an invented concept_key not in this connection's actual used-concept set must be discarded");
    }

    // ── 10. Tenant isolation — vector store id is resolved strictly per-tenant ──────────────────

    @Test
    void tenantAOnlyEverReceivesTenantAsOwnVectorStoreId() {
        setUpCommon();
        tenantSettings.flagValue = true;
        tenantRepository.seed("tenant_a", "vs_a");
        tenantRepository.seed("tenant_b", "vs_b");

        TenantContext.set("tenant_a");
        newResolver().resolveObjectKeys("conn-1", "q");
        assertEquals("vs_a", aiClient.lastVectorStoreId);

        TenantContext.clear();
        aiClient.fileSearchCalled = false;
        TenantContext.set("tenant_b");
        newResolver().resolveObjectKeys("conn-1", "q");
        assertEquals("vs_b", aiClient.lastVectorStoreId, "tenant B's own call must never see tenant A's vector store id");
    }

    // ── 11. Downstream Stage 2 / object-key resolution is identical regardless of Stage 1 source ─

    @Test
    void stage2ResolutionIsIdenticalWhicheverStage1PathSelectedTheSameConcepts() {
        setUpCommon();
        // Legacy path
        tenantSettings.flagValue = false;
        aiClient.scriptedChatResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]}}";
        Optional<List<String>> legacyResult = newResolver().resolveObjectKeys("conn-1", "q");

        // File Search path, same selected concept
        aiClient = new SpyAiClient();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]}}";
        Optional<List<String>> fileSearchResult = newResolver().resolveObjectKeys("conn-1", "q");

        assertEquals(legacyResult, fileSearchResult,
                "AgentBrain's downstream consumption of the Optional<List<String>> result must be unaffected "
                        + "by which Stage 1 implementation produced it");
    }

    // ── 13. investigation_hints is never referenced by the new prompt ──────────────────────────

    @Test
    void fileSearchSystemPromptNeverMentionsInvestigationHints() throws Exception {
        java.lang.reflect.Field f = ConceptScopedMetadataResolver.class
                .getDeclaredField("PERSISTENT_KNOWLEDGE_SYSTEM_PROMPT");
        f.setAccessible(true);
        String prompt = (String) f.get(null);
        assertFalse(prompt.toLowerCase().contains("investigation_hint"));
        assertFalse(prompt.toLowerCase().contains("status='open'"));
    }

    // ── Conversation-aware Stage 1 (previous_response_id chaining) ──────────────────────────────

    @Test
    void firstCallInAConversationHasNoPreviousResponseId() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");

        newResolver().resolveObjectKeys("conn-1", "Show me all purchase orders", "conv-1");

        assertNull(aiClient.lastPreviousResponseId, "the very first turn in a conversation must not chain");
    }

    @Test
    void previousResponseIdIsPassedWhenAlreadyStoredForThisConversation() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        tenantSettings.store.put("stage1_response_id:conv-1", "resp_turn1");

        newResolver().resolveObjectKeys("conn-1", "Only the submitted ones", "conv-1");

        assertEquals("resp_turn1", aiClient.lastPreviousResponseId);
    }

    @Test
    void theLatestResponseIdIsPersistedAfterASuccessfulCall() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.scriptedNewResponseId = "resp_new_1";

        newResolver().resolveObjectKeys("conn-1", "Show me all purchase orders", "conv-1");

        assertEquals("resp_new_1", tenantSettings.store.get("stage1_response_id:conv-1"));
    }

    @Test
    void turnTwoUsesTurnOnesStoredResponseId() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        ConceptScopedMetadataResolver resolver = newResolver();

        aiClient.scriptedNewResponseId = "resp_turn1";
        resolver.resolveObjectKeys("conn-1", "Show me all purchase orders", "conv-1");
        assertNull(aiClient.lastPreviousResponseId, "turn 1 must not chain");

        aiClient.scriptedNewResponseId = "resp_turn2";
        resolver.resolveObjectKeys("conn-1", "Only the submitted ones", "conv-1");
        assertEquals("resp_turn1", aiClient.lastPreviousResponseId, "turn 2 must chain to turn 1's response id");
    }

    @Test
    void turnThreeUsesTurnTwosStoredResponseId() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        ConceptScopedMetadataResolver resolver = newResolver();

        aiClient.scriptedNewResponseId = "resp_turn1";
        resolver.resolveObjectKeys("conn-1", "Show me all purchase orders", "conv-1");
        aiClient.scriptedNewResponseId = "resp_turn2";
        resolver.resolveObjectKeys("conn-1", "Only the submitted ones", "conv-1");
        aiClient.scriptedNewResponseId = "resp_turn3";
        resolver.resolveObjectKeys("conn-1", "Show me the supplier names", "conv-1");

        assertEquals("resp_turn2", aiClient.lastPreviousResponseId, "turn 3 must chain to turn 2's response id");
    }

    @Test
    void missingStoredResponseIdStartsAFreshNonChainedCall() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        // no entry in tenantSettings.store for this conversation key

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "q", "conv-never-seen");

        assertNull(aiClient.lastPreviousResponseId);
        assertTrue(result.isPresent(), "a fresh (non-chained) call is a completely normal, successful Stage 1 call");
    }

    @Test
    void invalidOrExpiredPreviousResponseIdTriggersExactlyOneFreshRetry() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        tenantSettings.store.put("stage1_response_id:conv-1", "resp_stale");
        aiClient.chainedOnlyFailure = new RuntimeException("simulated: previous_response_id not found");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]}}";

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "q", "conv-1");

        assertEquals(2, aiClient.fileSearchCallCount, "exactly one chained attempt, then exactly one fresh retry");
        assertTrue(result.isPresent(), "the fresh retry's success must still be returned as a normal Stage 1 result");
        assertEquals(List.of("obj-product"), result.get());
        assertFalse(aiClient.chatWithJsonCalled, "the fresh retry succeeding must never fall through to the legacy path");
    }

    @Test
    void freshRetryAfterAChainedFailureUpdatesTheStoredResponseId() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        tenantSettings.store.put("stage1_response_id:conv-1", "resp_stale");
        aiClient.chainedOnlyFailure = new RuntimeException("simulated: previous_response_id not found");
        aiClient.scriptedNewResponseId = "resp_fresh_retry";

        newResolver().resolveObjectKeys("conn-1", "q", "conv-1");

        assertEquals("resp_fresh_retry", tenantSettings.store.get("stage1_response_id:conv-1"),
                "the stale id must be replaced by the fresh retry's new response id");
    }

    @Test
    void bothChainedAndFreshRetryFailingFallsBackToTheLegacyPath() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        tenantSettings.store.put("stage1_response_id:conv-1", "resp_stale");
        aiClient.fileSearchFailure = new RuntimeException("simulated: OpenAI unavailable");
        aiClient.scriptedChatResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]}}";

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "q", "conv-1");

        assertTrue(aiClient.chatWithJsonCalled, "when even the fresh retry fails, the legacy path must still run");
        assertEquals(List.of("obj-product"), result.orElseThrow());
    }

    @Test
    void tenantAAndTenantBNeverShareAConversationResponseId() {
        setUpCommon();
        FakeTenantSettingsRepository settingsA = new FakeTenantSettingsRepository();
        settingsA.flagValue = true;
        FakeTenantSettingsRepository settingsB = new FakeTenantSettingsRepository();
        settingsB.flagValue = true;
        tenantRepository.seed("tenant_a", "vs_a");
        tenantRepository.seed("tenant_b", "vs_b");

        ConceptScopedMetadataResolver resolverA = new ConceptScopedMetadataResolver(
                packRepository, semanticService, aiClient, new ObjectMapper(), settingsA, tenantRepository);
        TenantContext.set("tenant_a");
        aiClient.scriptedNewResponseId = "resp_tenant_a";
        resolverA.resolveObjectKeys("conn-1", "q", "conv-shared-id");
        TenantContext.clear();

        // Tenant B's own TenantSettingsRepository (its own schema, in real Postgres) never sees
        // tenant A's stored value even though the conversationId string happens to be identical.
        ConceptScopedMetadataResolver resolverB = new ConceptScopedMetadataResolver(
                packRepository, semanticService, aiClient, new ObjectMapper(), settingsB, tenantRepository);
        TenantContext.set("tenant_b");
        aiClient.scriptedNewResponseId = "resp_tenant_b";
        resolverB.resolveObjectKeys("conn-1", "q", "conv-shared-id");

        assertNull(aiClient.lastPreviousResponseId, "tenant B must never chain off tenant A's response id");
        assertFalse(settingsB.store.containsValue("resp_tenant_a"), "tenant B's own settings store must never contain tenant A's response id");
    }

    @Test
    void conversationAAndConversationBNeverShareAResponseIdWithinTheSameTenant() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        ConceptScopedMetadataResolver resolver = newResolver();

        aiClient.scriptedNewResponseId = "resp_conv_a";
        resolver.resolveObjectKeys("conn-1", "q", "conv-a");

        resolver.resolveObjectKeys("conn-1", "q", "conv-b");

        assertNull(aiClient.lastPreviousResponseId, "conversation B must not chain off conversation A's response id");
    }

    @Test
    void theCorrectTenantVectorStoreIsAlwaysAttachedRegardlessOfChaining() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x_specific");
        tenantSettings.store.put("stage1_response_id:conv-1", "resp_prior");

        newResolver().resolveObjectKeys("conn-1", "q", "conv-1");

        assertEquals("vs_tenant_x_specific", aiClient.lastVectorStoreId);
    }

    @Test
    void theOutputContractIsUnchangedByConversationAwareness() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\",\"store\"]}}";

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "q", "conv-1");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
        assertTrue(result.get().containsAll(List.of("obj-product", "obj-store")));
    }

    @Test
    void flagOffPreservesLegacyBehaviorEvenWithAConversationIdSupplied() {
        setUpCommon();
        tenantSettings.flagValue = false;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_should_never_be_used");

        newResolver().resolveObjectKeys("conn-1", "any question", "conv-1");

        assertTrue(aiClient.chatWithJsonCalled, "flag off must still use the legacy path even with a conversationId");
        assertFalse(aiClient.fileSearchCalled, "flag off must never invoke File Search, conversationId or not");
        assertNull(tenantSettings.store.get("stage1_response_id:conv-1"),
                "the legacy path must never write a Stage 1 response id");
    }

    @Test
    void existingNonConversationCallersRemainFullyFunctional() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]}}";

        // The pre-existing 2-arg overload (no conversationId at all) must still work exactly as before.
        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "q");

        assertNull(aiClient.lastPreviousResponseId, "no conversationId ⇒ no chaining is even attempted");
        assertEquals(List.of("obj-product"), result.orElseThrow());
    }

    // ── Decision Router absorption: resolveObjectKeysWithRouting (combined contract) ────────────

    @Test
    void combinedCallResolvesConceptsAndRoutingInOneCall() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]},"
                + "\"routing\":{\"type\":\"QUERY_LIVE_DATA\",\"clarificationQuestion\":\"\"}}";

        ConceptScopedMetadataResolver.CombinedResolution result =
                newResolver().resolveObjectKeysWithRouting("conn-1", "Show me all products", "conv-1", false);

        assertEquals(List.of("obj-product"), result.objectKeys().orElseThrow());
        assertTrue(result.routing().isPresent());
        assertEquals("QUERY_LIVE_DATA", result.routing().get().type());
        assertEquals(1, aiClient.fileSearchCallCount, "exactly one combined call — no additional LLM call");
        assertNotNull(aiClient.lastJsonSchema, "the combined call must use strict JSON-schema structured output");
    }

    @Test
    void runtimeFactIsPassedAsPlainInputTextNeverAsRetrieval() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[]},"
                + "\"routing\":{\"type\":\"ANSWER_FROM_MEMORY\",\"clarificationQuestion\":\"\"}}";

        newResolver().resolveObjectKeysWithRouting("conn-1", "What is our return policy?", "conv-1", true);

        assertTrue(aiClient.lastFileSearchQuestion.contains("Document memory available for this question: true"),
                "memoryAvailable must reach the LLM as plain input text, not as a File Search retrieval");
    }

    @Test
    void hybridRoutingIsParsedCorrectly() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]},"
                + "\"routing\":{\"type\":\"HYBRID_DOC_AND_DATA\",\"clarificationQuestion\":\"\"}}";

        ConceptScopedMetadataResolver.CombinedResolution result =
                newResolver().resolveObjectKeysWithRouting("conn-1", "q", "conv-1", true);

        assertEquals("HYBRID_DOC_AND_DATA", result.routing().orElseThrow().type());
    }

    @Test
    void clarificationRoutingCarriesTheQuestionText() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[]},"
                + "\"routing\":{\"type\":\"ASK_CLARIFICATION\",\"clarificationQuestion\":\"Which product line do you mean?\"}}";

        ConceptScopedMetadataResolver.CombinedResolution result =
                newResolver().resolveObjectKeysWithRouting("conn-1", "q", "conv-1", false);

        assertEquals("ASK_CLARIFICATION", result.routing().orElseThrow().type());
        assertEquals("Which product line do you mean?", result.routing().orElseThrow().clarificationQuestion());
    }

    @Test
    void knowledgeGapRoutingIsParsedCorrectly() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[]},"
                + "\"routing\":{\"type\":\"KNOWLEDGE_GAP\",\"clarificationQuestion\":\"\"}}";

        ConceptScopedMetadataResolver.CombinedResolution result =
                newResolver().resolveObjectKeysWithRouting("conn-1", "q", "conv-1", false);

        assertEquals("KNOWLEDGE_GAP", result.routing().orElseThrow().type());
        assertTrue(result.objectKeys().orElseThrow().isEmpty());
    }

    @Test
    void invalidRoutingTypeIsDiscardedNotGuessedAt() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        // A value outside the five-value contract — must never be invented/coerced into a guess.
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]},"
                + "\"routing\":{\"type\":\"MADE_UP_TYPE\",\"clarificationQuestion\":\"\"}}";

        ConceptScopedMetadataResolver.CombinedResolution result =
                newResolver().resolveObjectKeysWithRouting("conn-1", "q", "conv-1", false);

        assertTrue(result.routing().isEmpty(), "an invalid routing.type must be discarded, never guessed at");
        assertEquals(List.of("obj-product"), result.objectKeys().orElseThrow(),
                "concept resolution must still succeed independently of the routing field's validity");
    }

    @Test
    void flagOffProducesConceptsWithoutRoutingLegacyFallbackRequired() {
        setUpCommon();
        tenantSettings.flagValue = false;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_should_never_be_used");
        aiClient.scriptedChatResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]}}";

        ConceptScopedMetadataResolver.CombinedResolution result =
                newResolver().resolveObjectKeysWithRouting("conn-1", "q", "conv-1", false);

        assertFalse(aiClient.fileSearchCalled, "flag off must never invoke File Search");
        assertTrue(result.routing().isEmpty(),
                "the legacy catalog-in-prompt path has no routing capability — caller must fall back to Decision Router");
        assertEquals(List.of("obj-product"), result.objectKeys().orElseThrow(),
                "concept resolution itself must still work via the legacy path");
    }

    @Test
    void combinedCallFailureFallsBackToLegacyPathWithNoRouting() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.fileSearchFailure = new RuntimeException("simulated OpenAI failure");
        aiClient.scriptedChatResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]}}";

        ConceptScopedMetadataResolver.CombinedResolution result =
                newResolver().resolveObjectKeysWithRouting("conn-1", "q", "conv-1", false);

        assertTrue(aiClient.chatWithJsonCalled, "a combined-call failure must fall back to the legacy path");
        assertTrue(result.routing().isEmpty(), "the legacy fallback never carries a routing decision");
        assertEquals(List.of("obj-product"), result.objectKeys().orElseThrow());
    }

    @Test
    void tenantAAndTenantBNeverShareARoutingCall() {
        setUpCommon();
        tenantSettings.flagValue = true;
        tenantRepository.seed("tenant_a", "vs_a");
        tenantRepository.seed("tenant_b", "vs_b");

        TenantContext.set("tenant_a");
        newResolver().resolveObjectKeysWithRouting("conn-1", "q", "conv-1", false);
        assertEquals("vs_a", aiClient.lastVectorStoreId);

        TenantContext.clear();
        TenantContext.set("tenant_b");
        newResolver().resolveObjectKeysWithRouting("conn-1", "q", "conv-1", false);
        assertEquals("vs_b", aiClient.lastVectorStoreId,
                "tenant B's own combined call must never see tenant A's vector store id");
    }

    @Test
    void combinedCallChainsViaPreviousResponseIdAcrossTurnsJustLikeTheConceptOnlyPath() {
        setUpCommon();
        tenantSettings.flagValue = true;
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]},"
                + "\"routing\":{\"type\":\"QUERY_LIVE_DATA\",\"clarificationQuestion\":\"\"}}";
        ConceptScopedMetadataResolver resolver = newResolver();

        aiClient.scriptedNewResponseId = "resp_turn1";
        resolver.resolveObjectKeysWithRouting("conn-1", "Show me all products", "conv-1", false);
        assertNull(aiClient.lastPreviousResponseId, "turn 1 must not chain");

        aiClient.scriptedNewResponseId = "resp_turn2";
        resolver.resolveObjectKeysWithRouting("conn-1", "Only the active ones", "conv-1", false);
        assertEquals("resp_turn1", aiClient.lastPreviousResponseId, "turn 2 must chain to turn 1's response id");
    }
}
