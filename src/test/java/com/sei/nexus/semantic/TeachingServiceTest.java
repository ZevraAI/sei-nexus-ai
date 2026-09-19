package com.sei.nexus.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.connection.NexusConnection;
import com.sei.nexus.knowledge.ConceptKnowledgeMaterializationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Part B — /TeachZevra Explicit Teaching. Hand-rolled fakes throughout (no Mockito, no database),
 * same convention as this repo's other service-logic tests.
 */
class TeachingServiceTest {

    // ── fakes ────────────────────────────────────────────────────────────────────────────────

    static class FakeAiClient extends AzureOpenAiClient {
        String scriptedJson = "{}";
        List<ChatMessage> lastMessages;
        String lastSystemPrompt;
        FakeAiClient() { super(new ObjectMapper(), null); }
        @Override public String respondWithStrictJson(List<ChatMessage> messages, String systemPrompt,
                                                        String jsonSchemaName, Map<String, Object> jsonSchema) {
            lastMessages = messages;
            lastSystemPrompt = systemPrompt;
            return scriptedJson;
        }
    }

    static class FakeConceptService extends ConceptKnowledgeMaterializationService {
        List<Map<String, String>> catalog = new ArrayList<>();
        FakeConceptService() { super(null, null, null, null, null, null); }
        @Override public List<Map<String, String>> listConceptCatalog() { return catalog; }
        void addConcept(String key, String name) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("conceptKey", key);
            row.put("name", name);
            catalog.add(row);
        }
    }

    static class FakeConnectionRepository extends ConnectionRepository {
        List<NexusConnection> connections = new ArrayList<>();
        FakeConnectionRepository() { super(null); }
        @Override public List<NexusConnection> findAll() { return connections; }
        void addConnection(String key, String name) {
            connections.add(new NexusConnection(key, name, "POSTGRES", "", null, null, null, null,
                    null, null, true, null, null, null, "ACTIVE", Instant.now(), Instant.now()));
        }
    }

    /** Stands in for a single tenant's nexus_learned_mapping table — a fresh instance per
     *  simulated tenant proves the isolation boundary (see tenantATeachingNeverVisibleToTenantB). */
    static class FakeMappingRepository extends LearnedMappingRepository {
        final List<LearnedMapping> rows = new ArrayList<>();
        String lastConnectionKeyArg;
        boolean lastConnectionKeyArgCaptured = false;
        FakeMappingRepository() { super(null); }
        @Override public LearnedMapping insertExplicitTeaching(LearnedMapping m, String connectionKey) {
            lastConnectionKeyArg = connectionKey;
            lastConnectionKeyArgCaptured = true;
            LearnedMapping saved = new LearnedMapping(
                    "lmap-" + rows.size(), m.domainKey(), m.businessTerm(), m.sqlPattern(),
                    m.sourceRunKey(), m.source(), m.confidence(), m.useCount(), m.lastUsedAt(),
                    m.promoted(), Instant.now(), Instant.now(), m.conceptKey());
            rows.add(saved);
            return saved;
        }
        @Override public Optional<LearnedMapping> findByKey(String mappingKey) {
            return rows.stream().filter(r -> r.mappingKey().equals(mappingKey)).findFirst();
        }
        @Override public List<LearnedMapping> findPromotedByConceptKeys(List<String> conceptKeys) {
            return rows.stream()
                    .filter(LearnedMapping::promoted)
                    .filter(r -> conceptKeys.contains(r.conceptKey()))
                    .toList();
        }
        void markPromotedByKey(String mappingKey) {
            for (int i = 0; i < rows.size(); i++) {
                LearnedMapping r = rows.get(i);
                if (r.mappingKey().equals(mappingKey)) {
                    rows.set(i, new LearnedMapping(r.mappingKey(), r.domainKey(), r.businessTerm(),
                            r.sqlPattern(), r.sourceRunKey(), r.source(), r.confidence(), r.useCount(),
                            r.lastUsedAt(), true, r.createdAt(), r.updatedAt(), r.conceptKey()));
                }
            }
        }
    }

    private FakeAiClient aiClient;
    private FakeConceptService conceptService;
    private FakeConnectionRepository connectionRepository;
    private FakeMappingRepository mappingRepository;
    private TeachingService service;

    private void setUp() {
        aiClient = new FakeAiClient();
        conceptService = new FakeConceptService();
        connectionRepository = new FakeConnectionRepository();
        mappingRepository = new FakeMappingRepository();
        service = new TeachingService(aiClient, new ObjectMapper(), conceptService,
                connectionRepository, mappingRepository);
        conceptService.addConcept("purchase-order-status", "Purchase Order Status");
    }

    private TeachingProposalRequest validRequest() {
        return new TeachingProposalRequest("purchase-order-status", "open orders",
                "Orders that have not yet been fully received", TeachingScope.TENANT,
                null, null, null, null);
    }

    private String proposalJson(String conceptKey, String scope) {
        return """
                {"businessTerm":"open orders","definition":"Orders not yet fully received",
                 "sqlPattern":"status IN ('submitted','acknowledged')","conceptKey":"%s","scope":"%s"}
                """.formatted(conceptKey, scope);
    }

    // ── Test 6: form contract requires concept/business term/definition/scope ─────────────────

    @Test
    void formContractRejectsMissingRequiredFields() {
        setUp();
        assertThrows(NexusException.class, () -> service.validate(
                new TeachingProposalRequest(null, "term", "meaning", TeachingScope.TENANT, null, null, null, null)));
        assertThrows(NexusException.class, () -> service.validate(
                new TeachingProposalRequest("purchase-order-status", "", "meaning", TeachingScope.TENANT, null, null, null, null)));
        assertThrows(NexusException.class, () -> service.validate(
                new TeachingProposalRequest("purchase-order-status", "term", " ", TeachingScope.TENANT, null, null, null, null)));
        assertThrows(NexusException.class, () -> service.validate(
                new TeachingProposalRequest("purchase-order-status", "term", "meaning", null, null, null, null, null)));
        assertThrows(NexusException.class, () -> service.validate(
                new TeachingProposalRequest("unknown-concept", "term", "meaning", TeachingScope.TENANT, null, null, null, null)),
                "an unknown concept key must be rejected");
        assertThrows(NexusException.class, () -> service.validate(
                new TeachingProposalRequest("purchase-order-status", "term", "meaning", TeachingScope.CONNECTION, null, null, null, null)),
                "connectionKey is required when scope is CONNECTION");
    }

    // ── Test 7: Teaching LLM receives structured input, returns a valid proposal ──────────────

    @Test
    void teachingProposalCallsTheDedicatedTeachingContractAndReturnsAValidProposal() {
        setUp();
        aiClient.scriptedJson = proposalJson("purchase-order-status", "TENANT");

        TeachingProposal proposal = service.buildProposal(validRequest());

        assertEquals("open orders", proposal.businessTerm());
        assertEquals("Orders not yet fully received", proposal.definition());
        assertEquals("status IN ('submitted','acknowledged')", proposal.sqlPattern());
        assertEquals("purchase-order-status", proposal.conceptKey());
        assertEquals(TeachingScope.TENANT, proposal.scope());
        assertNotNull(aiClient.lastMessages, "the Teaching LLM must actually be called");
        assertTrue(aiClient.lastMessages.get(0).content().contains("purchase-order-status"),
                "the submitted concept key must be part of the structured input sent to the LLM");
    }

    // ── Test 8: LLM-returned conceptKey mismatch is REJECTED, never silently substituted ──────

    @Test
    void mismatchedConceptKeyFromLlmIsRejected() {
        setUp();
        conceptService.addConcept("some-other-concept", "Some Other Concept");
        aiClient.scriptedJson = proposalJson("some-other-concept", "TENANT");

        NexusException ex = assertThrows(NexusException.class, () -> service.buildProposal(validRequest()));
        assertTrue(ex.getMessage().toLowerCase().contains("concept"));
        assertEquals(0, mappingRepository.rows.size(), "a rejected proposal must never be persisted");
    }

    // ── Test 9: user cancellation → no persistence occurs ──────────────────────────────────────

    @Test
    void userCancellationNeverPersists() {
        setUp();
        aiClient.scriptedJson = proposalJson("purchase-order-status", "TENANT");

        // Proposal is built (the LLM call happened, as it would before showing the Preview
        // screen) but the user clicks Cancel — TeachingService#confirmTeaching is simply never
        // called. Nothing should be persisted.
        service.buildProposal(validRequest());

        assertEquals(0, mappingRepository.rows.size(), "cancelling the preview must never persist anything");
        assertFalse(mappingRepository.lastConnectionKeyArgCaptured);
    }

    // ── Test 10: user confirmation → persisted with expected concept_key/content ──────────────

    @Test
    void userConfirmationPersistsTheExpectedMapping() {
        setUp();
        TeachingProposal proposal = new TeachingProposal("open orders", "Orders not yet fully received",
                "status IN ('submitted','acknowledged')", "purchase-order-status", TeachingScope.TENANT, null);

        LearnedMapping saved = service.confirmTeaching(proposal);

        assertEquals(1, mappingRepository.rows.size());
        assertEquals("open orders", saved.businessTerm());
        assertEquals("purchase-order-status", saved.conceptKey());
        assertEquals("EXPLICIT_TEACHING", saved.source());
        assertEquals(TeachingService.EXPLICIT_TEACHING_INITIAL_CONFIDENCE, saved.confidence());
        assertFalse(saved.promoted(), "explicit teaching still goes through normal promotion review, never immediate");
    }

    // ── Test 11: connection scope — associated only with the selected authorized connection ───

    @Test
    void connectionScopedTeachingIsAssociatedOnlyWithTheSelectedAuthorizedConnection() {
        setUp();
        connectionRepository.addConnection("conn-sap", "SAP ECC");
        connectionRepository.addConnection("conn-crm", "Salesforce CRM");

        TeachingProposal proposal = new TeachingProposal("open orders", "def", null,
                "purchase-order-status", TeachingScope.CONNECTION, "conn-sap");

        service.confirmTeaching(proposal);

        assertEquals("conn-sap", mappingRepository.lastConnectionKeyArg,
                "must be scoped to exactly the selected authorized connection");

        // An unauthorized connection must be rejected, never silently accepted.
        TeachingProposal badProposal = new TeachingProposal("open orders", "def", null,
                "purchase-order-status", TeachingScope.CONNECTION, "conn-not-authorized");
        assertThrows(NexusException.class, () -> service.confirmTeaching(badProposal));
    }

    // ── Test 12: tenant isolation — Tenant A's teaching not visible for Tenant B ───────────────

    @Test
    void tenantATeachingIsNeverVisibleThroughTenantBsRepository() {
        // Each tenant is bound to its OWN repository instance in production (via TenantContext-
        // routed JdbcTemplate) — there is no tenant-id column/filter in Java code for this table.
        // This test proves TeachingService performs no cross-repository/cross-tenant reads itself:
        // a mapping written through "tenant A"'s repository is structurally invisible to a
        // TeachingService instance wired to "tenant B"'s repository.
        FakeMappingRepository tenantARepo = new FakeMappingRepository();
        FakeMappingRepository tenantBRepo = new FakeMappingRepository();
        FakeConceptService concepts = new FakeConceptService();
        concepts.addConcept("purchase-order-status", "Purchase Order Status");

        TeachingService tenantAService = new TeachingService(new FakeAiClient(), new ObjectMapper(),
                concepts, new FakeConnectionRepository(), tenantARepo);
        TeachingService tenantBService = new TeachingService(new FakeAiClient(), new ObjectMapper(),
                concepts, new FakeConnectionRepository(), tenantBRepo);

        TeachingProposal proposal = new TeachingProposal("open orders", "def", null,
                "purchase-order-status", TeachingScope.TENANT, null);
        LearnedMapping savedInA = tenantAService.confirmTeaching(proposal);

        assertEquals(1, tenantARepo.rows.size());
        assertEquals(0, tenantBRepo.rows.size(), "tenant B's repository must never see tenant A's write");
        assertTrue(tenantBService.confirmTeaching(proposal) != null
                && tenantARepo.rows.size() == 1, "confirming against tenant B never touches tenant A's rows");
        assertTrue(tenantARepo.findByKey(savedInA.mappingKey()).isPresent());
    }

    // ── Test 13: promoted-learning consumption path unaffected by Part B ───────────────────────

    @Test
    void teachingSourcedMappingIsRetrievableThroughUnchangedPromotedConsumptionPathAfterPromotion() {
        setUp();
        TeachingProposal proposal = new TeachingProposal("open orders", "def", null,
                "purchase-order-status", TeachingScope.TENANT, null);
        LearnedMapping saved = service.confirmTeaching(proposal);

        // Not yet promoted — consumption path returns nothing for this concept yet.
        assertTrue(mappingRepository.findPromotedByConceptKeys(List.of("purchase-order-status")).isEmpty());

        mappingRepository.markPromotedByKey(saved.mappingKey());

        List<LearnedMapping> promoted = mappingRepository.findPromotedByConceptKeys(List.of("purchase-order-status"));
        assertEquals(1, promoted.size());
        assertEquals(saved.mappingKey(), promoted.get(0).mappingKey());
        assertEquals("EXPLICIT_TEACHING", promoted.get(0).source());
    }
}
