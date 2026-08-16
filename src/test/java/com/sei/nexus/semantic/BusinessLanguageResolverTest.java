package com.sei.nexus.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.enterprise.DataColumn;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.enterprise.ValueDomain;
import com.sei.nexus.semantic.BusinessLanguageResolver.IndexEntry;
import com.sei.nexus.semantic.BusinessLanguageResolver.IndexedColumn;
import com.sei.nexus.semantic.BusinessLanguageResolver.IndexedEntity;
import com.sei.nexus.semantic.BusinessLanguageResolver.IndexedVocab;
import com.sei.nexus.semantic.ResolvedQuestion.Kind;
import com.sei.nexus.semantic.ResolvedQuestion.Resolution;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-31 — Business Language Resolution runtime, against the PRO-30 contract:
 * derived index, deterministic n-gram matching (exact + singular/plural +
 * separator-insensitive), referents-only targets, company &gt; pack precedence,
 * offered-set ambiguity, ≤ cap resolutions, zero-cost fallback, and the exact
 * §6 prompt-block grammar. All through the static seams plus one end-to-end
 * pass through {@link BusinessLanguageResolver#resolve} with hand-rolled fakes.
 */
class BusinessLanguageResolverTest {

    private static final int CAP = 8;

    // ── fixture builders ─────────────────────────────────────────────────────

    private static IndexedEntity itemEntity() {
        return new IndexedEntity("ent-item", "Item", "obj-items",
                "retail_core.items", "items", "company");
    }

    private static List<IndexEntry> index(List<IndexedEntity> entities,
            List<IndexedVocab> vocab, List<IndexedColumn> columns) {
        Map<String, String> names = new java.util.HashMap<>();
        Map<String, String> tables = new java.util.HashMap<>();
        for (IndexedEntity e : entities) {
            names.put(e.entityKey(), e.entityName());
            tables.put(e.entityKey(), e.qualifiedTable());
        }
        return BusinessLanguageResolver.buildIndexEntries(entities, vocab, columns, names, tables);
    }

    // ── VALUE via curated vocabulary predicate (the TX case) ─────────────────

    @Test
    void curatedPredicateResolvesTwoCharTermAndExpandsCanonicalTokens() {
        List<IndexEntry> idx = index(List.of(), List.of(
                new IndexedVocab("TX", "state_province = 'Texas'", null, "company")), List.of());

        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                "show me all TX stores", idx, CAP);

        assertEquals(1, r.resolutions().size());
        Resolution res = r.resolutions().get(0);
        assertEquals("TX", res.surface());                         // original casing preserved
        assertEquals(Kind.VALUE, res.kind());
        assertEquals("state_province = 'Texas'", res.target());    // sql_equivalent verbatim
        assertEquals("company", res.tier());
        // Retrieval expansion: deterministic stages now see the canonical tokens
        assertTrue(r.expandedTokens().contains("state_province"));
        assertTrue(r.expandedTokens().contains("texas"));
        // Annotate-never-substitute: the original question is untouched
        assertEquals("show me all TX stores", r.original());
    }

    // ── VALUE via curated multi-value predicate (the "open orders" case) ─────

    @Test
    void curatedPredicateSupportsMultiValueInListForTwoWordTerm() {
        List<IndexEntry> idx = index(List.of(), List.of(
                new IndexedVocab("open orders",
                        "purchase_orders.status IN ('draft', 'submitted', 'acknowledged', 'partially_received')",
                        null, "company")), List.of());

        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                "show me all open orders for turtleneck products", idx, CAP);

        assertEquals(1, r.resolutions().size());
        Resolution res = r.resolutions().get(0);
        assertEquals("open orders", res.surface());
        assertEquals(Kind.VALUE, res.kind());
        assertEquals(
                "purchase_orders.status IN ('draft', 'submitted', 'acknowledged', 'partially_received')",
                res.target());
        assertEquals("company", res.tier());
    }

    // ── ENTITY via vocabulary term bound through entity_key (the SLIN case) ──

    @Test
    void vocabularyTermWithEntityBindingResolvesToEntityAndTable() {
        List<IndexEntry> idx = index(List.of(itemEntity()), List.of(
                new IndexedVocab("SLIN", null, "ent-item", "company")), List.of());

        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                "how many SLINs shipped last week", idx, CAP);

        assertEquals(1, r.resolutions().size());
        Resolution res = r.resolutions().get(0);
        assertEquals("SLINs", res.surface());
        assertEquals(Kind.ENTITY, res.kind());
        assertEquals("Item (table: retail_core.items)", res.target());
        assertTrue(r.expandedTokens().contains("item"));
        assertTrue(r.expandedTokens().contains("items"));
    }

    @Test
    void entityNameItselfResolves() {
        List<IndexEntry> idx = index(List.of(itemEntity()), List.of(), List.of());

        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                "list all items below reorder point", idx, CAP);

        assertEquals(1, r.resolutions().size());
        assertEquals(Kind.ENTITY, r.resolutions().get(0).kind());
        assertEquals("Item (table: retail_core.items)", r.resolutions().get(0).target());
    }

    // ── VALUE via persisted domain value (reverse PRO-24 direction) ──────────

    @Test
    void persistedDomainValueResolvesToComposedPredicate() {
        List<IndexEntry> idx = index(List.of(), List.of(), List.of(
                new IndexedColumn("obj-stores", "stores", "store_type",
                        List.of("flagship", "outlet"))));

        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                "revenue for flagship locations", idx, CAP);

        List<Resolution> values = r.resolutions().stream()
                .filter(res -> res.kind() == Kind.VALUE).toList();
        assertEquals(1, values.size());
        assertEquals("store_type = 'flagship'", values.get(0).target());
        assertEquals("company", values.get(0).tier());
    }

    // ── COLUMN via column name of a bound table (multi-word n-gram) ──────────

    @Test
    void threeWordNgramResolvesSeparatorInsensitiveColumnName() {
        List<IndexEntry> idx = index(List.of(), List.of(), List.of(
                new IndexedColumn("obj-stores", "stores", "store_manager_name", List.of())));

        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                "sort by store manager name please", idx, CAP);

        assertEquals(1, r.resolutions().size());
        Resolution res = r.resolutions().get(0);
        assertEquals(Kind.COLUMN, res.kind());
        assertEquals("stores.store_manager_name", res.target());
        assertEquals("store manager name", res.surface());
    }

    @Test
    void twoWordTermMatchesPluralQuestionForm() {
        List<IndexEntry> idx = index(List.of(itemEntity()), List.of(
                new IndexedVocab("purchase order", null, "ent-item", "company")), List.of());

        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                "open purchase orders this month", idx, CAP);

        assertEquals(1, r.resolutions().size());
        assertEquals("purchase orders", r.resolutions().get(0).surface());
    }

    // ── Precedence, first-match-wins, ambiguity (PRO-30 §2 / §3.4) ───────────

    @Test
    void companyTierBeatsPackTierForSameSurfaceAndKind() {
        List<IndexEntry> idx = index(List.of(), List.of(
                new IndexedVocab("SLIN", "line_type = 'pack-meaning'", null, "pack:mfg-v1"),
                new IndexedVocab("SLIN", "line_type = 'house-meaning'", null, "company")),
                List.of());

        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                "count SLIN records", idx, CAP);

        assertEquals(1, r.resolutions().size());
        assertEquals("line_type = 'house-meaning'", r.resolutions().get(0).target());
        assertEquals("company", r.resolutions().get(0).tier());
    }

    @Test
    void packTierResolvesWhenCompanyHasNoMapping() {
        // Mocked pack-instantiated row (provenance stamping itself is roadmap R3)
        List<IndexEntry> idx = index(List.of(), List.of(
                new IndexedVocab("DLX", "room_type = 'Deluxe Room'", null, "pack:hospitality-v1")),
                List.of());

        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                "occupancy for DLX this weekend", idx, CAP);

        assertEquals(1, r.resolutions().size());
        assertEquals("pack:hospitality-v1", r.resolutions().get(0).tier());
        assertEquals("Industry pack (hospitality-v1)", r.resolutions().get(0).sourceLabel());
    }

    @Test
    void firstMatchWinsWithinATier() {
        List<IndexEntry> idx = index(List.of(), List.of(
                new IndexedVocab("overdue", "due_date < NOW()", null, "company"),
                new IndexedVocab("overdue", "status = 'LATE'", null, "company")), List.of());

        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                "all overdue invoices", idx, CAP);

        assertEquals(1, r.resolutions().size());
        assertEquals("due_date < NOW()", r.resolutions().get(0).target());
    }

    @Test
    void oneSurfaceTwoKindsListsBothForTheModelToChoose() {
        // "Dept" is both a curated predicate and a raw column of a bound table
        List<IndexEntry> idx = index(List.of(), List.of(
                new IndexedVocab("dept", "department = 'active'", null, "company")),
                List.of(new IndexedColumn("obj-emp", "employees", "dept", List.of())));

        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                "headcount by dept", idx, CAP);

        assertEquals(2, r.resolutions().size());
        assertTrue(r.resolutions().stream().anyMatch(res -> res.kind() == Kind.VALUE));
        assertTrue(r.resolutions().stream().anyMatch(res -> res.kind() == Kind.COLUMN));
    }

    // ── Caps and zero-resolution path ─────────────────────────────────────────

    @Test
    void resolutionsAreCappedAtConfiguredMaximum() {
        List<IndexedVocab> vocab = new ArrayList<>();
        StringBuilder question = new StringBuilder("report on");
        for (int i = 0; i < 12; i++) {
            vocab.add(new IndexedVocab("term" + i, "col" + i + " = 'v'", null, "company"));
            question.append(" term").append(i);
        }
        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                question.toString(), index(List.of(), vocab, List.of()), CAP);

        assertEquals(CAP, r.resolutions().size());
    }

    @Test
    void zeroMatchesYieldsEmptyResultAndEmptyPromptBlock() {
        List<IndexEntry> idx = index(List.of(itemEntity()), List.of(
                new IndexedVocab("TX", "state_province = 'Texas'", null, "company")), List.of());

        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                "what changed yesterday", idx, CAP);

        assertTrue(r.isEmpty());
        assertTrue(r.expandedTokens().isEmpty());
        assertEquals("", r.renderPromptBlock());
    }

    @Test
    void stopWordUnigramsNeverMatchIndexTerms() {
        // A domain value equal to a stop word must not resolve on every question
        List<IndexEntry> idx = index(List.of(), List.of(), List.of(
                new IndexedColumn("obj-x", "orders", "channel", List.of("in", "online"))));

        ResolvedQuestion r = BusinessLanguageResolver.resolveAgainstIndex(
                "orders in march", idx, CAP);

        assertTrue(r.resolutions().stream().noneMatch(res -> "in".equals(res.surface())));
    }

    // ── Prompt block grammar (PRO-30 §6, exact) ───────────────────────────────

    @Test
    void promptBlockUsesExactApprovedGrammar() {
        ResolvedQuestion r = new ResolvedQuestion("q", List.of(
                new Resolution("TX", Kind.VALUE, "state_province = 'Texas'", "company"),
                new Resolution("SLIN", Kind.ENTITY, "Item (table: retail_core.items)", "company"),
                new Resolution("DLX", Kind.VALUE, "room_type = 'Deluxe Room'", "pack:hospitality-v1"),
                new Resolution("Mgr", Kind.COLUMN, "stores.store_manager_name", "company")),
                java.util.Set.of());

        assertEquals("""
                === RESOLUTIONS ===
                "TX" = value: state_province = 'Texas'   [company]
                "SLIN" = entity: Item (table: retail_core.items)   [company]
                "DLX" = value: room_type = 'Deluxe Room'   [pack:hospitality-v1]
                "Mgr" = column: stores.store_manager_name   [company]
                """, r.renderPromptBlock());
    }

    // ── Tier derivation seam ──────────────────────────────────────────────────

    @Test
    void tierDerivesFromCreatedByPrefix() {
        assertEquals("company", BusinessLanguageResolver.tierOf(null));
        assertEquals("company", BusinessLanguageResolver.tierOf("prakash@example.com"));
        assertEquals("pack:hospitality-v1", BusinessLanguageResolver.tierOf("pack:hospitality-v1"));
    }

    // ── End-to-end through resolve() with hand-rolled fakes ──────────────────

    private static class FakeSemanticRepository extends SemanticRepository {
        final List<BusinessEntity> entities;
        final List<OperationalVocabulary> terms;
        FakeSemanticRepository(List<BusinessEntity> entities, List<OperationalVocabulary> terms) {
            super(null);
            this.entities = entities;
            this.terms = terms;
        }
        @Override public List<BusinessEntity> findEntitiesByDomain(String domainKey) { return entities; }
        @Override public List<OperationalVocabulary> findTermsByDomain(String domainKey) { return terms; }
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
        @Override public Optional<DataObject> findDataObjectByKey(String key) {
            return Optional.ofNullable(objects.get(key));
        }
        @Override public List<DataColumn> findColumnsByObject(String objectKey) {
            return columns.getOrDefault(objectKey, List.of());
        }
        @Override public Optional<ValueDomain> findValueDomainByKey(String key) {
            return Optional.ofNullable(domains.get(key));
        }
    }

    private static BusinessLanguageResolver storesResolver() {
        Instant now = Instant.now();
        BusinessEntity store = new BusinessEntity("ent-store", "retail", "Store", "A retail location",
                "obj-stores", null, null, "ACTIVE", "steward@tenant.com", now, now);
        OperationalVocabulary tx = new OperationalVocabulary("term-tx", "retail", "ent-store",
                "TX", "Texas stores", "state_province = 'Texas'", null, "ACTIVE", now, now);
        DataObject stores = new DataObject("obj-stores", "retail", "stores", "conn-1",
                "retail_core", "stores", "Stores", "Physical retail locations",
                "", "", "", "", "", "", "", 500, false, "SCANNED", 1, now, now);
        DataColumn stateCol = new DataColumn("col-state", "obj-stores", "state_province",
                "character varying", true, "", false, false, false, false, true,
                null, "vdom-state", DataColumn.ROLE_INFERRED, now, now);
        ValueDomain stateDomain = new ValueDomain("vdom-state", "conn-1", "retail_core",
                "stores.state_province", "OBSERVED", false,
                "[\"California\",\"Texas\"]", null);

        return new BusinessLanguageResolver(
                new FakeSemanticRepository(List.of(store), List.of(tx)),
                new FakeEnterpriseMapRepository(
                        Map.of("obj-stores", stores),
                        Map.of("obj-stores", List.of(stateCol)),
                        Map.of("vdom-state", stateDomain)),
                new ObjectMapper());
    }

    @Test
    void endToEndResolveBuildsIndexFromExistingStoresOnly() {
        ResolvedQuestion r = storesResolver().resolve("show me all TX stores", List.of("retail"));

        // "TX" via curated vocabulary + "stores" via entity binding
        assertEquals(2, r.resolutions().size());
        Resolution tx = r.resolutions().stream()
                .filter(res -> res.surface().equals("TX")).findFirst().orElseThrow();
        assertEquals("state_province = 'Texas'", tx.target());
        assertEquals("company", tx.tier());
        assertTrue(r.expandedTokens().contains("texas"));
        assertEquals("show me all TX stores", r.original());
    }

    @Test
    void endToEndDomainValueResolvesThroughPersistedDomainOnly() {
        ResolvedQuestion r = storesResolver().resolve("compare Texas and California", List.of("retail"));

        // Both literals are persisted domain values → composed predicates
        assertTrue(r.resolutions().stream()
                .anyMatch(res -> res.target().equals("state_province = 'Texas'")));
        assertTrue(r.resolutions().stream()
                .anyMatch(res -> res.target().equals("state_province = 'California'")));
    }

    @Test
    void resolveIsEmptyOnBlankQuestionOrNoDomains() {
        BusinessLanguageResolver resolver = storesResolver();
        assertTrue(resolver.resolve("", List.of("retail")).isEmpty());
        assertTrue(resolver.resolve(null, List.of("retail")).isEmpty());
        assertTrue(resolver.resolve("show me all TX stores", List.of()).isEmpty());
    }

    @Test
    void repositoryFailureDegradesToEmptyResolution() {
        BusinessLanguageResolver resolver = new BusinessLanguageResolver(
                new SemanticRepository(null) {
                    @Override public List<BusinessEntity> findEntitiesByDomain(String d) {
                        throw new IllegalStateException("db down");
                    }
                },
                new FakeEnterpriseMapRepository(Map.of(), Map.of(), Map.of()),
                new ObjectMapper());

        ResolvedQuestion r = resolver.resolve("show me all TX stores", List.of("retail"));
        assertTrue(r.isEmpty());
        assertEquals("show me all TX stores", r.original());
    }
}
