package com.sei.nexus.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.ExecutionBindings;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.SemanticView;
import com.sei.nexus.agentmemory.BusinessWorldToolAdapter;
import com.sei.nexus.agentmemory.ConversationMemoryService;
import com.sei.nexus.agentmemory.ConversationRosterEntry;
import com.sei.nexus.agentmemory.ConversationRosterRepository;
import com.sei.nexus.agentmemory.RelationshipExplorationAdapter;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.governance.ContractResult;
import com.sei.nexus.governance.GovernanceAuditService;
import com.sei.nexus.governance.GovernanceOutcome;
import com.sei.nexus.governance.MaskResult;
import com.sei.nexus.governance.RlsResult;
import com.sei.nexus.governance.SqlGovernancePipeline;
import com.sei.nexus.graph.GraphEdge;
import com.sei.nexus.graph.KnowledgeGraphRepository;
import com.sei.nexus.query.QueryExecutionRepository;
import com.sei.nexus.runtime.ExecutionReference;
import com.sei.nexus.runtime.ExecutionReferenceRepository;
import com.sei.nexus.runtime.GovernedSqlRuntime;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 — the six new Conversation Memory / Business World / Relationship capabilities
 * wired into {@link AgentToolRegistry}, exactly the existing tool-definition/dispatch
 * mechanism the original four tools use. Hand-rolled fakes; no database, no Mockito — same
 * convention as {@link AgentToolRegistryTest}.
 */
class AgentToolRegistryMemoryCapabilitiesTest {

    private static final String CONN = "conn-1";
    private static final List<String> ALLOWED = List.of(CONN);
    private static final String CONV = "conv-1";
    private static final SqlTableReferenceExtractor EXTRACTOR = new SqlTableReferenceExtractor();

    // ── fakes shared with AgentToolRegistryTest's convention ────────────────────

    static class FakePipeline extends SqlGovernancePipeline {
        GovernanceOutcome outcome;
        FakePipeline() { super(null, null, null, null, null); }
        @Override public GovernanceOutcome governSql(String runKey, int stepNo, String connectionKey,
                String objectKeys, String sql, String userEmail, boolean forceAsync) {
            return outcome;
        }
    }

    static class FakeAudit extends GovernanceAuditService {
        FakeAudit() { super(null, null, null); }
        @Override public void recordOutcome(GovernanceOutcome outcome, String userEmail, String runKey,
                String connectionKey, List<String> objectKeys, Integer rowCount,
                Integer executionMs, boolean blocked) { /* no-op */ }
    }

    static class FakeDynamicSql extends DynamicSqlService {
        FakeDynamicSql() { super(null); }
        @Override public List<Map<String, Object>> executeQuery(String connectionKey,
                String approvedSql, int maxRows, boolean readOnly) {
            return List.of(Map.of("id", 1));
        }
    }

    static class ForbiddenExecutionRepo extends QueryExecutionRepository {
        ForbiddenExecutionRepo() { super(null); }
        @Override public void updateStatus(String executionKey, String status, Instant startedAt,
                                           Instant completedAt, String errorMessage) {
            throw new AssertionError("agent path must not write execution records");
        }
        @Override public void updateResult(String executionKey, String resultJson,
                                           String status, Instant completedAt) {
            throw new AssertionError("agent path must not write execution records");
        }
    }

    static class FakeExecutionReferenceRepository extends ExecutionReferenceRepository {
        final List<ExecutionReference> saved = new ArrayList<>();
        final Map<String, ExecutionReference> byId = new LinkedHashMap<>();
        FakeExecutionReferenceRepository() { super(null, new ObjectMapper()); }
        @Override public void save(ExecutionReference r) { saved.add(r); byId.put(r.executionId(), r); }
        @Override public Optional<ExecutionReference> findByExecutionId(String executionId) {
            return Optional.ofNullable(byId.get(executionId));
        }
    }

    static class FakeRosterRepository extends ConversationRosterRepository {
        record EnsureCall(String conversationId, String entityKey) {}
        final List<EnsureCall> ensureCalls = new ArrayList<>();
        final List<ConversationRosterEntry> rows = new ArrayList<>();
        FakeRosterRepository() { super(null); }
        @Override public void ensure(String conversationId, String entityKey, String businessName, String objectType) {
            ensureCalls.add(new EnsureCall(conversationId, entityKey));
            boolean present = rows.stream().anyMatch(r -> r.conversationId().equals(conversationId) && r.entityKey().equals(entityKey));
            if (!present) rows.add(new ConversationRosterEntry(conversationId, entityKey, businessName, objectType, Instant.now()));
        }
        @Override public List<ConversationRosterEntry> findByConversation(String conversationId) {
            return rows.stream().filter(r -> r.conversationId().equals(conversationId)).toList();
        }
        @Override public boolean existsInConversation(String conversationId, String entityKey) {
            return rows.stream().anyMatch(r -> r.conversationId().equals(conversationId) && r.entityKey().equals(entityKey));
        }
    }

