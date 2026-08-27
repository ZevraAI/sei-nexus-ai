package com.sei.nexus.agentbrain;

import com.sei.nexus.agentrunner.ZevraAgent;
import com.sei.nexus.semantic.BusinessLanguageResolver;
import com.sei.nexus.semantic.ResolvedQuestion;
import com.sei.nexus.semanticmodel.AttributeRole;
import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;
import com.sei.nexus.semanticmodel.SemanticModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0003 semantic model, Phase 1B — AgentBrain reasons over the canonical semantic model
 * produced by {@link EnterpriseSemanticAssembler}. It never touches persistence, and passes the
 * execution-plane raw material through to the ResolvedBusinessModel for the builder.
 */
class AgentBrainTest {

    /** A fake assembler — proves AgentBrain depends on the assembler, not on persistence. */
    static class FakeAssembler extends EnterpriseSemanticAssembler {
        final SemanticModel model;          // returned for the connection primitive
        SemanticModel domainModel;          // returned for the domain primitive, when set
        SemanticModel objectKeysModel;      // returned for the concept-scoped primitive, when set
        List<String> seenKeys;
        List<String> seenDomainKeys;
        List<String> seenObjectKeys;
        FakeAssembler(SemanticModel model) { super(null); this.model = model; }
        @Override public SemanticModel assemble(List<String> connectionKeys) {
            this.seenKeys = connectionKeys; return model;
        }
        @Override public SemanticModel assembleByDomains(List<String> domainKeys) {
            this.seenDomainKeys = domainKeys;
            return domainModel != null ? domainModel : model;
        }
        @Override public SemanticModel assembleByObjectKeys(List<String> objectKeys) {
            this.seenObjectKeys = objectKeys;
            return objectKeysModel != null ? objectKeysModel : model;
        }
    }

    /** A fake concept resolver — scripts Stage 1/2's combined result directly, so AgentBrain's
     *  own wiring (not the resolver's internals, covered by {@code
     *  ConceptScopedMetadataResolverTest}) is what's under test here. */
    static class FakeConceptResolver extends ConceptScopedMetadataResolver {
        Map<String, java.util.Optional<List<String>>> resultByConnection = new java.util.HashMap<>();
        List<String> seenConnectionKeys = new java.util.ArrayList<>();
        String seenQuestion;
        FakeConceptResolver() { super(null, null, null, null); }
        @Override public java.util.Optional<List<String>> resolveObjectKeys(String connectionKey, String question) {
            seenConnectionKeys.add(connectionKey);
            seenQuestion = question;
            return resultByConnection.getOrDefault(connectionKey, java.util.Optional.empty());
        }
    }

    /** A fake resolver — records whether AgentBrain consulted the Semantic Foundation. */
    static class FakeResolver extends BusinessLanguageResolver {
        ResolvedQuestion result;
        List<String> seenDomainKeys;
        int calls = 0;
        FakeResolver() { super(null, null, null); }
        @Override public ResolvedQuestion resolve(String question, List<String> domainKeys) {
            calls++;
            seenDomainKeys = domainKeys;
            return result != null ? result : ResolvedQuestion.empty(question);
        }
    }

    private static AgentBrain brainWith(FakeAssembler assembler, FakeResolver resolver) {
        return new AgentBrain(assembler, resolver);
    }

    private static ZevraAgent agent(List<String> connections) {
        return new ZevraAgent("agent-1", "public", "Ops", "ops", "d", "persona", "goal",
                connections, 5, "ACTIVE", "u@x.com", null, null);
    }

