package com.sei.nexus.agentbrain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.knowledge.ConceptEmbeddingRepository.Candidate;
import com.sei.nexus.knowledge.ConceptRetrievalService;
import com.sei.nexus.onboarding.TenantSettingsRepository;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.SemanticService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConceptScopedMetadataResolver} — the single production Stage 1 + Stage 2 path: retrieve
 * candidate concepts from the concept index, show them to the LLM in one call, validate its answer,
 * resolve the objects. Failures are loud (no fallback), the one self-contradiction reconsideration is
 * bounded, and follow-ups carry the previous question and its concepts.
 *
 * <p>These tests prove the plumbing, not what the real model decides — Java never chooses a concept.
 * Hand-rolled fakes, this project's convention: no Mockito, no Spring context, no database.
 */
class ConceptScopedMetadataResolverTest {

    private static final String EMPTY_QUERY_LIVE_DATA = json("[]", "QUERY_LIVE_DATA");

    private static String json(String conceptKeysArray, String routingType) {
        return json(conceptKeysArray, routingType, "SINGLE", "");
    }

    private static String json(String conceptKeysArray, String routingType, String resolutionType,
                               String conceptClarification) {
        return "{\"metadataRequest\":{\"conceptKeys\":" + conceptKeysArray
                + ",\"conceptClarificationQuestion\":\"" + conceptClarification
                + "\",\"resolutionType\":\"" + resolutionType + "\"},"
                + "\"routing\":{\"clarificationQuestion\":\"\",\"type\":\"" + routingType + "\"}}";
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
        final List<List<String>> stage2Queries = new ArrayList<>();
        FakeSemanticService() { super(null, null, null); }
        @Override public List<String> findDistinctConceptKeysForConnection(String connectionKey) {
            return usedConceptKeysByConnection.getOrDefault(connectionKey, List.of());
        }
        @Override public List<BusinessEntity> findEntitiesByConnectionAndConcepts(String connectionKey, List<String> conceptKeys) {
            stage2Queries.add(conceptKeys);
            return entitiesByConnection.getOrDefault(connectionKey, List.of()).stream()
                    .filter(e -> conceptKeys.contains(e.conceptKey())).toList();
        }
    }

    static class FakeTenantSettingsRepository extends TenantSettingsRepository {
        final Map<String, String> store = new LinkedHashMap<>();
        FakeTenantSettingsRepository() { super(null); }
        @Override public Optional<String> get(String key) { return Optional.ofNullable(store.get(key)); }
        @Override public void set(String key, String value) { store.put(key, value); }
    }

    /** Scripted retrieval: records each query and the carried keys; returns a fixed candidate list
     *  (a longer one for {@code retrieveWider}), or throws. */
    static class FakeRetrieval extends ConceptRetrievalService {
        final List<String> queries = new ArrayList<>();
        final List<Collection<String>> carried = new ArrayList<>();
        int widerCalls = 0;
        List<Candidate> narrow = List.of(new Candidate("alpha", "alpha — Alpha. desc meaning", 0.9));
        List<Candidate> wide = List.of(new Candidate("alpha", "alpha — Alpha. desc meaning", 0.9),
                new Candidate("beta", "beta — Beta. desc meaning", 0.4));
        RuntimeException failure;
        FakeRetrieval() { super(null, null, 20); }
        @Override public List<Candidate> retrieve(String connectionKey, String query, Collection<String> carriedKeys) {
            if (failure != null) throw failure;
            queries.add(query);
            carried.add(carriedKeys);
            return narrow;
        }
        @Override public List<Candidate> retrieveWider(String connectionKey, String query, Collection<String> carriedKeys) {
            widerCalls++;
            queries.add(query);
            return wide;
        }
    }

    /** Answers each Stage 1 call from a queue (last entry repeats); "THROW" makes that call fail.
     *  Records every user message, system prompt and schema name. */
    static class SequencedAiClient extends AzureOpenAiClient {
        final Deque<String> responses = new ArrayDeque<>();
        final List<String> userMessages = new ArrayList<>();
        final List<String> systemPrompts = new ArrayList<>();
        final List<String> schemaNames = new ArrayList<>();
        int calls = 0;
        SequencedAiClient() { super(new ObjectMapper(), null); }
        @Override public String respondForStage1(List<ChatMessage> messages, String systemPrompt,
                                                 String jsonSchemaName, Map<String, Object> jsonSchema) {
            calls++;
            userMessages.add(messages.get(0).content());
            systemPrompts.add(systemPrompt);
            schemaNames.add(jsonSchemaName);
            String next = responses.size() > 1 ? responses.poll() : responses.peek();
            if ("THROW".equals(next)) throw new RuntimeException("simulated Stage 1 failure");
            return next;
        }
    }