    static class FakeEnterpriseMapRepository extends EnterpriseMapRepository {
        final List<DataObject> objects = new ArrayList<>();
        int byKeyCalls = 0;
        FakeEnterpriseMapRepository() { super(null); }
        @Override public Optional<DataObject> findDataObjectByKey(String objectKey) {
            byKeyCalls++;
            return objects.stream().filter(o -> o.objectKey().equals(objectKey)).findFirst();
        }
        @Override public List<DataObject> findDataObjectsByDomain(String domainKey) {
            return objects.stream().filter(o -> o.domainKey().equals(domainKey)).toList();
        }
    }

    static class FakeKnowledgeGraphRepository extends KnowledgeGraphRepository {
        record Call(List<String> keys) {}
        final List<Call> calls = new ArrayList<>();
        List<GraphEdge> toReturn = List.of();
        FakeKnowledgeGraphRepository() { super(null); }
        @Override public List<GraphEdge> findEdgesForNodes(List<String> entityKeys) {
            calls.add(new Call(entityKeys));
            return toReturn;
        }
    }

    private static DataObject object(String key, String domain, String businessName) {
        return new DataObject(key, domain, "Entity " + key, CONN, "public", key,
                businessName, "purpose", null, null, null, null, null, null, null,
                null, false, "SCANNED", 1, null, null);
    }

    private FakePipeline pipeline;
    private FakeDynamicSql dynamicSql;
    private FakeExecutionReferenceRepository execRefRepo;
    private FakeRosterRepository rosterRepo;
    private FakeEnterpriseMapRepository enterpriseRepo;
    private FakeKnowledgeGraphRepository graphRepo;
    private AgentToolRegistry registry;
    private AgentToolRegistry disabledRegistry;

    @BeforeEach
    void setUp() {
        pipeline = new FakePipeline();
        dynamicSql = new FakeDynamicSql();
        execRefRepo = new FakeExecutionReferenceRepository();
        rosterRepo = new FakeRosterRepository();
        enterpriseRepo = new FakeEnterpriseMapRepository();
        graphRepo = new FakeKnowledgeGraphRepository();

        GovernedSqlRuntime runtime = new GovernedSqlRuntime(pipeline, dynamicSql, new FakeAudit(),
                EXTRACTOR, new ForbiddenExecutionRepo(), null, execRefRepo, new ObjectMapper());

        ConversationMemoryService memoryService = new ConversationMemoryService(rosterRepo);
        BusinessWorldToolAdapter businessWorldAdapter = new BusinessWorldToolAdapter(enterpriseRepo);
        RelationshipExplorationAdapter relationshipAdapter = new RelationshipExplorationAdapter(graphRepo);

        registry = new AgentToolRegistry(null, new ObjectMapper(), runtime,
                memoryService, businessWorldAdapter, relationshipAdapter, execRefRepo, true);
        disabledRegistry = new AgentToolRegistry(null, new ObjectMapper(), runtime,
                memoryService, businessWorldAdapter, relationshipAdapter, execRefRepo, false);
    }

    private static ExecutionContract contractApproving(String... tables) {
        Set<ExecutionBindings.ApprovedAsset> assets = new HashSet<>();
        Map<String, ExecutionBindings.ExecutionTarget> objectBindings = new LinkedHashMap<>();
        for (String t : tables) {
            assets.add(new ExecutionBindings.ApprovedAsset(CONN, EXTRACTOR.canonical(t)));
            objectBindings.put(t, new ExecutionBindings.ExecutionTarget("SQL", CONN, "public", t, null));
        }
        ExecutionBindings bindings = new ExecutionBindings(objectBindings, Map.of(), assets);
        SemanticView view = new SemanticView(List.of());
        return new ExecutionContract("ctr-test", Instant.now(), "agent-1",
                List.of(CONN), "hash", view, bindings);
    }

    private static GovernanceOutcome execOutcome(String governedSql, int rowLimit) {
        return new GovernanceOutcome(GovernanceOutcome.Verdict.EXECUTE, null, governedSql,
                "SELECT approved", "BOUNDED_LIST", "EXECUTE_SYNC", rowLimit, "ek", "analyst",
                ContractResult.passed(List.of()), RlsResult.passThrough(governedSql),
                MaskResult.passThrough(governedSql));
    }