    private static SemanticModel twoObjects() {
        BusinessObject orders = new BusinessObject("obj-orders", "Orders", "",
                List.of(new BusinessAttribute("c-oid", "Id", AttributeRole.IDENTIFIER)), List.of());
        BusinessObject shipments = new BusinessObject("obj-ship", "Shipments", "",
                List.of(new BusinessAttribute("c-sid", "Id", AttributeRole.IDENTIFIER)), List.of());
        return new SemanticModel(
                List.of(shipments, orders),
                Map.of("obj-orders", new PhysicalTable("conn-1", "public", "orders"),
                       "obj-ship",   new PhysicalTable("conn-1", "public", "shipments")),
                Map.of("c-oid", new PhysicalColumn("conn-1", "public", "orders", "id"),
                       "c-sid", new PhysicalColumn("conn-1", "public", "shipments", "id")));
    }

    @Test
    void resolvesOverSemanticModelAndCarriesTargetsThrough() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        AgentBrain brain = brainWith(assembler, new FakeResolver());

        ResolvedBusinessModel model = brain.resolve(agent(List.of("conn-1")), "how many orders");

        assertEquals(List.of("conn-1"), assembler.seenKeys, "resolved against the agent's connection scope");
        assertEquals(2, model.objects().size(), "the full approved surface is carried");
        // relevance ranking (business reasoning) puts the matching object first
        assertEquals("Orders", model.objects().get(0).businessName());
        // execution-plane raw material passed through for the builder
        assertEquals("orders", model.objectTargets().get("obj-orders").table());
        assertEquals("id", model.attributeTargets().get("c-oid").column());
    }

    @Test
    void emptyModelYieldsEmptyResolution() {
        AgentBrain brain = brainWith(
                new FakeAssembler(new SemanticModel(List.of(), Map.of(), Map.of())), new FakeResolver());
        assertTrue(brain.resolve(agent(List.of("conn-1")), "q").objects().isEmpty());
    }

    // ── Phase 2: AgentBrain owns the Semantic Foundation ──────────────────────

    /** Agent scope carries no business domains, so resolution is skipped entirely — Phase 1 parity. */
    @Test
    void agentScopeSkipsBusinessLanguageResolutionEntirely() {
        FakeResolver resolver = new FakeResolver();
        AgentBrain brain = brainWith(new FakeAssembler(twoObjects()), resolver);

        ResolvedBusinessModel model = brain.resolve(agent(List.of("conn-1")), "how many orders");

        assertEquals(0, resolver.calls, "no domain scope ⇒ the resolver is never consulted");
        assertTrue(model.resolution().isEmpty(), "empty resolution reproduces pre-Phase-2 behaviour");
        assertTrue(model.literalScope().isEmpty());
        assertEquals("Orders", model.objects().get(0).businessName(), "ranking unchanged");
    }

    /** A domain-scoped question resolves through the Semantic Foundation and carries its signals. */
    @Test
    void domainScopeResolvesAndCarriesSemanticSignals() {
        FakeResolver resolver = new FakeResolver();
        resolver.result = new ResolvedQuestion("show shipments", List.of(), Set.of("shipments"),
                List.of("TX"),
                List.of(new ResolvedQuestion.LiteralCandidate("stores", "state_province",
                        true, List.of("Texas", "California"))));
        AgentBrain brain = brainWith(new FakeAssembler(twoObjects()), resolver);

        ResolvedBusinessModel model = brain.resolve("agent-1", List.of("conn-1"),
                List.of("dom-retail"), "show shipments");

        assertEquals(1, resolver.calls);
        assertEquals(List.of("dom-retail"), resolver.seenDomainKeys, "resolved against the domain scope");
        // the resolution travels on the model for downstream stages
        assertEquals(List.of("TX"), model.resolution().unresolvedTerms());
        // and the literal validation scope is derived here (single owner)
        assertEquals("stores.state_province",
                model.literalScope().get("stores.state_province").qualifiedColumn());
        assertEquals("stores.state_province",
                model.literalScope().get("state_province").qualifiedColumn(),
                "unambiguous bare column is an alias fallback key");
    }

    /** Expansion tokens join the ranking keywords, so a resolved term selects its object. */
    @Test
    void expansionTokensInfluenceObjectRanking() {
        FakeResolver resolver = new FakeResolver();
        // The question mentions neither object by name; only the canonical token does.
        resolver.result = new ResolvedQuestion("what about SHP", List.of(), Set.of("shipments"));
        AgentBrain brain = brainWith(new FakeAssembler(twoObjects()), resolver);

        ResolvedBusinessModel model = brain.resolve("agent-1", List.of("conn-1"),
                List.of("dom-retail"), "what about SHP");

        assertEquals("Shipments", model.objects().get(0).businessName(),
                "the canonical token ranks Shipments first even though the surface form never matched");
    }

    /** The literal-scope derivation drops a bare key that two different tables would claim. */
    @Test
    void ambiguousBareColumnIsDroppedFromLiteralScope() {
        ResolvedQuestion resolved = new ResolvedQuestion("q", List.of(), Set.of(), List.of("X"),
                List.of(new ResolvedQuestion.LiteralCandidate("stores", "status", true, List.of("A")),
                        new ResolvedQuestion.LiteralCandidate("orders", "status", true, List.of("B"))));

        Map<String, com.sei.nexus.semanticmodel.ColumnValueDomain> scope =
                AgentBrain.literalScopeOf(resolved);

        assertNull(scope.get("status"), "ambiguous bare column is removed");
        assertNotNull(scope.get("stores.status"));
        assertNotNull(scope.get("orders.status"));
    }

    // ── Phase 3: the approved surface represents the same business scope ───────

    /** Two objects on the SAME connection but in DIFFERENT domains. */
    private static SemanticModel twoDomainsOneConnection() {
        BusinessObject inScope = new BusinessObject("obj-in", "Orders", "",
                List.of(new BusinessAttribute("c-in", "Id", AttributeRole.IDENTIFIER)), List.of());
        BusinessObject otherDomain = new BusinessObject("obj-other", "Payroll", "",
                List.of(new BusinessAttribute("c-other", "Id", AttributeRole.IDENTIFIER)), List.of());
        return new SemanticModel(
                List.of(inScope, otherDomain),
                Map.of("obj-in",    new PhysicalTable("conn-1", "public", "orders"),
                       "obj-other", new PhysicalTable("conn-1", "public", "payroll")),
                Map.of("c-in",    new PhysicalColumn("conn-1", "public", "orders", "id"),
                       "c-other", new PhysicalColumn("conn-1", "public", "payroll", "id")));
    }

    /**
     * Class D eliminated: the surface is the agent's DOMAINS narrowed to its connections — an
     * object is never admitted merely because it shares a connection with an in-scope object.
     */
    @Test
    void domainScopeIsNotBroadenedByASharedConnection() {
        FakeAssembler assembler = new FakeAssembler(twoDomainsOneConnection());
        // the domain primitive returns only the in-scope domain's object
        assembler.domainModel = new SemanticModel(
                List.of(twoDomainsOneConnection().objects().get(0)),
                Map.of("obj-in", new PhysicalTable("conn-1", "public", "orders")),
                Map.of("c-in",   new PhysicalColumn("conn-1", "public", "orders", "id")));

        ResolvedBusinessModel model = brainWith(assembler, new FakeResolver())
                .resolve("agent-1", List.of("conn-1"), List.of("dom-sales"), "orders");

        assertEquals(List.of("dom-sales"), assembler.seenDomainKeys, "scope is resolved by domain");
        assertEquals(1, model.objects().size(), "only the in-domain object is approved");
        assertEquals("Orders", model.objects().get(0).businessName());
        assertNull(model.objectTargets().get("obj-other"),
                "an out-of-domain object on the same connection is NOT in the approved surface");
    }

    /**
     * Class A eliminated: an agent with domains but no connection keys still gets its domain
     * surface, so the compiled contract is non-empty.
     */
    @Test
    void domainScopeSurvivesMissingConnectionKeys() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());

        ResolvedBusinessModel model = brainWith(assembler, new FakeResolver())
                .resolve("agent-1", List.of(), List.of("dom-retail"), "how many orders");

        assertEquals(2, model.objects().size(),
                "no connection keys ⇒ the domain scope stands; the surface is not blanked");
    }

    /**
     * Class B eliminated: when every in-domain object sits on a connection outside the agent's
     * (stale) list, narrowing would empty the scope — so the domain scope is kept, exactly as the
     * conversational grounding has always behaved.
     */
    @Test
    void staleConnectionKeysFallBackToTheDomainScope() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        assembler.domainModel = twoObjects();   // all objects live on conn-1

        ResolvedBusinessModel model = brainWith(assembler, new FakeResolver())
                .resolve("agent-1", List.of("conn-STALE"), List.of("dom-retail"), "how many orders");

        assertEquals(2, model.objects().size(),
                "a stale connection key must not silently blank the approved surface");
    }

    /** Partial overlap narrows rather than falling back. */
    @Test
    void narrowingKeepsOnlyObjectsOnApprovedConnections() {
        BusinessObject onApproved = new BusinessObject("obj-a", "Orders", "",
                List.of(new BusinessAttribute("c-a", "Id", AttributeRole.IDENTIFIER)), List.of());
        BusinessObject onOther = new BusinessObject("obj-b", "Shipments", "",
                List.of(new BusinessAttribute("c-b", "Id", AttributeRole.IDENTIFIER)), List.of());
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        assembler.domainModel = new SemanticModel(
                List.of(onApproved, onOther),
                Map.of("obj-a", new PhysicalTable("conn-1", "public", "orders"),
                       "obj-b", new PhysicalTable("conn-2", "public", "shipments")),
                Map.of("c-a", new PhysicalColumn("conn-1", "public", "orders", "id"),
                       "c-b", new PhysicalColumn("conn-2", "public", "shipments", "id")));

        ResolvedBusinessModel model = brainWith(assembler, new FakeResolver())
                .resolve("agent-1", List.of("conn-1"), List.of("dom-retail"), "orders");

        assertEquals(1, model.objects().size());
        assertEquals("Orders", model.objects().get(0).businessName());
        assertNull(model.attributeTargets().get("c-b"),
                "attribute targets are narrowed with their object");
    }

    /** The autonomous-agent path is untouched: no domains ⇒ connection-scoped, as before. */
    @Test
    void agentScopeStillResolvesByConnection() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());

        ResolvedBusinessModel model = brainWith(assembler, new FakeResolver())
                .resolve(agent(List.of("conn-1")), "how many orders");

        assertEquals(List.of("conn-1"), assembler.seenKeys, "agents resolve by connection");
        assertNull(assembler.seenDomainKeys, "the domain primitive is never used for agents");
        assertEquals(2, model.objects().size());
    }

    // ── Concept-Scoped Metadata Narrowing — AgentBrain wiring ────────────────────
    // (Stage 1/2 mechanism itself is covered by ConceptScopedMetadataResolverTest; these prove
    // AgentBrain's integration: it uses the resolver's result when present, falls back to the
    // existing full assembly when absent, and the resulting SemanticModel/ResolvedBusinessModel
    // reaches the caller through the exact same, unmodified downstream shape as before.)

    // Item 15 — existing downstream flow receives the Stage 2 metadata exactly as before: the
    // narrowed SemanticModel flows into ResolvedBusinessModel via the same fields/types any other
    // assembly path already uses — no new downstream type, no reshaping.
    @Test
    void conceptScopedNarrowingFeedsTheSameResolvedBusinessModelShapeAsTheFullAssembly() {
        FakeAssembler assembler = new FakeAssembler(twoObjects()); // full-assembly fallback model
        SemanticModel narrowed = new SemanticModel(
                List.of(new BusinessObject("obj-inv", "Inventory", "",
                        List.of(new BusinessAttribute("c-inv", "OnHandQty", AttributeRole.MEASURE)), List.of())),
                Map.of("obj-inv", new PhysicalTable("conn-1", "public", "inventory_balances")),
                Map.of("c-inv", new PhysicalColumn("conn-1", "public", "inventory_balances", "on_hand_qty")));
        assembler.objectKeysModel = narrowed;
        FakeConceptResolver conceptResolver = new FakeConceptResolver();
        conceptResolver.resultByConnection.put("conn-1", java.util.Optional.of(List.of("obj-inv")));
        AgentBrain brain = new AgentBrain(assembler, new FakeResolver(), conceptResolver);

        ResolvedBusinessModel model = brain.resolve(agent(List.of("conn-1")), "how much stock do we have?");

        assertEquals(List.of("obj-inv"), assembler.seenObjectKeys,
                "AgentBrain must hand the resolver's selected object keys to the assembler's targeted primitive");
        assertEquals(1, model.objects().size());
        assertEquals("Inventory", model.objects().get(0).businessName());
        assertEquals("inventory_balances", model.objectTargets().get("obj-inv").table());
        assertEquals("on_hand_qty", model.attributeTargets().get("c-inv").column());
        assertNull(assembler.seenKeys, "the full-assembly primitive must never be called once narrowing applies");
    }

    @Test
    void conceptResolverReceivingTheQuestionMatchesWhatTheCallerAsked() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        FakeConceptResolver conceptResolver = new FakeConceptResolver();
        conceptResolver.resultByConnection.put("conn-1", java.util.Optional.of(List.of()));
        AgentBrain brain = new AgentBrain(assembler, new FakeResolver(), conceptResolver);

        brain.resolve(agent(List.of("conn-1")), "which stores are below reorder point");

        assertEquals(List.of("conn-1"), conceptResolver.seenConnectionKeys);
        assertEquals("which stores are below reorder point", conceptResolver.seenQuestion,
                "AgentBrain must pass the real question through unmodified — it never rewrites or interprets it itself");
    }

    /** The LLM legitimately selecting zero concepts must yield an honest empty result, not a
     *  fallback to the full unnarrowed surface — that would defeat the whole point of narrowing. */
    @Test
    void zeroSelectedConceptsYieldsAnEmptyModelRatherThanFallingBackToFullAssembly() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        FakeConceptResolver conceptResolver = new FakeConceptResolver();
        conceptResolver.resultByConnection.put("conn-1", java.util.Optional.of(List.of()));
        AgentBrain brain = new AgentBrain(assembler, new FakeResolver(), conceptResolver);

        ResolvedBusinessModel model = brain.resolve(agent(List.of("conn-1")), "what is the weather today?");

        assertTrue(model.objects().isEmpty());
        assertNull(assembler.seenKeys, "zero relevant concepts must not trigger the full-assembly fallback");
        assertNull(assembler.seenObjectKeys, "assembleByObjectKeys need not even be called for an empty selection");
    }

    /** No active pack / no tenant concept catalog for this connection ⇒ Stage 1 does not apply ⇒
     *  AgentBrain must fall back to exactly the pre-existing full assembly, unchanged. */
    @Test
    void noConceptResolverResultFallsBackToTheExistingFullAssembly() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        FakeConceptResolver conceptResolver = new FakeConceptResolver(); // no entry for conn-1 ⇒ Optional.empty()
        AgentBrain brain = new AgentBrain(assembler, new FakeResolver(), conceptResolver);

        ResolvedBusinessModel model = brain.resolve(agent(List.of("conn-1")), "how many orders");

        assertEquals(List.of("conn-1"), assembler.seenKeys, "must fall back to the full connection-scoped assembly");
        assertEquals(2, model.objects().size());
        assertNull(assembler.seenObjectKeys, "the targeted primitive must never be called on fallback");
    }

    /** Every pre-existing test in this file constructs AgentBrain via the 2-arg constructor —
     *  proving the feature is a complete no-op (byte-identical to before it existed) when no
     *  concept resolver is wired at all, exactly like every other collaborator added this way in
     *  this codebase (EnterpriseSemanticAssembler, BusinessObjectBatchAnalyzer). */
    @Test
    void twoArgConstructorNeverAttemptsConceptScopedNarrowing() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        AgentBrain brain = new AgentBrain(assembler, new FakeResolver()); // 2-arg — no conceptResolver

        ResolvedBusinessModel model = brain.resolve(agent(List.of("conn-1")), "how many orders");

        assertEquals(List.of("conn-1"), assembler.seenKeys);
        assertEquals(2, model.objects().size());
    }

    // ── domain_keys != metadata-selection criteria (Concept-Scoped Metadata Narrowing —
    // domain-key decoupling) ──────────────────────────────────────────────────────────────
    //
    // A domain-bearing conversational scope (domainKeys=['PLATFORM'], exactly what every
    // onboarding-created agent has) must now ALSO reach concept-scoped narrowing when its
    // connection has the prerequisites — previously it never could, because
    // assembleBusinessScope only tried conceptScopedModel inside the domainKeys.isEmpty()
    // branch. domainKeys itself must still reach BusinessLanguageResolver unchanged.

    private static SemanticModel inventoryOnly() {
        BusinessObject inv = new BusinessObject("obj-inv", "Inventory", "",
                List.of(new BusinessAttribute("c-inv", "OnHandQty", AttributeRole.MEASURE)), List.of());
        return new SemanticModel(
                List.of(inv),
                Map.of("obj-inv", new PhysicalTable("conn-1", "public", "inventory_balances")),
                Map.of("c-inv", new PhysicalColumn("conn-1", "public", "inventory_balances", "on_hand_qty")));
    }

    // Test 1 — an agent with domainKeys=['PLATFORM'] DOES invoke concept-scoped selection.
    @Test
    void domainBearingScopeInvokesConceptScopedSelection() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        assembler.objectKeysModel = inventoryOnly();
        FakeConceptResolver conceptResolver = new FakeConceptResolver();
        conceptResolver.resultByConnection.put("conn-1", java.util.Optional.of(List.of("obj-inv")));
        AgentBrain brain = new AgentBrain(assembler, new FakeResolver(), conceptResolver);

        brain.resolve("agent-1", List.of("conn-1"), List.of("PLATFORM"), "how much stock do we have?");

        assertEquals(List.of("conn-1"), conceptResolver.seenConnectionKeys,
                "concept-scoped selection must be attempted even though domainKeys is non-empty");
    }

    // Test 2 — the caller receives the selected concept's objects/columns, not the entire
    // PLATFORM domain (assembleByDomains must never be called when concept-scoping succeeds).
    @Test
    void domainBearingScopeReceivesSelectedConceptObjectsNotTheWholeDomain() {
        FakeAssembler assembler = new FakeAssembler(twoObjects()); // would be returned by assembleByDomains
        assembler.objectKeysModel = inventoryOnly();               // returned by assembleByObjectKeys
        FakeConceptResolver conceptResolver = new FakeConceptResolver();
        conceptResolver.resultByConnection.put("conn-1", java.util.Optional.of(List.of("obj-inv")));
        AgentBrain brain = new AgentBrain(assembler, new FakeResolver(), conceptResolver);

        ResolvedBusinessModel model = brain.resolve("agent-1", List.of("conn-1"),
                List.of("PLATFORM"), "how much stock do we have?");

        assertEquals(1, model.objects().size(), "must receive only the concept-selected object, not the whole domain");
        assertEquals("Inventory", model.objects().get(0).businessName());
        assertNull(assembler.seenDomainKeys, "assembleByDomains must never be called when concept-scoping succeeds");
        assertEquals(List.of("obj-inv"), assembler.seenObjectKeys);
    }

    // Test 3 / 9 — BusinessLanguageResolver (and, by the same code path, every other independent
    // domain_keys consumer downstream in ChatService) still receives PLATFORM unchanged, whether
    // concept-scoping succeeds or falls back.
    @Test
    void businessLanguageResolverStillReceivesDomainKeysWhenConceptScopingSucceeds() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        assembler.objectKeysModel = inventoryOnly();
        FakeConceptResolver conceptResolver = new FakeConceptResolver();
        conceptResolver.resultByConnection.put("conn-1", java.util.Optional.of(List.of("obj-inv")));
        FakeResolver blr = new FakeResolver();
        AgentBrain brain = new AgentBrain(assembler, blr, conceptResolver);

        brain.resolve("agent-1", List.of("conn-1"), List.of("PLATFORM"), "how much stock do we have?");

        assertEquals(1, blr.calls, "BusinessLanguageResolver must still run for a domain-bearing scope");
        assertEquals(List.of("PLATFORM"), blr.seenDomainKeys,
                "domain_keys must reach BusinessLanguageResolver unchanged — it is not the metadata-selection criterion");
    }

    @Test
    void businessLanguageResolverStillReceivesDomainKeysWhenConceptScopingFallsBack() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        FakeConceptResolver conceptResolver = new FakeConceptResolver(); // no entry for conn-1 ⇒ Optional.empty()
        FakeResolver blr = new FakeResolver();
        AgentBrain brain = new AgentBrain(assembler, blr, conceptResolver);

        brain.resolve("agent-1", List.of("conn-1"), List.of("PLATFORM"), "how many orders");

        assertEquals(1, blr.calls, "BusinessLanguageResolver must still run even when concept-scoping is inapplicable");
        assertEquals(List.of("PLATFORM"), blr.seenDomainKeys);
        assertEquals(List.of("PLATFORM"), assembler.seenDomainKeys,
                "the fallback assembly must still run exactly as before this change");
    }

    // Test 8 — existing fallback behavior remains fully intact when concept-scoping
    // prerequisites are unavailable for a domain-bearing scope: assembleByDomains + connection
    // narrowing runs exactly as it did before this change.
    @Test
    void domainBearingScopeFallsBackToAssembleByDomainsWhenConceptScopingIsInapplicable() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        FakeConceptResolver conceptResolver = new FakeConceptResolver(); // declines for conn-1

        AgentBrain brain = new AgentBrain(assembler, new FakeResolver(), conceptResolver);
        ResolvedBusinessModel model = brain.resolve("agent-1", List.of("conn-1"),
                List.of("PLATFORM"), "how many orders");

        assertEquals(List.of("PLATFORM"), assembler.seenDomainKeys,
                "assembleByDomains must still be called exactly as before when concept-scoping does not apply");
        assertEquals(2, model.objects().size());
        assertNull(assembler.seenObjectKeys, "the concept-scoped primitive must never be called on fallback");
    }

    @Test
    void noConceptResolverAtAllStillFallsBackToAssembleByDomainsForADomainBearingScope() {
        // The exact pre-existing behavior for every tenant that hasn't wired a resolver at all —
        // must remain byte-identical.
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        AgentBrain brain = new AgentBrain(assembler, new FakeResolver()); // 2-arg — no conceptResolver

        ResolvedBusinessModel model = brain.resolve("agent-1", List.of("conn-1"),
                List.of("PLATFORM"), "how many orders");

        assertEquals(List.of("PLATFORM"), assembler.seenDomainKeys);
        assertEquals(2, model.objects().size());
    }

    // Test 5/6 — concept selection is performed entirely by the LLM (via the resolver); Java
    // (AgentBrain) neither inspects the question nor adds/infers a concept — it only relays
    // exactly the object keys the resolver returned, unchanged, for a domain-bearing scope too.
    @Test
    void javaRelaysTheResolversSelectionVerbatimForADomainBearingScopeNeverAddingOrInferring() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        assembler.objectKeysModel = inventoryOnly();
        FakeConceptResolver conceptResolver = new FakeConceptResolver();
        conceptResolver.resultByConnection.put("conn-1", java.util.Optional.of(List.of("obj-inv")));
        AgentBrain brain = new AgentBrain(assembler, new FakeResolver(), conceptResolver);

        brain.resolve("agent-1", List.of("conn-1"), List.of("PLATFORM"),
                "totally unrelated question text with no keyword overlap with inventory at all");

        assertEquals(List.of("obj-inv"), assembler.seenObjectKeys,
                "AgentBrain must pass through exactly what the resolver selected, regardless of question wording");
        assertEquals("totally unrelated question text with no keyword overlap with inventory at all",
                conceptResolver.seenQuestion, "the raw question is relayed unmodified — AgentBrain never rewrites or matches it itself");
    }

    // Test 7 — connection isolation remains intact for a domain-bearing, multi-connection scope:
    // if EVERY in-scope connection can be concept-scoped, narrowing applies across all of them;
    // if the prerequisite is missing for ANY one connection, the whole thing falls back to the
    // existing safe domain+connection-narrowing path — never a partial/mixed narrowing.
    @Test
    void multiConnectionDomainBearingScopeNarrowsOnlyWhenEveryConnectionQualifies() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        FakeConceptResolver conceptResolver = new FakeConceptResolver();
        conceptResolver.resultByConnection.put("conn-1", java.util.Optional.of(List.of("obj-inv")));
        // conn-2 has no entry ⇒ Optional.empty() ⇒ concept-scoping is inapplicable for the pair.
        AgentBrain brain = new AgentBrain(assembler, new FakeResolver(), conceptResolver);

        brain.resolve("agent-1", List.of("conn-1", "conn-2"), List.of("PLATFORM"), "how many orders");

        assertEquals(List.of("conn-1", "conn-2"), conceptResolver.seenConnectionKeys,
                "both connections must be attempted before deciding narrowing is inapplicable");
        assertEquals(List.of("PLATFORM"), assembler.seenDomainKeys,
                "one connection lacking prerequisites must fall the WHOLE scope back to assembleByDomains — never a partial narrowing");
        assertNull(assembler.seenObjectKeys);
    }

    @Test
    void multiConnectionDomainBearingScopeNarrowsAcrossAllConnectionsWhenBothQualify() {
        FakeAssembler assembler = new FakeAssembler(twoObjects());
        SemanticModel combined = new SemanticModel(
                List.of(new BusinessObject("obj-a", "A", "", List.of(), List.of()),
                        new BusinessObject("obj-b", "B", "", List.of(), List.of())),
                Map.of("obj-a", new PhysicalTable("conn-1", "public", "a"),
                       "obj-b", new PhysicalTable("conn-2", "public", "b")),
                Map.of());
        assembler.objectKeysModel = combined;
        FakeConceptResolver conceptResolver = new FakeConceptResolver();
        conceptResolver.resultByConnection.put("conn-1", java.util.Optional.of(List.of("obj-a")));
        conceptResolver.resultByConnection.put("conn-2", java.util.Optional.of(List.of("obj-b")));
        AgentBrain brain = new AgentBrain(assembler, new FakeResolver(), conceptResolver);

        ResolvedBusinessModel model = brain.resolve("agent-1", List.of("conn-1", "conn-2"),
                List.of("PLATFORM"), "how many orders");

        assertEquals(List.of("conn-1", "conn-2"), conceptResolver.seenConnectionKeys);
        assertEquals(List.of("obj-a", "obj-b"), assembler.seenObjectKeys,
                "when every connection qualifies, narrowing applies across all of them together");
        assertNull(assembler.seenDomainKeys, "assembleByDomains must not be called when every connection qualified");
        assertEquals(2, model.objects().size());
    }
}
