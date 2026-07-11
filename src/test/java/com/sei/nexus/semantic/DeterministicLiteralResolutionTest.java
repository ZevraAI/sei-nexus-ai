package com.sei.nexus.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.enterprise.DataColumn;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.enterprise.ValueDomain;
import com.sei.nexus.semantic.BusinessLanguageResolver.IndexedColumn;
import com.sei.nexus.semantic.ResolvedQuestion.Kind;
import com.sei.nexus.semantic.ResolvedQuestion.LiteralCandidate;
import com.sei.nexus.semantic.ResolvedQuestion.Resolution;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-33 — Deterministic Literal Resolution, resolver side (PRO-32 §2.1/§3):
 * unresolved-term detection, candidate ranking, the exact LITERAL CANDIDATES
 * grammar, precedence with BLR (resolved terms are never "unresolved"), the
 * governed learning capture, and the zero-cost fallback.
 */
class DeterministicLiteralResolutionTest {

    private static final int CAP = 3;

    // ── Unresolved-term detection (PRO-32 §2.1) ──────────────────────────────

    @Test
    void lowercaseTxIsDetectedWhenNothingResolvesIt() {
        // The exact PRO-31 evidence: "show me all tx stores", stores resolved
        List<Resolution> resolutions = List.of(new Resolution(
                "stores", Kind.ENTITY, "Store (table: retail_core.stores)", "company"));

        List<String> terms = BusinessLanguageResolver.detectUnresolvedTerms(
                "show me all tx stores", resolutions, CAP);

        assertEquals(List.of("tx"), terms);
    }

    @Test
    void allCapsCodesAreDetected() {
        assertEquals(List.of("USA"), BusinessLanguageResolver.detectUnresolvedTerms(
                "USA revenue by state", List.of(), CAP));
        assertEquals(List.of("CA"), BusinessLanguageResolver.detectUnresolvedTerms(
                "compare CA against last month", List.of(), CAP));
    }

    @Test
    void proseWordsNumbersStopWordsAndFunctionWordsAreNeverDetected() {
        // "top" = prose, "30" = number (verbatim ground truth), "show/me/the" = stop
        assertTrue(BusinessLanguageResolver.detectUnresolvedTerms(
                "show me the top 30 vendors", List.of(), CAP).isEmpty());
        // "us" = two-letter function word, not a TX-class code
        assertTrue(BusinessLanguageResolver.detectUnresolvedTerms(
                "give us store counts", List.of(), CAP).isEmpty());
    }

    @Test
    void quotedPhrasesAndAlnumCodesAreDetected() {
        List<String> terms = BusinessLanguageResolver.detectUnresolvedTerms(
                "vendors on 'net 30' with N45 flag", List.of(), CAP);
        assertTrue(terms.contains("net 30"));
        assertTrue(terms.contains("N45"));
    }

    @Test
    void detectionIsCapped() {
        List<String> terms = BusinessLanguageResolver.detectUnresolvedTerms(
                "compare TX CA WA NV AZ", List.of(), CAP);
        assertEquals(CAP, terms.size());
    }

    @Test
    void blrResolvedTermsAreNeverUnresolved_companyAndPackPrecedence() {
        // Company vocabulary resolved "TX"; pack vocabulary resolved "DLX" —
        // both are BLR's responsibility and DLR must not re-litigate them.
        List<Resolution> resolutions = List.of(
                new Resolution("TX", Kind.VALUE, "state_province = 'Texas'", "company"),
                new Resolution("DLX", Kind.VALUE, "room_type = 'Deluxe Room'", "pack:hospitality-v1"));

        List<String> terms = BusinessLanguageResolver.detectUnresolvedTerms(
                "TX bookings for DLX rooms", resolutions, CAP);

        assertFalse(terms.contains("TX"));
        assertFalse(terms.contains("DLX"));
    }

    // ── Candidate ranking (PRO-32 C2: authoritative > status > declared filterable) ──

    @Test
    void candidatesRankAuthoritativeThenStatusThenDeclaredFilterable() {
        List<IndexedColumn> columns = List.of(
                new IndexedColumn("obj-s", "stores", "state_province",
                        List.of("California", "Texas"), false, true, DataColumn.ROLE_INFERRED, false),
                new IndexedColumn("obj-s", "stores", "region_code",
                        List.of("N", "S"), false, true, DataColumn.ROLE_DECLARED, false),
                new IndexedColumn("obj-s", "stores", "status",
                        List.of("open", "closed"), true, true, DataColumn.ROLE_INFERRED, true),
                new IndexedColumn("obj-s", "stores", "no_domain_col", List.of(),
                        true, true, DataColumn.ROLE_CONFIRMED, false));

        List<LiteralCandidate> ranked = BusinessLanguageResolver.rankLiteralCandidates(columns);

        assertEquals(3, ranked.size());                       // domain-less column excluded
        assertEquals("status", ranked.get(0).column());       // authoritative first
        assertEquals("region_code", ranked.get(1).column());  // declared filterable
        assertEquals("state_province", ranked.get(2).column());
    }