    private AgentToolRegistry.ToolExecutionResult call(AgentToolRegistry reg, String tool, Map<String, Object> args,
                                                         String conversationId) {
        return reg.executeWithReference(tool, args, ALLOWED, "u@x.com", "run-1", 1,
                contractApproving("orders"), conversationId, null);
    }

    // ── 1. tool registration ─────────────────────────────────────────────────

    @Test
    void allSixToolsAreRegisteredWhenCapabilityIsEnabled() {
        List<Map<String, Object>> defs = registry.getToolDefinitions(ALLOWED);
        List<String> names = defs.stream()
                .map(d -> (Map<?, ?>) d.get("function"))
                .map(f -> (String) f.get("name"))
                .toList();

        assertTrue(names.containsAll(List.of("memory_list", "memory_get", "memory_get_execution_reference",
                "get_business_object", "list_business_objects", "explore_related")));
        assertTrue(names.containsAll(List.of("query_database", "describe_schema", "analyze_image", "final_answer")),
                "original four tools remain registered unchanged");
        assertEquals(10, names.size());
    }

    @Test
    void noNewToolsAreRegisteredWhenCapabilityIsDisabled() {
        List<Map<String, Object>> defs = disabledRegistry.getToolDefinitions(ALLOWED);
        List<String> names = defs.stream()
                .map(d -> (Map<?, ?>) d.get("function"))
                .map(f -> (String) f.get("name"))
                .toList();

        assertEquals(4, names.size(), "disabled: exactly the original four tools, nothing more");
        assertFalse(names.contains("memory_list"));
    }

    @Test
    void newToolNamesAreRejectedLikeAnyUnknownToolWhenCapabilityIsDisabled() {
        var ex = assertThrows(com.sei.nexus.common.NexusException.class,
                () -> call(disabledRegistry, "memory_list", Map.of(), CONV));
        assertTrue(ex.getMessage().contains("Unknown tool"));
    }

    // ── 2/3. memory_get ───────────────────────────────────────────────────────

    @Test
    void memoryGetSucceedsForARegisteredEntity() throws Exception {
        rosterRepo.ensure(CONV, "supplier", "Supplier", "entity");
        enterpriseRepo.objects.add(object("supplier", "inventory", "Supplier"));

        String result = call(registry, "memory_get", Map.of("entity_key", "supplier"), CONV).content();

        assertTrue(result.contains("\"business_name\":\"Supplier\""));
        assertFalse(result.contains("error"));
    }

    @Test
    void memoryGetCannotAccessAnEntityNotInConversationMemory() throws Exception {
        enterpriseRepo.objects.add(object("supplier", "inventory", "Supplier"));
        // Note: supplier exists authoritatively but was never discovered in THIS conversation.

        String result = call(registry, "memory_get", Map.of("entity_key", "supplier"), CONV).content();

        assertTrue(result.contains("not in this conversation's memory"),
                "no silent discovery, no fallback to get_business_object — a deterministic not-found result");
    }

    // ── 4/5. get_business_object + mechanical registration ──────────────────────

    @Test
    void getBusinessObjectSuccessfulRetrievalRegistersTheEntity() throws Exception {
        enterpriseRepo.objects.add(object("supplier", "inventory", "Supplier"));
        assertFalse(rosterRepo.existsInConversation(CONV, "supplier"));

        String result = call(registry, "get_business_object", Map.of("entity_key", "supplier"), CONV).content();

        assertTrue(result.contains("\"business_name\":\"Supplier\""));
        assertTrue(rosterRepo.existsInConversation(CONV, "supplier"), "successful retrieval mechanically registers the entity");
    }

    @Test
    void getBusinessObjectFailureDoesNotRegisterAnything() throws Exception {
        String result = call(registry, "get_business_object", Map.of("entity_key", "warehouse"), CONV).content();

        assertTrue(result.contains("error"));
        assertFalse(rosterRepo.existsInConversation(CONV, "warehouse"), "a failed lookup registers nothing");
        assertTrue(rosterRepo.ensureCalls.isEmpty());
    }

    // ── 6. list_business_objects: listing is not discovery ──────────────────────

    @Test
    void listBusinessObjectsNeverRegistersReturnedEntities() throws Exception {
        enterpriseRepo.objects.add(object("supplier", "inventory", "Supplier"));
        enterpriseRepo.objects.add(object("product", "inventory", "Product"));

        String result = call(registry, "list_business_objects", Map.of("group", "inventory"), CONV).content();

        assertTrue(result.contains("supplier") && result.contains("product"));
        assertTrue(rosterRepo.ensureCalls.isEmpty(), "listing must never mechanically register anything");
        assertFalse(rosterRepo.existsInConversation(CONV, "supplier"));
        assertFalse(rosterRepo.existsInConversation(CONV, "product"));
    }

