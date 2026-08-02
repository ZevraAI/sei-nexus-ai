package com.sei.nexus.agentbrain;

import com.sei.nexus.semantic.ResolvedQuestion;
import com.sei.nexus.semanticmodel.AttributeRole;
import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;
import com.sei.nexus.semanticmodel.SemanticModel;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified Answer Engine, Phase 3 — Step 1. The conversational path now compiles an
 * {@link ExecutionContract} for its own scope through the shared reasoning spine
 * ({@code AgentBrain.resolve(agentId, connectionKeys, domainKeys, question)} →
 * {@link ExecutionContractBuilder}).
 *
 * <p>The contract is <b>shadow</b> at this step: it is recorded on the audit trail but drives
 * neither grounding (Step 4) nor the runtime gate (Step 2). These tests pin the two properties
 * Step 1 must establish before those steps can proceed:
 *
 * <ol>
 *   <li>the compiled contract's approved surface covers every mapped table in the chat scope, so
 *       enabling the gate in Step 2 cannot reject a query that works today; and</li>
 *   <li>business-language resolution happens exactly once, inside AgentBrain, and is returned
 *       verbatim — so the conversational prompt is byte-identical to the pre-Step-1 pipeline.</li>
 * </ol>
 *
 * <p>Hand-rolled fakes; no database.
 */
class ChatScopeGroundingTest {

    private static final String CONN = "conn-1";
    private static final SqlTableReferenceExtractor EXTRACTOR = new SqlTableReferenceExtractor();

    private final ExecutionContractBuilder builder = new ExecutionContractBuilder(EXTRACTOR);

    /** Two mapped business objects on one connection, as a chat tenant would have. */
    private static SemanticModel retailModel() {
        BusinessObject stores = new BusinessObject("obj-stores", "Stores", "store master",
                List.of(new BusinessAttribute("c-sid", "Id", AttributeRole.IDENTIFIER),
                        new BusinessAttribute("c-state", "State", AttributeRole.DIMENSION)),
                List.of());
        BusinessObject orders = new BusinessObject("obj-orders", "Orders", "order records",
                List.of(new BusinessAttribute("c-oid", "Id", AttributeRole.IDENTIFIER)),
                List.of());
        return new SemanticModel(
                List.of(stores, orders),
                Map.of("obj-stores", new PhysicalTable(CONN, "retail_core", "stores"),
                       "obj-orders", new PhysicalTable(CONN, "retail_core", "orders")),
                Map.of("c-sid",   new PhysicalColumn(CONN, "retail_core", "stores", "id"),
                       "c-state", new PhysicalColumn(CONN, "retail_core", "stores", "state_province"),
                       "c-oid",   new PhysicalColumn(CONN, "retail_core", "orders", "id")));
    }

    private AgentBrain brain(AgentBrainTest.FakeResolver resolver) {
        return new AgentBrain(new AgentBrainTest.FakeAssembler(retailModel()), resolver);
    }

    /**
     * Step 2 pre-condition: the approved surface must cover every mapped table, in both the bare
     * and schema-qualified forms the planner may emit. If this fails, enabling the gate would
     * reject working conversational queries.
     */
    @Test
    void chatScopeContractApprovesEveryMappedTable() {
        ExecutionContract contract = builder.compile(
                brain(new AgentBrainTest.FakeResolver())
                        .resolve("agent-retail", List.of(CONN), List.of("dom-retail"),
                                "how many stores are in texas"));

        assertFalse(contract.isEmpty(), "a mapped chat scope compiles a non-empty contract");
        assertNotNull(contract.contractId(), "the contract is identifiable on the audit trail");
        assertEquals("agent-retail", contract.agentId());

        for (String table : List.of("STORES", "ORDERS")) {
            // retail_core is a non-default schema: the schema-qualified form is the executable
            // identity; the bare form is not approved.
            assertTrue(contract.executionBindings().isApproved(CONN, "RETAIL_CORE." + table),
                    "schema-qualified table must be approved: " + table);
            assertFalse(contract.executionBindings().isApproved(CONN, table),
                    "bare form of a non-default-schema table is not executable: " + table);
        }
        assertFalse(contract.executionBindings().isApproved(CONN, "INVOICES"),
                "an unmapped table is not approved");
    }

    /** The full approved surface is carried, not narrowed by question relevance. */
    @Test
    void rankingDoesNotNarrowTheApprovedSurface() {
        ExecutionContract contract = builder.compile(
                brain(new AgentBrainTest.FakeResolver())
                        .resolve("agent-retail", List.of(CONN), List.of("dom-retail"),
                                "orders only question"));

        assertEquals(2, contract.semanticView().businessObjects().size(),
                "relevance ranking orders the surface; it never removes objects");
        assertTrue(contract.executionBindings().isApproved(CONN, "RETAIL_CORE.STORES"));
    }

    /**
     * Prompt parity for Step 1: resolution runs once, inside AgentBrain, and the exact
     * ResolvedQuestion the resolver produced is what the conversational pipeline receives.
     */
    @Test
    void resolutionHappensExactlyOnceAndIsReturnedVerbatim() {
        AgentBrainTest.FakeResolver resolver = new AgentBrainTest.FakeResolver();
        ResolvedQuestion expected = new ResolvedQuestion("how many stores are in texas",
                List.of(), Set.of("stores"), List.of("texas"),
                List.of(new ResolvedQuestion.LiteralCandidate("stores", "state_province",
                        true, List.of("Texas", "California"))));
        resolver.result = expected;

        ResolvedBusinessModel model = brain(resolver).resolve(
                "agent-retail", List.of(CONN), List.of("dom-retail"), "how many stores are in texas");

        assertEquals(1, resolver.calls, "exactly one resolution per request — never duplicated");
        assertEquals(List.of("dom-retail"), resolver.seenDomainKeys, "resolved against the chat domain scope");
        assertSame(expected, model.resolution(),
                "the conversational pipeline receives the resolver's result verbatim");
        // the derived literal scope travels with it (single owner, Phase 2)
        assertEquals("stores.state_province",
                model.literalScope().get("state_province").qualifiedColumn());
    }

    /** An agentless chat request (no domains, no connections) stays inert and cheap. */
    @Test
    void agentlessChatScopeCompilesAnEmptyContractWithoutResolving() {
        AgentBrainTest.FakeResolver resolver = new AgentBrainTest.FakeResolver();
        AgentBrain agentBrain = new AgentBrain(
                new AgentBrainTest.FakeAssembler(new SemanticModel(List.of(), Map.of(), Map.of())),
                resolver);

        ExecutionContract contract = builder.compile(
                agentBrain.resolve(null, List.of(), List.of(), "hello"));

        assertEquals(0, resolver.calls, "no domain scope ⇒ no resolution, exactly as today");
        assertTrue(contract.isEmpty());
        assertTrue(contract.executionBindings().approvedAssets().isEmpty());
    }
}