    // ── Block grammar (PRO-32 §3, exact) ─────────────────────────────────────

    @Test
    void literalCandidatesBlockUsesExactApprovedGrammar() {
        ResolvedQuestion r = new ResolvedQuestion("show me all tx stores",
                List.of(), Set.of(), List.of("tx"),
                List.of(new LiteralCandidate("stores", "status", true,
                                List.of("open", "closed")),
                        new LiteralCandidate("stores", "state_province", false,
                                List.of("California", "Texas"))));

        assertEquals("""
                === LITERAL CANDIDATES ===
                "tx" matched no known term, value, column, or entity. If it denotes a data value,
                it MUST be one of these stored values (choose the exact spelling):
                  stores.status (legal): open | closed
                  stores.state_province (observed): California | Texas
                If none of these is what the user means, ask for clarification. Never invent a literal.
                """, r.renderLiteralCandidatesBlock());
    }

    @Test
    void blockCapsCandidateColumnsAtThree() {
        List<LiteralCandidate> four = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            four.add(new LiteralCandidate("t", "col" + i, false, List.of("v")));
        }
        ResolvedQuestion r = new ResolvedQuestion("q", List.of(), Set.of(),
                List.of("XX"), four);

        String block = r.renderLiteralCandidatesBlock();
        assertTrue(block.contains("t.col2"));
        assertFalse(block.contains("t.col3"));
    }

    // ── Zero-cost fallback (PRO-32 §6 failure table, executable form) ────────

    @Test
    void zeroUnresolvedTermsRendersNothing() {
        ResolvedQuestion r = new ResolvedQuestion("q", List.of(), Set.of(), List.of(),
                List.of(new LiteralCandidate("t", "c", false, List.of("v"))));
        assertEquals("", r.renderLiteralCandidatesBlock());

        ResolvedQuestion noCandidates = new ResolvedQuestion("q", List.of(), Set.of(),
                List.of("XX"), List.of());
        assertEquals("", noCandidates.renderLiteralCandidatesBlock());

        // PRO-31 compat constructor carries no literal data at all
        ResolvedQuestion pro31Shape = new ResolvedQuestion("q", List.of(), Set.of());
        assertTrue(pro31Shape.unresolvedTerms().isEmpty());
        assertTrue(pro31Shape.literalCandidates().isEmpty());
        assertEquals("", pro31Shape.renderLiteralCandidatesBlock());
    }

    // ── End-to-end through resolve() with hand-rolled fakes (the TX scenario) ─

    private static class FakeSemanticRepository extends SemanticRepository {
        final List<BusinessEntity> entities;
        final List<OperationalVocabulary> terms;
        FakeSemanticRepository(List<BusinessEntity> entities, List<OperationalVocabulary> terms) {
            super(null);
            this.entities = entities;
            this.terms = terms;
        }
        @Override public List<BusinessEntity> findEntitiesByDomain(String d) { return entities; }
        @Override public List<OperationalVocabulary> findTermsByDomain(String d) { return terms; }
    }

    private static class FakeEnterpriseMapRepository extends EnterpriseMapRepository {
        final Map<String, DataObject> objects;
        final Map<String, List<DataColumn>> columns;
        final Map<String, ValueDomain> domains;
        FakeEnterpriseMapRepository(Map<String, DataObject> objects,
                Map<String, List<DataColumn>> columns, Map<String, ValueDomain> domains) {
            super(null);
            this.objects = objects;
            this.columns = columns;
            this.domains = domains;
        }
        @Override public Optional<DataObject> findDataObjectByKey(String k) {
            return Optional.ofNullable(objects.get(k));
        }
        @Override public List<DataColumn> findColumnsByObject(String k) {
            return columns.getOrDefault(k, List.of());
        }
        @Override public Optional<ValueDomain> findValueDomainByKey(String k) {
            return Optional.ofNullable(domains.get(k));
        }
    }

    private static BusinessLanguageResolver txScenarioResolver() {
        Instant now = Instant.now();
        BusinessEntity store = new BusinessEntity("ent-store", "retail", "Store", "A retail location",
                "obj-stores", null, null, "ACTIVE", "steward@tenant.com", now, now);
        DataObject stores = new DataObject("obj-stores", "retail", "stores", "conn-1",
                "retail_core", "stores", "Stores", "Physical retail locations",
                "", "", "", "", "", "", "", 500, false, "SCANNED", 1, now, now);
        DataColumn stateCol = new DataColumn("col-state", "obj-stores", "state_province",
                "character varying", true, "", false, false, false, false, true,
                null, "vdom-state", DataColumn.ROLE_INFERRED, now, now);
        DataColumn statusCol = new DataColumn("col-status", "obj-stores", "status",
                "USER-DEFINED", true, "", false, true, false, false, true,
                "store_status", "vdom-status", DataColumn.ROLE_INFERRED, now, now);
        ValueDomain stateDomain = new ValueDomain("vdom-state", "conn-1", "retail_core",
                "stores.state_province", "OBSERVED", false,
                "[\"California\",\"Texas\"]", null);
        ValueDomain statusDomain = new ValueDomain("vdom-status", "conn-1", "retail_core",
                "store_status", "ENUM", true, "[\"open\",\"closed\"]", null);

        return new BusinessLanguageResolver(
                new FakeSemanticRepository(List.of(store), List.of()),
                new FakeEnterpriseMapRepository(
                        Map.of("obj-stores", stores),
                        Map.of("obj-stores", List.of(stateCol, statusCol)),
                        Map.of("vdom-state", stateDomain, "vdom-status", statusDomain)),
                new ObjectMapper());
    }

    @Test
    void motivatingTxScenario_unresolvedTermOfferedThePersistedTexasValue() {
        ResolvedQuestion r = txScenarioResolver().resolve("show me all tx stores", List.of("retail"));

        // BLR resolved "stores"; nothing resolved "tx" — DLR takes over
        assertEquals(List.of("tx"), r.unresolvedTerms());
        assertTrue(r.literalCandidates().stream()
                .anyMatch(c -> c.column().equals("state_province") && c.values().contains("Texas")));

        String block = r.renderLiteralCandidatesBlock();
        assertTrue(block.contains("\"tx\" matched no known term"));
        assertTrue(block.contains("stores.state_province (observed): California | Texas"));
        assertTrue(block.contains("stores.status (legal): open | closed"));
        assertTrue(block.contains("Never invent a literal."));

        // Annotate-never-substitute: the question is untouched
        assertEquals("show me all tx stores", r.original());
    }

    @Test
    void fullyResolvedQuestionHasNoLiteralBlock() {
        // "texas" is a persisted domain value → BLR resolves it → zero-cost path
        ResolvedQuestion r = txScenarioResolver().resolve("show me all texas stores", List.of("retail"));

        assertTrue(r.unresolvedTerms().isEmpty());
        assertEquals("", r.renderLiteralCandidatesBlock());
    }

    // ── Learning capture (PRO-32 D3 — governed lifecycle, never auto-promoted) ─

    private static class RecordingMappingRepository extends LearnedMappingRepository {
        final List<LearnedMapping> upserted = new ArrayList<>();
        RecordingMappingRepository() { super(null); }
        @Override public LearnedMapping upsert(LearnedMapping m) {
            upserted.add(m);
            return m;
        }
    }

    @Test
    void captureLiteralBindingEntersExistingLearnedMappingLifecycle() {
        RecordingMappingRepository repo = new RecordingMappingRepository();
        SemanticLearningService svc = new SemanticLearningService(
                null, null, repo, null, null, null, null);

        svc.captureLiteralBinding("run-1", "retail", "TX", "stores.state_province", "Texas");

        assertEquals(1, repo.upserted.size());
        LearnedMapping m = repo.upserted.get(0);
        assertEquals("tx", m.businessTerm());                          // stable dedup key
        assertEquals("state_province = 'Texas'", m.sqlPattern());      // vocabulary-grade predicate
        assertEquals("LITERAL_RESOLUTION", m.source());
        assertEquals(0.5, m.confidence());                             // standard starting confidence
        assertFalse(m.promoted());                                     // never auto-promoted
    }

    @Test
    void captureIgnoresBlankInputsAndRepositoryFailures() {
        RecordingMappingRepository repo = new RecordingMappingRepository();
        SemanticLearningService svc = new SemanticLearningService(
                null, null, repo, null, null, null, null);

        svc.captureLiteralBinding("run-1", "retail", "", "c", "v");
        svc.captureLiteralBinding("run-1", "retail", "s", null, "v");
        assertTrue(repo.upserted.isEmpty());

        SemanticLearningService failing = new SemanticLearningService(
                null, null, new LearnedMappingRepository(null) {
                    @Override public LearnedMapping upsert(LearnedMapping m) {
                        throw new IllegalStateException("db down");
                    }
                }, null, null, null, null);
        // Must not throw — learning never blocks the response
        assertDoesNotThrow(() ->
                failing.captureLiteralBinding("run-1", "retail", "TX", "c", "v"));
    }
}