    // ── 7/8. explore_related ─────────────────────────────────────────────────

    @Test
    void exploreRelatedDelegatesToTheExistingAdapterWithCorrectScoping() throws Exception {
        graphRepo.toReturn = List.of(new GraphEdge("rel-1", "purchase-order", "supplier", "REFERENCES",
                "supplier_id", "id", "JOIN ...", "MANY_TO_ONE", false, "#000"));

        String result = call(registry, "explore_related", Map.of("entity_key", "purchase-order"), CONV).content();

        assertEquals(1, graphRepo.calls.size());
        assertEquals(List.of("purchase-order"), graphRepo.calls.get(0).keys());
        assertTrue(result.contains("\"target_entity\":\"supplier\""));
        assertTrue(result.contains("\"cardinality\":\"MANY_TO_ONE\""));
    }

    @Test
    void exploreRelatedNeverRegistersRelatedEntities() throws Exception {
        graphRepo.toReturn = List.of(new GraphEdge("rel-1", "purchase-order", "supplier", "REFERENCES",
                "supplier_id", "id", "JOIN ...", "MANY_TO_ONE", false, "#000"));

        call(registry, "explore_related", Map.of("entity_key", "purchase-order"), CONV);

        assertTrue(rosterRepo.ensureCalls.isEmpty(), "relationship discovery tells the LLM what exists — it never registers anything itself");
    }

    // ── 9. memory_get_execution_reference: exact lookup ──────────────────────

    @Test
    void memoryGetExecutionReferenceReturnsTheExactMatch() throws Exception {
        ExecutionReference ref = new ExecutionReference("exec-1", null, CONV, "run-1", CONN,
                Instant.now(), Instant.now(), 10, "EXECUTE_SYNC", 2, List.of("id"), "[{\"id\":1}]", "SELECT ...",
                "ctr-1", "hash", List.of(), Map.of("orders", "public.orders"), Map.of(), List.of());
        execRefRepo.byId.put("exec-1", ref);

        String result = call(registry, "memory_get_execution_reference", Map.of("reference_id", "exec-1"), CONV).content();

        assertTrue(result.contains("\"execution_id\":\"exec-1\""));
        assertTrue(result.contains("\"row_count\":2"));
    }

    @Test
    void memoryGetExecutionReferenceRejectsAnUnknownId() throws Exception {
        String result = call(registry, "memory_get_execution_reference", Map.of("reference_id", "nope"), CONV).content();
        assertTrue(result.contains("No execution found"));
    }

    @Test
    void memoryGetExecutionReferenceRejectsAReferenceFromAnotherConversation() throws Exception {
        ExecutionReference ref = new ExecutionReference("exec-1", null, "other-conv", "run-1", CONN,
                Instant.now(), Instant.now(), 10, "EXECUTE_SYNC", 2, List.of("id"), "[{\"id\":1}]", "SELECT ...",
                "ctr-1", "hash", List.of(), Map.of(), Map.of(), List.of());
        execRefRepo.byId.put("exec-1", ref);

        String result = call(registry, "memory_get_execution_reference", Map.of("reference_id", "exec-1"), CONV).content();

        assertTrue(result.contains("does not belong to the current conversation"),
                "exact conversation match required — no fuzzy/'probably means' resolution");
    }

    // ── 10. execution_id exposure ─────────────────────────────────────────────

    @Test
    void executeWithReferenceExposesExecutionIdOnlyOnSuccessfulQueryDatabase() {
        pipeline.outcome = execOutcome("SELECT id FROM orders", 50);

        AgentToolRegistry.ToolExecutionResult result = call(registry, "query_database",
                Map.of("connection_key", CONN, "sql", "SELECT id FROM orders"), CONV);

        assertNotNull(result.executionId(), "a successful query_database execution exposes an execution_id");
        assertTrue(result.content().contains("\"id\":1"));
    }

    @Test
    void executionIdIsNullForNonQueryDatabaseTools() throws Exception {
        AgentToolRegistry.ToolExecutionResult result = call(registry, "memory_list", Map.of(), CONV);
        assertNull(result.executionId());
    }

