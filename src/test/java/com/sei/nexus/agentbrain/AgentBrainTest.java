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
        List<String> seenKeys;
        List<String> seenDomainKeys;
        FakeAssembler(SemanticModel model) { super(null); this.model = model; }
        @Override public SemanticModel assemble(List<String> connectionKeys) {
            this.seenKeys = connectionKeys; return model;
        }
        @Override public SemanticModel assembleByDomains(List<String> domainKeys) {
            this.seenDomainKeys = domainKeys;
            return domainModel != null ? domainModel : model;
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
}
