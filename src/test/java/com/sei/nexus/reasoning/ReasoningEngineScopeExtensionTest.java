package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.ExecutionBindings;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.ExecutionContractBuilder;
import com.sei.nexus.agentbrain.PromptAssembler;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.SemanticView;
import com.sei.nexus.enterprise.DataColumn;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler;
import com.sei.nexus.runtime.GovernedSqlRuntime;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReasoningEngine#reason}: a planner metadata request for an object concept selection did
 * not include — validated by exact identity against the approved connections' catalog — makes that
 * object executable for the rest of THIS run, so the planner is never handed a table's columns and
 * then blocked from querying it. Fixtures are domain-neutral. Same hand-rolled-fakes convention as
 * {@link ReasoningEngineMetadataRequestTest}.
 */
class ReasoningEngineScopeExtensionTest {

    private static final String CONN = "conn-1";
    private static final SqlTableReferenceExtractor EXTRACTOR = new SqlTableReferenceExtractor();

    /** Nothing resolved (concept selection picked nothing) but the connection is approved. */
    private static ExecutionContract emptyContract() {
        return new ExecutionContract("ctr-empty", Instant.now(), "agent-1", List.of(CONN), "hash",
                new SemanticView(List.of()), new ExecutionBindings(Map.of(), Map.of(), Set.of()));
    }

    private static ColumnMetadataRequestHandler handlerWithCatalogOfOneObject() {
        EnterpriseMapRepository repo = new EnterpriseMapRepository(null) {
            @Override public List<DataObject> findDataObjectsByConnectionKeys(List<String> connectionKeys) {
                return List.of(new DataObject("obj-items", "domain-1", "items", CONN, "public", "items", "Item",
                        null, null, null, null, null, null, null, null, null, false, "SCANNED", 1,
                        Instant.now(), Instant.now()));
            }
            @Override public List<DataColumn> findColumnsByObject(String objectKey) {
                return List.of(new DataColumn(objectKey + ".id", objectKey, "id", "text", true,
                                null, false, false, false, false, false, null, null,
                                DataColumn.ROLE_INFERRED, Instant.now(), Instant.now()),
                        new DataColumn(objectKey + ".unit_cost", objectKey, "unit_cost", "numeric", true,
                                null, false, false, false, false, false, null, null,
                                DataColumn.ROLE_INFERRED, Instant.now(), Instant.now()));
            }
        };
        return new ColumnMetadataRequestHandler(new PromptContextBuilder(), new PromptAssembler(),
                new EnterpriseSemanticAssembler(repo), new ExecutionContractBuilder(EXTRACTOR));
    }

    private static ReasoningRepository noOpRepository() {
        return new ReasoningRepository(null) {
            @Override public void saveSession(ReasoningSession s) { }
            @Override public void updateSessionStatus(String k, String s, String c, Double conf, Instant at) { }
            @Override public void saveStep(ReasoningStep step) { }
        };
    }