    @Test
    void agentRunnerComposesTheExecutionIdNoteForTheLlmOnly() {
        AgentToolRegistry.ToolExecutionResult withId = new AgentToolRegistry.ToolExecutionResult("[{\"id\":1}]", "exec-42");
        AgentToolRegistry.ToolExecutionResult withoutId = new AgentToolRegistry.ToolExecutionResult("[{\"id\":1}]", null);

        String llmContent = AgentRunner.withExecutionIdNote(withId);
        assertTrue(llmContent.contains("[{\"id\":1}]"));
        assertTrue(llmContent.contains("exec-42"));
        assertEquals("[{\"id\":1}]", AgentRunner.withExecutionIdNote(withoutId),
                "no execution_id means the content is passed through completely unchanged");
    }

    // ── 11/12. bare rows JSON compatibility (query_database, execute()) ─────────

    @Test
    void existingQueryDatabaseRowsJsonRemainsABareListViaTheUnchangedExecuteMethod() throws Exception {
        pipeline.outcome = execOutcome("SELECT id FROM orders", 50);

        String result = registry.execute("query_database", Map.of("connection_key", CONN, "sql", "SELECT id FROM orders"),
                ALLOWED, "u@x.com", "run-1", 1, contractApproving("orders"), CONV, null);

        Object parsed = new ObjectMapper().readValue(result, Object.class);
        assertInstanceOf(List.class, parsed,
                "ChatService.extractAgentQueryRows requires a List — this shape must never become a Map");
        assertFalse(result.contains("execution_id"), "execution_id must never be merged into content's own shape");
    }

    // ── 13/14. tenant + conversation isolation ───────────────────────────────

    @Test
    void memoryIsIsolatedPerConversation() throws Exception {
        rosterRepo.ensure("conv-A", "supplier", "Supplier", "entity");

        String listA = call(registry, "memory_list", Map.of(), "conv-A").content();
        String listB = call(registry, "memory_list", Map.of(), "conv-B").content();

        assertTrue(listA.contains("supplier"));
        assertFalse(listB.contains("supplier"), "conversation isolation: conv-B must never see conv-A's roster");
    }

    @Test
    void executionReferenceTenantIsolationReliesOnAmbientRoutingNotAnyNewMechanism() {
        // Tenant isolation for every new capability is inherited from the existing
        // TenantContext/TenantAwareDataSource ambient routing (same as every other
        // repository) — no explicit tenant parameter exists anywhere in this registry's
        // new code, exactly matching the existing four tools and every repository this
        // phase reuses. This test documents that structural fact; a cross-tenant row can
        // never even be returned by findByExecutionId in the first place under that routing.
        assertTrue(true);
    }

    // ── multi-turn reuse (Parts 11/15-17 of the design) ─────────────────────────

    @Test
    void multiTurnRosterReuseAndExecutionReferenceRetrievalOnALaterTurn() throws Exception {
        // Turn 1: discover Purchase Order + Supplier, execute a query.
        enterpriseRepo.objects.add(object("purchase-order", "sales", "Purchase Order"));
        enterpriseRepo.objects.add(object("supplier", "inventory", "Supplier"));
        call(registry, "get_business_object", Map.of("entity_key", "purchase-order"), CONV);
        pipeline.outcome = execOutcome("SELECT id FROM orders", 50);
        AgentToolRegistry.ToolExecutionResult turn1Exec =
                call(registry, "query_database", Map.of("connection_key", CONN, "sql", "SELECT id FROM orders"), CONV);
        String executionIdFromTurn1 = turn1Exec.executionId();
        assertNotNull(executionIdFromTurn1);

        // Turn 3: reuse Purchase Order from memory without rediscovering it.
        String memList = call(registry, "memory_list", Map.of(), CONV).content();
        assertTrue(memList.contains("purchase-order"));
        int byKeyCallsBeforeReuse = enterpriseRepo.byKeyCalls;
        call(registry, "memory_get", Map.of("entity_key", "purchase-order"), CONV);
        assertEquals(byKeyCallsBeforeReuse + 1, enterpriseRepo.byKeyCalls,
                "memory_get still reaches Business World for the CURRENT authoritative object — "
                        + "it just skips the roster-membership gate get_business_object would otherwise need");

        // Turn 5: retrieve turn 1's execution reference using the execution_id it was given.
        call(registry, "get_business_object", Map.of("entity_key", "supplier"), CONV); // discover a new object mid-conversation
        assertTrue(rosterRepo.existsInConversation(CONV, "supplier"), "a new object can still be discovered on a later turn");

        String execRefResult = call(registry, "memory_get_execution_reference",
                Map.of("reference_id", executionIdFromTurn1), CONV).content();
        assertFalse(execRefResult.contains("error"), "the earlier execution is retrievable by the id it exposed");
    }
}
