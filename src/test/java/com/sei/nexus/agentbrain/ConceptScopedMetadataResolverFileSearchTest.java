package com.sei.nexus.agentbrain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.common.NexusException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Persistent Knowledge / native OpenAI File Search Stage 1 — {@link ConceptScopedMetadataResolver}
 * is now the SOLE production Stage 1 semantic-resolution implementation: there is no runtime
 * choice between a legacy and a new path, no feature flag, and no fallback from Persistent
 * Knowledge/File Search to any other concept-selection mechanism. Required-infrastructure failure
 * (missing Vector Store, File Search/OpenAI failure) surfaces as an explicit {@link NexusException}
 * instead.
 *
 * <p>Hand-rolled fakes throughout, this project's convention — no Mockito, no Spring context, no
 * database.
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
        final List<List<String>> stage2ConceptSelectionsQueried = new java.util.ArrayList<>();
        FakeSemanticService() { super(null, null, null); }
        @Override public List<String> findDistinctConceptKeysForConnection(String connectionKey) {
            return usedConceptKeysByConnection.getOrDefault(connectionKey, List.of());
        }
        @Override public List<BusinessEntity> findEntitiesByConnectionAndConcepts(String connectionKey, List<String> conceptKeys) {
            stage2ConceptSelectionsQueried.add(conceptKeys);
            List<BusinessEntity> all = entitiesByConnection.getOrDefault(connectionKey, List.of());
            return all.stream().filter(e -> conceptKeys.contains(e.conceptKey())).toList();
        }
    }

    /** Now used only for {@code previous_response_id} conversation-chaining bookkeeping — the
     *  Stage 1 path feature flag it used to gate no longer exists. */
    static class FakeTenantSettingsRepository extends TenantSettingsRepository {
        final Map<String, String> store = new LinkedHashMap<>();
        FakeTenantSettingsRepository() { super(null); }
        @Override public Optional<String> get(String key) { return Optional.ofNullable(store.get(key)); }
        @Override public void set(String key, String value) { store.put(key, value); }
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

    /** Scripts the File Search call; also records whether the legacy {@code chatWithJson} call
     *  shape was ever used — asserted to NEVER happen anywhere in this file. */
    static class SpyAiClient extends AzureOpenAiClient {
        boolean chatWithJsonCalled = false;
        boolean fileSearchCalled = false;
        int fileSearchCallCount = 0;
        String lastVectorStoreId;
        String lastFileSearchQuestion;
        String lastPreviousResponseId;
        String scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[]}}";
        String scriptedNewResponseId = "resp_default";
        RuntimeException fileSearchFailure; // always throws, chained or not
        RuntimeException chainedOnlyFailure; // throws only when a previousResponseId is supplied

        SpyAiClient() { super(new ObjectMapper(), null); }

        @Override public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
            chatWithJsonCalled = true;
            return "{}";
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

        // Phase 1 explicit prompt caching: the combined concept+routing variant now calls
        // chatWithFileSearchForConceptAndRouting (prompt_cache_key="zevra:stage1-concept-and-routing:v1")
        // instead of the 5-arg chatWithFileSearch above — delegate to the same fake logic.
        @Override public FileSearchResult chatWithFileSearchForConceptAndRouting(String vectorStoreId,
                String instructions, String question, String previousResponseId, Map<String, Object> jsonSchema) {
            return chatWithFileSearch(vectorStoreId, instructions, question, previousResponseId, jsonSchema);
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

    /** Standard "happy path" tenant state shared by most tests below. */
    private void seedReadyVectorStore() {
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x");
    }

    // ── 1. Persistent Knowledge / File Search is the sole Stage 1 path ──────────────────────────

    @Test
    void resolveObjectKeysAlwaysUsesFileSearchAndNeverTheLegacyCatalogCall() {
        setUpCommon();
        seedReadyVectorStore();

        newResolver().resolveObjectKeys("conn-1", "any question");

        assertTrue(aiClient.fileSearchCalled, "Stage 1 must always use File Search");
        assertFalse(aiClient.chatWithJsonCalled, "the legacy catalog-in-prompt call must never fire");
    }

    // ── 2. File Search receives the current tenant's Vector Store ID ────────────────────────────

    @Test
    void fileSearchReceivesTheCurrentTenantsOwnVectorStoreId() {
        setUpCommon();
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x_specific");

        newResolver().resolveObjectKeys("conn-1", "q");

        assertEquals("vs_tenant_x_specific", aiClient.lastVectorStoreId);
    }

    // ── 3. The user question reaches the File Search-enabled LLM verbatim ───────────────────────

    @Test
    void theExactUserQuestionReachesTheFileSearchCall() {
        setUpCommon();
        seedReadyVectorStore();

        newResolver().resolveObjectKeys("conn-1", "Show me all open orders.");

        assertEquals("Show me all open orders.", aiClient.lastFileSearchQuestion);
    }

    // ── 4-7. Explicit failure — no fallback to any other semantic mechanism ─────────────────────

    @Test
    void missingVectorStoreFailsExplicitlyWithoutAttemptingFileSearch() {
        setUpCommon();
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", null); // provisioned tenant row, but no vector store yet

        NexusException ex = assertThrows(NexusException.class, () -> newResolver().resolveObjectKeys("conn-1", "q"));

        assertFalse(aiClient.fileSearchCalled, "no vector store ⇒ must not even attempt File Search");
        assertFalse(aiClient.chatWithJsonCalled, "must never fall back to the legacy catalog-in-prompt call");
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
    }

    @Test
    void unknownTenantSchemaFailsExplicitlyWithoutAttemptingFileSearch() {
        setUpCommon();
        TenantContext.set("tenant_never_seeded");

        assertThrows(NexusException.class, () -> newResolver().resolveObjectKeys("conn-1", "q"));

        assertFalse(aiClient.fileSearchCalled);
        assertFalse(aiClient.chatWithJsonCalled);
    }

    @Test
    void noTenantContextAtAllFailsExplicitlyWithoutAttemptingFileSearch() {
        setUpCommon();
        // TenantContext deliberately left unset — resolves to "public"

        assertThrows(NexusException.class, () -> newResolver().resolveObjectKeys("conn-1", "q"));

        assertFalse(aiClient.fileSearchCalled, "no tenant context (public schema) must never resolve a vector store");
        assertFalse(aiClient.chatWithJsonCalled);
    }

    @Test
    void fileSearchFailureOnTheFirstCallInAConversationFailsExplicitly() {
        setUpCommon();
        seedReadyVectorStore();
        aiClient.fileSearchFailure = new RuntimeException("simulated OpenAI failure");

        NexusException ex = assertThrows(NexusException.class, () -> newResolver().resolveObjectKeys("conn-1", "q"));

        assertTrue(aiClient.fileSearchCalled, "File Search must still have been attempted");
        assertFalse(aiClient.chatWithJsonCalled, "a File Search failure must never fall back to the legacy call");
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertTrue(ex.getMessage().contains("conn-1"), "the failure message should identify the affected connection");
    }

    @Test
    void bothChainedAndFreshRetryFailingFailsExplicitlyWithoutAnyFallback() {
        setUpCommon();
        seedReadyVectorStore();
        tenantSettings.store.put("stage1_response_id:conv-1", "resp_stale");
        aiClient.fileSearchFailure = new RuntimeException("simulated: OpenAI unavailable");

        assertThrows(NexusException.class, () -> newResolver().resolveObjectKeys("conn-1", "q", "conv-1"));

        assertEquals(2, aiClient.fileSearchCallCount, "exactly one chained attempt, then exactly one fresh retry");
        assertFalse(aiClient.chatWithJsonCalled, "when even the fresh retry fails, there must still be no legacy fallback");
    }

    @Test
    void combinedCallFailureFailsExplicitlyWithoutFallback() {
        setUpCommon();
        seedReadyVectorStore();
        aiClient.fileSearchFailure = new RuntimeException("simulated OpenAI failure");

        assertThrows(NexusException.class,
                () -> newResolver().resolveObjectKeysWithRouting("conn-1", "q", "conv-1", false));

        assertFalse(aiClient.chatWithJsonCalled, "a combined-call failure must never fall back to the legacy path");
    }

    // ── 8. No relevant knowledge preserves existing Optional.of(List.of()) semantics ────────────

    @Test
    void noRelevantKnowledgeReturnsOptionalOfEmptyListNotOptionalEmpty() {
        setUpCommon();
        seedReadyVectorStore();
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[]}}";

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "unrelated question");

        assertTrue(result.isPresent(), "Stage 1 IS applicable (File Search ran) — it just found nothing relevant");
        assertTrue(result.get().isEmpty());
    }

    // ── 9. Multiple retrieved concepts are all validated and resolved ───────────────────────────

    @Test
    void multipleRetrievedConceptsAreAllValidatedAndResolvedToObjectKeys() {
        setUpCommon();
        seedReadyVectorStore();
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\",\"store\"]}}";

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "q");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
        assertTrue(result.get().containsAll(List.of("obj-product", "obj-store")));
    }

    @Test
    void invalidConceptKeyFromFileSearchIsDiscardedNeverPassedToStage2() {
        setUpCommon();
        seedReadyVectorStore();
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\",\"made-up-concept\"]}}";

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "q");

        assertEquals(List.of("obj-product"), result.orElseThrow(),
                "an invented concept_key not in this connection's actual used-concept set must be discarded");
    }

    // ── 10. Tenant isolation — vector store id is resolved strictly per-tenant ──────────────────

    @Test
    void tenantAOnlyEverReceivesTenantAsOwnVectorStoreId() {
        setUpCommon();
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

    // ── 11. investigation_hints is never referenced by the non-combined prompt ─────────────────

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
        seedReadyVectorStore();

        newResolver().resolveObjectKeys("conn-1", "Show me all purchase orders", "conv-1");

        assertNull(aiClient.lastPreviousResponseId, "the very first turn in a conversation must not chain");
    }

    @Test
    void previousResponseIdIsPassedWhenAlreadyStoredForThisConversation() {
        setUpCommon();
        seedReadyVectorStore();
        tenantSettings.store.put("stage1_response_id:conv-1", "resp_turn1");

        newResolver().resolveObjectKeys("conn-1", "Only the submitted ones", "conv-1");

        assertEquals("resp_turn1", aiClient.lastPreviousResponseId);
    }

    @Test
    void theLatestResponseIdIsPersistedAfterASuccessfulCall() {
        setUpCommon();
        seedReadyVectorStore();
        aiClient.scriptedNewResponseId = "resp_new_1";

        newResolver().resolveObjectKeys("conn-1", "Show me all purchase orders", "conv-1");

        assertEquals("resp_new_1", tenantSettings.store.get("stage1_response_id:conv-1"));
    }

    @Test
    void turnTwoUsesTurnOnesStoredResponseId() {
        setUpCommon();
        seedReadyVectorStore();
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
        seedReadyVectorStore();
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
        seedReadyVectorStore();
        // no entry in tenantSettings.store for this conversation key

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "q", "conv-never-seen");

        assertNull(aiClient.lastPreviousResponseId);
        assertTrue(result.isPresent(), "a fresh (non-chained) call is a completely normal, successful Stage 1 call");
    }

    @Test
    void invalidOrExpiredPreviousResponseIdTriggersExactlyOneFreshRetry() {
        setUpCommon();
        seedReadyVectorStore();
        tenantSettings.store.put("stage1_response_id:conv-1", "resp_stale");
        aiClient.chainedOnlyFailure = new RuntimeException("simulated: previous_response_id not found");
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]}}";

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "q", "conv-1");

        assertEquals(2, aiClient.fileSearchCallCount, "exactly one chained attempt, then exactly one fresh retry");
        assertTrue(result.isPresent(), "the fresh retry's success must still be returned as a normal Stage 1 result");
        assertEquals(List.of("obj-product"), result.get());
        assertFalse(aiClient.chatWithJsonCalled, "the fresh retry succeeding must never touch the legacy call shape");
    }

    @Test
    void freshRetryAfterAChainedFailureUpdatesTheStoredResponseId() {
        setUpCommon();
        seedReadyVectorStore();
        tenantSettings.store.put("stage1_response_id:conv-1", "resp_stale");
        aiClient.chainedOnlyFailure = new RuntimeException("simulated: previous_response_id not found");
        aiClient.scriptedNewResponseId = "resp_fresh_retry";

        newResolver().resolveObjectKeys("conn-1", "q", "conv-1");

        assertEquals("resp_fresh_retry", tenantSettings.store.get("stage1_response_id:conv-1"),
                "the stale id must be replaced by the fresh retry's new response id");
    }

    @Test
    void tenantAAndTenantBNeverShareAConversationResponseId() {
        setUpCommon();
        FakeTenantSettingsRepository settingsA = new FakeTenantSettingsRepository();
        FakeTenantSettingsRepository settingsB = new FakeTenantSettingsRepository();
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
        seedReadyVectorStore();
        ConceptScopedMetadataResolver resolver = newResolver();

        aiClient.scriptedNewResponseId = "resp_conv_a";
        resolver.resolveObjectKeys("conn-1", "q", "conv-a");

        resolver.resolveObjectKeys("conn-1", "q", "conv-b");

        assertNull(aiClient.lastPreviousResponseId, "conversation B must not chain off conversation A's response id");
    }

    @Test
    void theCorrectTenantVectorStoreIsAlwaysAttachedRegardlessOfChaining() {
        setUpCommon();
        TenantContext.set("tenant_x");
        tenantRepository.seed("tenant_x", "vs_tenant_x_specific");
        tenantSettings.store.put("stage1_response_id:conv-1", "resp_prior");

        newResolver().resolveObjectKeys("conn-1", "q", "conv-1");

        assertEquals("vs_tenant_x_specific", aiClient.lastVectorStoreId);
    }

    @Test
    void theOutputContractIsUnchangedByConversationAwareness() {
        setUpCommon();
        seedReadyVectorStore();
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\",\"store\"]}}";

        Optional<List<String>> result = newResolver().resolveObjectKeys("conn-1", "q", "conv-1");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
        assertTrue(result.get().containsAll(List.of("obj-product", "obj-store")));
    }

    @Test
    void existingNonConversationCallersRemainFullyFunctional() {
        setUpCommon();
        seedReadyVectorStore();
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
        seedReadyVectorStore();
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
        seedReadyVectorStore();
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[]},"
                + "\"routing\":{\"type\":\"ANSWER_FROM_MEMORY\",\"clarificationQuestion\":\"\"}}";

        newResolver().resolveObjectKeysWithRouting("conn-1", "What is our return policy?", "conv-1", true);

        assertTrue(aiClient.lastFileSearchQuestion.contains("Document memory available for this question: true"),
                "memoryAvailable must reach the LLM as plain input text, not as a File Search retrieval");
    }

    @Test
    void hybridRoutingIsParsedCorrectly() {
        setUpCommon();
        seedReadyVectorStore();
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]},"
                + "\"routing\":{\"type\":\"HYBRID_DOC_AND_DATA\",\"clarificationQuestion\":\"\"}}";

        ConceptScopedMetadataResolver.CombinedResolution result =
                newResolver().resolveObjectKeysWithRouting("conn-1", "q", "conv-1", true);

        assertEquals("HYBRID_DOC_AND_DATA", result.routing().orElseThrow().type());
    }

    @Test
    void clarificationRoutingCarriesTheQuestionText() {
        setUpCommon();
        seedReadyVectorStore();
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
        seedReadyVectorStore();
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
        seedReadyVectorStore();
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
    void tenantAAndTenantBNeverShareARoutingCall() {
        setUpCommon();
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
        seedReadyVectorStore();
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

    // ── Concept-Level Disjunctive Ambiguity design — SINGLE / MULTI_SPAN / AMBIGUOUS ────────────
    // (ported from the now-deleted legacy-only ConceptScopedMetadataResolverTest, adapted to the
    // combined Persistent Knowledge + routing call, the canonical production Stage 1 path)

    @Test
    void resolutionTypeSingleProceedsToStage2Normally() {
        setUpCommon();
        seedReadyVectorStore();
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"],"
                + "\"resolutionType\":\"SINGLE\",\"conceptClarificationQuestion\":\"\"},"
                + "\"routing\":{\"type\":\"QUERY_LIVE_DATA\",\"clarificationQuestion\":\"\"}}";

        ConceptScopedMetadataResolver.CombinedResolution result =
                newResolver().resolveObjectKeysWithRouting("conn-1", "show me the product catalog", "conv-1", false);

        assertTrue(result.conceptAmbiguityClarification().isEmpty());
        assertEquals(List.of("product"), result.conceptKeys().get());
        assertEquals(List.of("obj-product"), result.objectKeys().get());
        assertEquals(List.of(List.of("product")), semanticService.stage2ConceptSelectionsQueried,
                "Stage 2 must run normally for a single, unambiguous concept");
    }

    @Test
    void resolutionTypeMultiSpanProceedsWithBothConcepts() {
        setUpCommon();
        seedReadyVectorStore();
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\",\"store\"],"
                + "\"resolutionType\":\"MULTI_SPAN\",\"conceptClarificationQuestion\":\"\"},"
                + "\"routing\":{\"type\":\"QUERY_LIVE_DATA\",\"clarificationQuestion\":\"\"}}";

        ConceptScopedMetadataResolver.CombinedResolution result =
                newResolver().resolveObjectKeysWithRouting("conn-1", "compare products across stores", "conv-1", false);

        assertTrue(result.conceptAmbiguityClarification().isEmpty());
        assertEquals(List.of("product", "store"), result.conceptKeys().get());
        assertTrue(result.objectKeys().get().containsAll(List.of("obj-product", "obj-store")));
    }

    @Test
    void resolutionTypeAmbiguousNeverCallsStage2() {
        setUpCommon();
        seedReadyVectorStore();
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\",\"store\"],"
                + "\"resolutionType\":\"AMBIGUOUS\","
                + "\"conceptClarificationQuestion\":\"Do you mean the product catalog or store locations?\"},"
                + "\"routing\":{\"type\":\"QUERY_LIVE_DATA\",\"clarificationQuestion\":\"\"}}";

        ConceptScopedMetadataResolver.CombinedResolution result =
                newResolver().resolveObjectKeysWithRouting("conn-1", "ambiguous question", "conv-1", false);

        assertTrue(result.conceptAmbiguityClarification().isPresent());
        assertEquals("Do you mean the product catalog or store locations?", result.conceptAmbiguityClarification().get());
        assertTrue(result.objectKeys().isPresent(), "present-but-empty, never Optional.empty()");
        assertTrue(result.objectKeys().get().isEmpty(), "no physical object may be resolved for an ambiguous question");
        assertTrue(semanticService.stage2ConceptSelectionsQueried.isEmpty(),
                "Stage 2 must NEVER be invoked when Stage 1 signals ambiguity");
        assertEquals(List.of("product", "store"), result.conceptKeys().get());
    }

    @Test
    void malformedAmbiguousSignalWithNoQuestionIsDiscarded() {
        setUpCommon();
        seedReadyVectorStore();
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\",\"store\"],"
                + "\"resolutionType\":\"AMBIGUOUS\",\"conceptClarificationQuestion\":\"\"},"
                + "\"routing\":{\"type\":\"QUERY_LIVE_DATA\",\"clarificationQuestion\":\"\"}}";

        ConceptScopedMetadataResolver.CombinedResolution result =
                newResolver().resolveObjectKeysWithRouting("conn-1", "q", "conv-1", false);

        assertTrue(result.conceptAmbiguityClarification().isEmpty());
        assertFalse(semanticService.stage2ConceptSelectionsQueried.isEmpty(),
                "a discarded/malformed ambiguity signal falls through to normal Stage 2 resolution");
    }

    @Test
    void resolutionTypeFieldIsBackwardCompatibleWithResponsesLackingIt() {
        setUpCommon();
        seedReadyVectorStore();
        aiClient.scriptedFileSearchResponse = "{\"metadataRequest\":{\"conceptKeys\":[\"product\"]},"
                + "\"routing\":{\"type\":\"QUERY_LIVE_DATA\",\"clarificationQuestion\":\"\"}}";

        ConceptScopedMetadataResolver.CombinedResolution result =
                newResolver().resolveObjectKeysWithRouting("conn-1", "q", "conv-1", false);

        assertTrue(result.conceptAmbiguityClarification().isEmpty());
        assertEquals(List.of("product"), result.conceptKeys().get());
    }
}