    private static PackEntity concept(String conceptKey, String name) {
        return new PackEntity(name, List.of(), List.of(), List.of(), "desc", "meaning", conceptKey, "ACTIVE");
    }

    private static BusinessEntity entity(String entityKey, String primaryObjectKey, String conceptKey) {
        Instant now = Instant.now();
        return new BusinessEntity(entityKey, "PLATFORM", entityKey, "desc", primaryObjectKey,
                "", "", "ACTIVE", "steward@x.com", now, now, null, null, "pack-1", conceptKey);
    }

    private FakeIndustryPackRepository packRepository;
    private FakeSemanticService semanticService;
    private FakeTenantSettingsRepository settings;
    private FakeRetrieval retrieval;
    private SequencedAiClient aiClient;
    private ConceptScopedMetadataResolver resolver;

    private void setUp(String... responses) {
        packRepository = new FakeIndustryPackRepository();
        semanticService = new FakeSemanticService();
        settings = new FakeTenantSettingsRepository();
        retrieval = new FakeRetrieval();
        aiClient = new SequencedAiClient();
        for (String r : responses) aiClient.responses.add(r);
        packRepository.assign("conn-1", new IndustryPack("pack-1", "GENERIC", "Generic", "1.0.0", "desc",
                List.of(concept("alpha", "Alpha"), concept("beta", "Beta")),
                List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, null));
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of("alpha", "beta"));
        semanticService.entitiesByConnection.put("conn-1", List.of(
                entity("alpha", "obj-alpha", "alpha"), entity("beta", "obj-beta", "beta")));
        resolver = new ConceptScopedMetadataResolver(packRepository, semanticService, aiClient,
                new ObjectMapper(), settings, retrieval);
    }

    private ConceptScopedMetadataResolver.CombinedResolution resolve() {
        return resolver.resolveObjectKeysWithRouting("conn-1", "some question", "conv-1", false);
    }

    // ── the single path ─────────────────────────────────────────────────────────────────────

    @Test
    void theModelsSelectionAmongTheRetrievedCandidatesIsResolvedToObjects() {
        setUp(json("[\"alpha\"]", "QUERY_LIVE_DATA"));

        var resolution = resolve();

        assertEquals(1, aiClient.calls);
        assertEquals(List.of("alpha"), resolution.conceptKeys().orElseThrow());
        assertEquals(List.of("obj-alpha"), resolution.objectKeys().orElseThrow());
        assertEquals("QUERY_LIVE_DATA", resolution.routing().orElseThrow().type());
        assertEquals("stage1_concept_and_routing", aiClient.schemaNames.get(0));
    }

    @Test
    void thePromptContainsTheCandidateDocsTheQuestionAndTheMemoryFactAndNoPhysicalNames() {
        setUp(json("[\"alpha\"]", "QUERY_LIVE_DATA"));

        resolver.resolveObjectKeysWithRouting("conn-1", "some question", "conv-1", true);

        String prompt = aiClient.userMessages.get(0);
        assertTrue(prompt.contains("- alpha — Alpha. desc meaning"), "each candidate doc is shown verbatim");
        assertTrue(prompt.contains("QUESTION:\nsome question"));
        assertTrue(prompt.contains("Document memory available for this question: true"));
        assertFalse(prompt.contains("PREVIOUS QUESTION"), "a first question has no previous question");
        assertFalse(prompt.contains("obj-"), "no physical object names are ever sent");
    }

    @Test
    void theSystemPromptAndPromptNeverMentionRemovedFileSearchMachinery() {
        setUp(json("[\"alpha\"]", "QUERY_LIVE_DATA"));
        resolve();
        String all = (aiClient.systemPrompts.get(0) + aiClient.userMessages.get(0)).toLowerCase();
        assertFalse(all.contains("file_search"));
        assertFalse(all.contains("file search"));
        assertFalse(all.contains("vector store"));
        assertTrue(aiClient.systemPrompts.get(0).contains("CANDIDATE CONCEPTS"));
    }

    @Test
    void aConceptKeyThatIsNotInTheTenantsUsedConceptsIsDiscarded() {
        setUp(json("[\"alpha\",\"made_up\"]", "QUERY_LIVE_DATA"));

        var resolution = resolve();

        assertEquals(List.of("alpha"), resolution.conceptKeys().orElseThrow());
    }

    @Test
    void ambiguityIsRelayedAndStageTwoIsNeverInvoked() {
        setUp(json("[\"alpha\",\"beta\"]", "ASK_CLARIFICATION", "AMBIGUOUS", "Which one do you mean?"));

        var resolution = resolve();

        assertEquals(Optional.of("Which one do you mean?"), resolution.conceptAmbiguityClarification());
        assertTrue(resolution.objectKeys().orElseThrow().isEmpty());
        assertTrue(semanticService.stage2Queries.isEmpty(), "no object may be approved for an unresolved ambiguity");
    }

    @Test
    void aConnectionWithoutAPackOrConceptCatalogIsNotNarrowedAndMakesNoLlmCall() {
        setUp(json("[\"alpha\"]", "QUERY_LIVE_DATA"));
        assertEquals(Optional.empty(), resolver.resolveObjectKeysWithRouting("other-conn", "q", "c", false).objectKeys());
        semanticService.usedConceptKeysByConnection.put("conn-1", List.of());
        assertEquals(Optional.empty(), resolve().objectKeys());
        assertEquals(0, aiClient.calls);
        assertTrue(retrieval.queries.isEmpty());
    }

    // ── failures are loud ───────────────────────────────────────────────────────────────────

    @Test
    void anUnbuiltConceptIndexSurfacesAsAnExceptionNotASilentUnnarrowedResult() {
        setUp(json("[\"alpha\"]", "QUERY_LIVE_DATA"));
        retrieval.failure = new NexusException(HttpStatus.SERVICE_UNAVAILABLE, "concept index has not been built");

        NexusException e = assertThrows(NexusException.class, this::resolve);

        assertTrue(e.getMessage().contains("not been built"));
        assertEquals(0, aiClient.calls);
    }

    @Test
    void aFailedStage1CallIsAnExceptionNotAFallback() {
        setUp("THROW");

        NexusException e = assertThrows(NexusException.class, this::resolve);

        assertTrue(e.getMessage().contains("Stage 1"));
        assertEquals(1, aiClient.calls, "no retry, no second path");
    }

    // ── follow-ups: previous question + previous concepts ───────────────────────────────────

    @Test
    void aFollowUpRetrievesOverThePreviousQuestionTooCarriesItsConceptsAndShowsThePreviousQuestion() {
        setUp(json("[\"alpha\"]", "QUERY_LIVE_DATA"), json("[\"alpha\"]", "QUERY_LIVE_DATA"));

        resolver.resolveObjectKeysWithRouting("conn-1", "show open orders", "conv-1", false);
        resolver.resolveObjectKeysWithRouting("conn-1", "only the submitted ones", "conv-1", false);

        assertEquals("show open orders\nonly the submitted ones", retrieval.queries.get(1));
        assertEquals(List.of("alpha"), List.copyOf(retrieval.carried.get(1)));
        String prompt = aiClient.userMessages.get(1);
        assertTrue(prompt.contains("PREVIOUS QUESTION IN THIS CONVERSATION:\nshow open orders"));
        assertTrue(prompt.contains("QUESTION:\nonly the submitted ones"));
    }

    @Test
    void aTurnThatSelectsNoConceptKeepsThePreviousConceptsForTheNextFollowUp() {
        setUp(json("[\"alpha\"]", "QUERY_LIVE_DATA"), json("[]", "ASK_CLARIFICATION"), json("[\"alpha\"]", "QUERY_LIVE_DATA"));

        resolver.resolveObjectKeysWithRouting("conn-1", "q1", "conv-1", false);
        resolver.resolveObjectKeysWithRouting("conn-1", "unclear", "conv-1", false);
        resolver.resolveObjectKeysWithRouting("conn-1", "q3", "conv-1", false);

        assertEquals(List.of("alpha"), List.copyOf(retrieval.carried.get(2)));
    }

    @Test
    void withoutAConversationIdNothingIsCarriedOrPersisted() {
        setUp(json("[\"alpha\"]", "QUERY_LIVE_DATA"));

        resolver.resolveObjectKeysWithRouting("conn-1", "q", null, false);

        assertTrue(settings.store.isEmpty());
        assertEquals("q", retrieval.queries.get(0));
        assertTrue(retrieval.carried.get(0).isEmpty());
    }

    // ── reconsideration (one bounded retry on a self-contradictory answer) ──────────────────

    @Test
    void noConceptWithDataRoutingGivesTheModelOneChanceToReconsiderWithAWiderCandidateList() {
        setUp(EMPTY_QUERY_LIVE_DATA, json("[\"beta\"]", "QUERY_LIVE_DATA"));

        var resolution = resolve();

        assertEquals(2, aiClient.calls, "exactly one reconsideration call");
        assertEquals(1, retrieval.widerCalls, "the second call is shown a wider candidate list");
        assertEquals(List.of("obj-beta"), resolution.objectKeys().orElseThrow());
        assertTrue(aiClient.userMessages.get(1).contains("- beta — Beta. desc meaning"),
                "the concept only the wider retrieval returned is now offered");
    }

    @Test
    void theObservationNamesTheContradictionAndListsTheTenantsConceptMenuAsFacts() {
        setUp(EMPTY_QUERY_LIVE_DATA, json("[\"alpha\"]", "QUERY_LIVE_DATA"));

        resolve();

        String second = aiClient.userMessages.get(1);
        assertTrue(second.contains("SYSTEM OBSERVATION"));
        assertTrue(second.contains("QUERY_LIVE_DATA"));
        assertTrue(second.contains("alpha, beta"), "lists the tenant's actual concept_keys");
        assertFalse(second.contains("obj-"));
    }

    @Test
    void noReconsiderationWhenAConceptWasSelectedOrTheModelAsksForClarification() {
        setUp(json("[\"beta\"]", "QUERY_LIVE_DATA"));
        resolve();
        assertEquals(1, aiClient.calls);

        setUp(json("[]", "ASK_CLARIFICATION"));
        assertEquals("ASK_CLARIFICATION", resolve().routing().orElseThrow().type());
        assertEquals(1, aiClient.calls);
    }

    @Test
    void aConceptSelectedButRoutedToKnowledgeGapIsAContradictionHandedBackOnce() {
        setUp(json("[\"alpha\"]", "KNOWLEDGE_GAP"), json("[\"alpha\"]", "QUERY_LIVE_DATA"));

        var resolution = resolve();

        assertEquals(2, aiClient.calls);
        assertEquals("QUERY_LIVE_DATA", resolution.routing().orElseThrow().type());
        String observation = aiClient.userMessages.get(1);
        assertTrue(observation.contains("conflict"));
        assertTrue(observation.contains("alpha"));
    }

    @Test
    void aKnowledgeGapWithNoConceptIsReconsideredOnceAndMayBeStoodBy() {
        setUp(json("[]", "KNOWLEDGE_GAP"), json("[]", "KNOWLEDGE_GAP"));

        var resolution = resolve();

        assertEquals(2, aiClient.calls, "bounded — never a third call");
        assertTrue(aiClient.userMessages.get(1).contains("Candidate retrieval can miss a concept"));
        assertEquals("KNOWLEDGE_GAP", resolution.routing().orElseThrow().type());
        assertTrue(resolution.objectKeys().orElseThrow().isEmpty());
    }

    @Test
    void reconsiderationIsBoundedEvenIfTheModelStaysContradictory() {
        setUp(EMPTY_QUERY_LIVE_DATA, EMPTY_QUERY_LIVE_DATA);

        var resolution = resolve();

        assertEquals(2, aiClient.calls);
        assertTrue(resolution.objectKeys().orElseThrow().isEmpty());
    }

    @Test
    void aFailedOrUnparseableReconsiderationKeepsTheFirstResponse() {
        setUp(EMPTY_QUERY_LIVE_DATA, "THROW");
        var resolution = resolve();
        assertEquals(2, aiClient.calls);
        assertEquals("QUERY_LIVE_DATA", resolution.routing().orElseThrow().type());

        setUp(EMPTY_QUERY_LIVE_DATA, "not json at all");
        assertEquals("QUERY_LIVE_DATA", resolve().routing().orElseThrow().type());
    }

    @Test
    void theStage1PromptAsksForTheLabelOwningConceptsOfABreakdownAndStaysDomainNeutral() {
        setUp(json("[\"alpha\"]", "QUERY_LIVE_DATA"));
        resolve();

        String prompt = aiClient.systemPrompts.get(0);
        assertTrue(prompt.contains("Select concepts to LABEL a breakdown"));
        assertTrue(prompt.contains("also select the"));
        assertTrue(prompt.contains("If the entity could be one of several concepts, select each"));
        String rule = prompt.substring(prompt.indexOf("Select concepts to LABEL a breakdown"),
                prompt.indexOf("Select concepts to LABEL a breakdown") + 600).toLowerCase();
        for (String forbidden : new String[] {"warehouse", "inventory", "retail", "purchase", "customer"}) {
            assertFalse(rule.contains(forbidden), "the rule must stay domain-neutral: " + forbidden);
        }
    }
}
