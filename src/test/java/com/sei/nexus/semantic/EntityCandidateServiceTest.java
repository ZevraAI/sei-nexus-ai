package com.sei.nexus.semantic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-22 — tier-1 candidate retrieval and the tier-2 prompt/contract rendering.
 * Token normalization mirrors the production matching family
 * (RelationshipDiscoveryService slug fallback, PackEntityMapper normalization).
 */
class EntityCandidateServiceTest {

    static class FakeRepository extends SemanticRepository {
        List<EntityCandidate> candidateRows = List.of();
        List<OperationalVocabulary> terms   = List.of();
        List<String> lastTokens;
        int lastLimit;

        FakeRepository() { super(null); }

        @Override
        public List<EntityCandidate> findCandidateEntities(String domainKey, List<String> tokens, int limit) {
            lastTokens = tokens;
            lastLimit  = limit;
            return candidateRows;
        }

        @Override
        public List<OperationalVocabulary> findTermsByEntity(String entityKey) {
            return terms;
        }
    }

    private FakeRepository repository;
    private EntityCandidateService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        service    = new EntityCandidateService(repository);
    }

    private static OperationalVocabulary term(String term) {
        return new OperationalVocabulary("tk-" + term, "PLATFORM", "supplier", term,
                "def", "", "", "ACTIVE", Instant.now(), Instant.now());
    }

    // ── token normalization ──────────────────────────────────────────────────

    @Test
    void tokensCoverSingularSpacedCompressedAndHyphenatedForms() {
        List<String> tokens = EntityCandidateService.matchTokens("stores");
        assertTrue(tokens.containsAll(List.of("stores", "store")));

        tokens = EntityCandidateService.matchTokens("lgs_purchase_orders");
        assertTrue(tokens.containsAll(List.of(
                        "purchase orders", "purchase order",
                        "purchaseorders", "purchaseorder",
                        "purchase-orders", "purchase-order")),
                "prefix stripped + all match forms generated, got: " + tokens);

        tokens = EntityCandidateService.matchTokens("store_targets");
        assertTrue(tokens.contains("store target"), "entity-name form (spaced, singular)");
        assertTrue(tokens.contains("storetarget"), "entity-key form (compressed, singular)");
    }

    @Test
    void blankInputsYieldNoCandidatesAndNoQuery() {
        assertEquals(List.of(), service.retrieve(null, "stores"));
        assertEquals(List.of(), service.retrieve("PLATFORM", " "));
        assertNull(repository.lastTokens, "no repository query for blank input");
    }

    // ── retrieval assembly ───────────────────────────────────────────────────

    @Test
    void retrievalIsBoundedAndAssemblesMinimalCandidateContext() {
        String longDesc = "x".repeat(200);
        repository.candidateRows = List.of(new SemanticRepository.EntityCandidate(
                "supplier", "Supplier", longDesc, "obj-lgs-supplier", "lgs_supplier"));
        repository.terms = new ArrayList<>(List.of(
                term("vendor"), term("seller"), term("provider"), term("source")));

        List<EntityCandidateService.Candidate> out = service.retrieve("PLATFORM", "vendors");

        assertEquals(5, repository.lastLimit, "retrieval hard-capped at MAX_CANDIDATES");
        assertEquals(1, out.size());
        EntityCandidateService.Candidate c = out.get(0);
        assertEquals("supplier", c.entityKey());
        assertEquals("lgs_supplier", c.boundTable());
        assertEquals(3, c.terms().size(), "terms capped per candidate");
        assertEquals(121, c.description().length(), "description truncated to 120 + ellipsis");
    }

    // ── prompt rendering ─────────────────────────────────────────────────────

    @Test
    void promptBlockAndContractAreEmptyWithoutCandidates() {
        assertEquals("", service.renderPromptBlock(List.of()), "zero token cost when no candidates");
        assertEquals("", service.resolutionContract(List.of()));
    }

    @Test
    void promptBlockRendersOneCompactLinePerCandidate() {
        List<EntityCandidateService.Candidate> cands = List.of(
                new EntityCandidateService.Candidate("supplier", "Supplier", "lgs_supplier",
                        List.of("vendor"), "External companies"),
                new EntityCandidateService.Candidate("carrier", "Carrier", null,
                        List.of(), null));

        String block = service.renderPromptBlock(cands);
        assertTrue(block.contains(
                "- entity_key=supplier | name=Supplier | table=lgs_supplier | terms: vendor | desc: External companies"));
        assertTrue(block.contains("- entity_key=carrier | name=Carrier\n"),
                "absent fields omitted, not rendered empty");

        String contract = service.resolutionContract(cands);
        assertTrue(contract.contains("\"entityResolution\""));
        assertTrue(contract.contains("never invent one"));
    }
}