    @Test
    void aMetadataRequestForAnOutOfScopeObjectMakesItQueryableForTheNextStep() {
        AtomicInteger plannerCalls = new AtomicInteger();
        List<ExecutionContract> contractsSeenByRuntime = new ArrayList<>();
        List<String> schemaCtxSeenByPlanner = new ArrayList<>();

        ReasoningPlanner planner = new ReasoningPlanner(null, null) {
            @Override public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                schemaCtxSeenByPlanner.add(schemaCtx);
                int call = plannerCalls.incrementAndGet();
                if (call == 1) {
                    return StepPlan.metadataRequest("need item columns", "r", "items", "columns");
                }
                if (call > 2) return null;   // after its one SQL step the planner says "done"
                return new StepPlan("Top items", "SELECT id FROM public.items LIMIT 5", CONN, "", "r");
            }
        };
        ReasoningEvaluator evaluator = new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                return new EvaluationResult("SUFFICIENT", "ok");
            }
        };
        GovernedSqlRuntime runtime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) {
                contractsSeenByRuntime.add(r.contract());
                return new Outcome(Status.EXECUTED, null, null,
                        List.of(Map.of("id", "i1")), "[{\"id\":\"i1\"}]", 3L, List.of(), null, null, null);
            }
        };
        ReasoningEngine engine = new ReasoningEngine(planner, evaluator,
                new ReasoningEventBus(new ObjectMapper()), noOpRepository(), runtime, new ObjectMapper(),
                handlerWithCatalogOfOneObject());

        ExecutionContract contract = emptyContract();
        engine.reason("q", "q", "rsession", "schema context", "run", "user@test.com",
                false, null, contract, true, "conv", null, null, contract);

        assertEquals(1, contractsSeenByRuntime.size());
        ExecutionContract used = contractsSeenByRuntime.get(0);
        assertTrue(used.executionBindings().isApproved(CONN, EXTRACTOR.canonical("public.items")),
                "the SQL step ran under a contract that approves the object the planner asked for");
        assertTrue(used.columnsByTable(CONN).get("items").contains("unit_cost"));
        assertFalse(contract.executionBindings().isApproved(CONN, EXTRACTOR.canonical("public.items")),
                "the caller's contract is never mutated");
        assertTrue(schemaCtxSeenByPlanner.get(1).contains("now approved for querying"),
                "the planner is told, as a fact in its own context, that it may now query the object");
    }

    @Test
    void whenTheGateIsOffNoContractIsFabricatedAndTheRuntimeStillSeesNull() {
        AtomicInteger plannerCalls = new AtomicInteger();
        List<ExecutionContract> contractsSeenByRuntime = new ArrayList<>();

        ReasoningPlanner planner = new ReasoningPlanner(null, null) {
            @Override public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                int call = plannerCalls.incrementAndGet();
                if (call == 1) {
                    return StepPlan.metadataRequest("need item columns", "r", "items", "columns");
                }
                if (call > 2) return null;
                return new StepPlan("Items", "SELECT id FROM public.items LIMIT 5", CONN, "", "r");
            }
        };
        ReasoningEvaluator evaluator = new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                return new EvaluationResult("SUFFICIENT", "ok");
            }
        };
        GovernedSqlRuntime runtime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) {
                contractsSeenByRuntime.add(r.contract());
                return new Outcome(Status.EXECUTED, null, null,
                        List.of(Map.of("id", "i1")), "[{\"id\":\"i1\"}]", 3L, List.of(), null, null, null);
            }
        };
        ReasoningEngine engine = new ReasoningEngine(planner, evaluator,
                new ReasoningEventBus(new ObjectMapper()), noOpRepository(), runtime, new ObjectMapper(),
                handlerWithCatalogOfOneObject());

        engine.reason("q", "q", "rsession", "schema context", "run", "user@test.com",
                false, null, null, false, "conv", null, null, emptyContract());

        assertNull(contractsSeenByRuntime.get(0), "gate off ⇒ no contract is introduced by the extension");
    }

    @Test
    void aRequestForAnObjectInNoCatalogExtendsNothing() {
        AtomicInteger plannerCalls = new AtomicInteger();
        List<ExecutionContract> contractsSeenByRuntime = new ArrayList<>();

        ReasoningPlanner planner = new ReasoningPlanner(null, null) {
            @Override public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                int call = plannerCalls.incrementAndGet();
                if (call == 1) {
                    return StepPlan.metadataRequest("x", "r", "does_not_exist", "columns");
                }
                if (call > 2) return null;
                return new StepPlan("Items", "SELECT id FROM public.items LIMIT 5", CONN, "", "r");
            }
        };
        ReasoningEvaluator evaluator = new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                return new EvaluationResult("SUFFICIENT", "ok");
            }
        };
        GovernedSqlRuntime runtime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) {
                contractsSeenByRuntime.add(r.contract());
                return new Outcome(Status.EXECUTED, null, null, List.of(), "[]", 1L, List.of(), null, null, null);
            }
        };
        ReasoningEngine engine = new ReasoningEngine(planner, evaluator,
                new ReasoningEventBus(new ObjectMapper()), noOpRepository(), runtime, new ObjectMapper(),
                handlerWithCatalogOfOneObject());

        ExecutionContract contract = emptyContract();
        ReasoningEngine.ReasoningResult result = engine.reason("q", "q", "rsession", "schema context", "run",
                "user@test.com", false, null, contract, true, "conv", null, null, contract);

        assertEquals("METADATA_UNAVAILABLE", result.evidence().getSteps().get(0).outcome());
        assertFalse(contractsSeenByRuntime.get(0).executionBindings()
                .isApproved(CONN, EXTRACTOR.canonical("public.items")),
                "an unvalidated request extends nothing");
    }
}
