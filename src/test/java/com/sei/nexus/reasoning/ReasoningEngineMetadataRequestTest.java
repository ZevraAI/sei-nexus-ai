package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.ExecutionBindings;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.PromptAssembler;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.SemanticView;
import com.sei.nexus.runtime.GovernedSqlRuntime;
import com.sei.nexus.semanticmodel.AttributeRole;
import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Missing-Column Metadata Request — {@link ReasoningEngine#reason}'s loop-level handling of
 * {@link ReasoningPlanner.StepPlan#isMetadataRequest()}: the extension to the existing
 * planner/evaluate iteration, not a new orchestration mechanism (see {@link
 * ColumnMetadataRequestHandler} for the validation/retrieval unit tests, and {@link
 * ReasoningPlannerMetadataRequestTest} for the JSON-parsing unit tests). Same hand-rolled-fakes
 * convention as {@link ReasoningEngineClarificationTest}.
 */
class ReasoningEngineMetadataRequestTest {

    private static final String CONN = "conn-1";

    /** Two resolved objects — "order_lines" (with a measure column) and "orders". */
    private static ExecutionContract resolvedContract() {
        Map<String, ExecutionBindings.ExecutionTarget> attributeBindings = new java.util.LinkedHashMap<>();
        List<BusinessAttribute> lineAttrs = new ArrayList<>();
        for (String c : List.of("id", "ordered_qty", "order_id")) {
            lineAttrs.add(new BusinessAttribute("line." + c, c, AttributeRole.ATTRIBUTE));
            attributeBindings.put("line." + c, new ExecutionBindings.ExecutionTarget("SQL", CONN, "public", "order_lines", c));
        }
        List<BusinessAttribute> orderAttrs = new ArrayList<>();
        for (String c : List.of("id", "status")) {
            orderAttrs.add(new BusinessAttribute("order." + c, c, AttributeRole.ATTRIBUTE));
            attributeBindings.put("order." + c, new ExecutionBindings.ExecutionTarget("SQL", CONN, "public", "orders", c));
        }
        BusinessObject lineItems = new BusinessObject("obj-line-items", "Order Line Item", null, null, lineAttrs, List.of());
        BusinessObject orders = new BusinessObject("obj-orders", "Order", null, null, orderAttrs, List.of());

        Map<String, ExecutionBindings.ExecutionTarget> objectBindings = Map.of(
                "obj-line-items", new ExecutionBindings.ExecutionTarget("SQL", CONN, "public", "order_lines", null),
                "obj-orders", new ExecutionBindings.ExecutionTarget("SQL", CONN, "public", "orders", null));
        Set<ExecutionBindings.ApprovedAsset> assets = Set.of(
                new ExecutionBindings.ApprovedAsset(CONN, "ORDER_LINES"),
                new ExecutionBindings.ApprovedAsset(CONN, "ORDERS"));

        return new ExecutionContract("ctr-1", Instant.now(), "agent-1", List.of(CONN), "hash",
                new SemanticView(List.of(lineItems, orders)),
                new ExecutionBindings(objectBindings, attributeBindings, assets));
    }

    private static ReasoningRepository noopRepository() {
        return new ReasoningRepository(null) {
            @Override public void saveSession(ReasoningSession s) { }
            @Override public void updateSessionStatus(String sessionKey, String status,
                    String conclusion, Double confidence, java.time.Instant concludedAt) { }
            @Override public void saveStep(ReasoningStep step) { }
        };
    }

    /** Empty Enterprise Map fallback catalog — proves the circular-dependency fallback never
     *  turns a genuinely-nonexistent object into a false positive for these tests. */
    private static ReasoningEngine engine(ReasoningPlanner planner, ReasoningEvaluator evaluator,
                                          GovernedSqlRuntime runtime) {
        return engineWithCatalog(planner, evaluator, runtime, List.of(), Map.of());
    }

    private static ReasoningEngine engineWithCatalog(ReasoningPlanner planner, ReasoningEvaluator evaluator,
            GovernedSqlRuntime runtime, List<com.sei.nexus.enterprise.DataObject> catalogObjects,
            Map<String, List<com.sei.nexus.enterprise.DataColumn>> catalogColumns) {
        com.sei.nexus.enterprise.EnterpriseMapRepository fakeRepo =
                new com.sei.nexus.enterprise.EnterpriseMapRepository(null) {
            @Override public List<com.sei.nexus.enterprise.DataObject> findDataObjectsByConnectionKeys(
                    List<String> connectionKeys) {
                return catalogObjects;
            }
            @Override public List<com.sei.nexus.enterprise.DataColumn> findColumnsByObject(String objectKey) {
                return catalogColumns.getOrDefault(objectKey, List.of());
            }
        };
        return new ReasoningEngine(planner, evaluator, new ReasoningEventBus(new ObjectMapper()),
                noopRepository(), runtime, new ObjectMapper(),
                new ColumnMetadataRequestHandler(new PromptContextBuilder(), new PromptAssembler(),
                        new com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler(fakeRepo)));
    }

    private static com.sei.nexus.enterprise.DataObject catalogObject(String objectKey, String businessName, String table) {
        return new com.sei.nexus.enterprise.DataObject(objectKey, "domain-1", table, CONN, "public", table,
                businessName, null, null, null, null, null, null, null, null, null, false, "SCANNED", 1,
                java.time.Instant.now(), java.time.Instant.now());
    }

    private static com.sei.nexus.enterprise.DataColumn catalogColumn(String objectKey, String columnName) {
        return new com.sei.nexus.enterprise.DataColumn(objectKey + "." + columnName, objectKey, columnName,
                "text", true, null, false, false, false, false, false, null, null,
                com.sei.nexus.enterprise.DataColumn.ROLE_INFERRED, java.time.Instant.now(), java.time.Instant.now());
    }

    // ── Scenario: agent can request columns for an already-resolved object, receives them,
    //     and continues planning with them ────────────────────────────────────────────────────

    @Test
    void agentRequestsColumnsReceivesThemAndContinuesPlanning() {
        AtomicInteger plannerCalls = new AtomicInteger(0);
        List<String> seenSchemaCtx = new ArrayList<>();

        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                int call = plannerCalls.incrementAndGet();
                seenSchemaCtx.add(schemaCtx);
                if (call == 1) {
                    return StepPlan.metadataRequest("Need line item columns",
                            "columns were omitted from the schema context", "order_lines", "columns");
                }
                // Second call: the real column list must now be visible in schemaCtx.
                assertTrue(schemaCtx.contains("ordered_qty"),
                        "the planner's second call must see the retrieved column metadata");
                return new StepPlan("Sum ordered quantity", "SELECT SUM(ordered_qty) FROM order_lines",
                        CONN, "", "now that columns are known");
            }
        };

        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                return new EvaluationResult("SUFFICIENT", "answered");
            }
        };

        AtomicInteger executions = new AtomicInteger(0);
        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) {
                executions.incrementAndGet();
                return new Outcome(Status.EXECUTED, null, null,
                        List.of(Map.of("sum", 42)), "[{\"sum\":42}]", 5L, List.of(), null, null, null);
            }
        };

        ReasoningEngine.ReasoningResult result = engine(fakePlanner, fakeEvaluator, fakeRuntime).reason(
                "how many of each item did we order", "how many of each item did we order",
                "rsession", "schema context", "run-1", "user@test.com", false, null, null, false,
                "conv-1", null, null, resolvedContract());

        assertEquals(2, plannerCalls.get(), "one metadata-request call, one SQL-planning call");
        assertEquals(1, executions.get(), "SQL executes only after columns were received");
        assertFalse(result.queryData().isEmpty());

        // The metadata step's own evidence must describe a metadata retrieval, never an empty
        // query — see EvidenceStoreTest's addMetadataStep coverage for the unit-level proof.
        EvidenceStore.StepEvidence metadataStep = result.evidence().getSteps().get(0);
        assertEquals("METADATA_RETRIEVED", metadataStep.outcome());
        assertNull(metadataStep.evaluatorDecision(),
                "a metadata retrieval never reached evaluation — no sufficiency verdict applies");
        assertFalse(metadataStep.rowSummary().contains("Query returned"));
        assertTrue(metadataStep.rowSummary().contains("column"));
    }

    // ── Scenario: Agent Brain can request metadata for an object not yet present in the resolved
    //     ExecutionContract but present in the authoritative Enterprise Map — the circular-
    //     dependency fix — and can then produce SQL using the newly supplied real columns ───────

    @Test
    void agentRequestsMetadataForAnObjectNotYetInExecutionContextButKnownToEnterpriseMetadata() {
        AtomicInteger plannerCalls = new AtomicInteger(0);

        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                int call = plannerCalls.incrementAndGet();
                if (call == 1) {
                    // "catalog_entries" is NOT part of resolvedContract()'s resolved object set —
                    // e.g. Concept-Scoped Narrowing never selected it — but it IS a real object
                    // on the same already-approved connection, per the Enterprise Map fixture.
                    return StepPlan.metadataRequest("Need catalog columns",
                            "the object exists but was never resolved into this request's scope",
                            "catalog_entries", "columns");
                }
                assertTrue(schemaCtx.contains("label"),
                        "the object's real column, retrieved via the enterprise-metadata "
                                + "fallback, must reach the next planner call");
                return new StepPlan("Use the retrieved column", "SELECT label FROM catalog_entries",
                        CONN, "", "now that columns are known");
            }
        };
        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                return new EvaluationResult("SUFFICIENT", "answered");
            }
        };
        AtomicInteger executions = new AtomicInteger(0);
        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) {
                executions.incrementAndGet();
                assertEquals("SELECT label FROM catalog_entries", r.sql(),
                        "the SQL Agent Brain generates uses the real column it received, never a guess");
                return new Outcome(Status.EXECUTED, null, null,
                        List.of(Map.of("label", "x")), "[{\"label\":\"x\"}]", 5L, List.of(), null, null, null);
            }
        };

        ReasoningEngine.ReasoningResult result = engineWithCatalog(fakePlanner, fakeEvaluator, fakeRuntime,
                List.of(catalogObject("obj-catalog", "Catalog Entries", "catalog_entries")),
                Map.of("obj-catalog", List.of(catalogColumn("obj-catalog", "id"), catalogColumn("obj-catalog", "label"))))
                .reason("q", "q", "rsession", "schema context", "run-1", "user@test.com", false, null, null, false,
                        "conv-1", null, null, resolvedContract());

        assertEquals(2, plannerCalls.get());
        assertEquals(1, executions.get());
        assertFalse(result.queryData().isEmpty());
    }

    // ── Scenario: requesting an unresolved/unapproved object is rejected ──────────────────────

    @Test
    void requestingAnObjectOutsideTheResolvedSetIsRejectedAndPlanningContinues() {
        AtomicInteger plannerCalls = new AtomicInteger(0);

        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                int call = plannerCalls.incrementAndGet();
                if (call == 1) {
                    return StepPlan.metadataRequest("Need a table's columns",
                            "r", "some_table_never_resolved", "columns");
                }
                // The rejection must be visible in the evidence text handed to the next call —
                // Java never silently retries or invents columns for an unapproved object.
                assertTrue(evidence.buildContextForLlm().contains("some_table_never_resolved"));
                return null; // planner gives up — done
            }
        };

        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                fail("no SQL ever executed — the evaluator must never be consulted");
                return null;
            }
        };

        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) {
                fail("must never execute SQL for a rejected metadata request");
                return null;
            }
        };

        ReasoningEngine.ReasoningResult result = engine(fakePlanner, fakeEvaluator, fakeRuntime).reason(
                "question", "question", "rsession", "schema context", "run-1", "user@test.com",
                false, null, null, false, "conv-1", null, null, resolvedContract());

        assertEquals(2, plannerCalls.get());
        assertTrue(result.queryData().isEmpty());
    }

    @Test
    void rejectionMessageRedirectsThePlannerToTheApprovedSchemaIdentityNotJavaResolution() {
        // The rejection must tell Agent Brain WHERE to find the correct identity (re-check
        // "Approved schema") rather than merely stating the rejection — Java performs no
        // resolution/substitution of its own; it only points the model back at its own context.
        AtomicInteger plannerCalls = new AtomicInteger(0);
        java.util.concurrent.atomic.AtomicReference<String> secondCallSchemaCtx = new java.util.concurrent.atomic.AtomicReference<>();

        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                int call = plannerCalls.incrementAndGet();
                if (call == 1) {
                    return StepPlan.metadataRequest("Need a business object's columns",
                            "r", "concept", "columns");
                }
                secondCallSchemaCtx.set(evidence.buildContextForLlm());
                return null;
            }
        };
        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) { return null; }
        };
        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) { fail("must never execute"); return null; }
        };

        engine(fakePlanner, fakeEvaluator, fakeRuntime).reason(
                "question", "question", "rsession", "schema context", "run-1", "user@test.com",
                false, null, null, false, "conv-1", null, null, resolvedContract());

        String transcript = secondCallSchemaCtx.get();
        assertNotNull(transcript);
        assertTrue(transcript.contains("Approved schema"),
                "the rejection must redirect the planner to re-check \"Approved schema\"");
        assertTrue(transcript.toLowerCase(java.util.Locale.ROOT).contains("retry with that exact identity"),
                "the rejection must explicitly invite a retry with the correct identity, not just "
                        + "state that the request failed");
        assertFalse(transcript.contains("<the exact table name"),
                "the rejection message must be a real, filled-in string, never a template artifact");
    }

    @Test
    void metadataRequestIsRejectedWhenNoResolvedContractIsAvailable() {
        // Gate-off migration mode: resolvedObjects is null — nothing to validate against, so
        // every metadata request is rejected, never guessed at.
        AtomicInteger plannerCalls = new AtomicInteger(0);
        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                int call = plannerCalls.incrementAndGet();
                if (call == 1) return StepPlan.metadataRequest("d", "r", "order_lines", "columns");
                assertFalse(schemaCtx.contains("ordered_qty"), "nothing to validate against ⇒ nothing retrieved");
                return null;
            }
        };
        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) { return null; }
        };
        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) { fail("must not execute"); return null; }
        };

        engine(fakePlanner, fakeEvaluator, fakeRuntime).reason(
                "q", "q", "rsession", "schema context", "run-1", "user@test.com",
                false, null, null, false, "conv-1", null, null, null);

        assertEquals(2, plannerCalls.get());
    }

    // ── Scenario: multiple metadata requests in the same investigation ────────────────────────

    @Test
    void multipleMetadataRequestsAcrossDifferentObjectsBothSucceed() {
        AtomicInteger plannerCalls = new AtomicInteger(0);
        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                int call = plannerCalls.incrementAndGet();
                if (call == 1) return StepPlan.metadataRequest("d1", "r1", "order_lines", "columns");
                if (call == 2) {
                    assertTrue(schemaCtx.contains("ordered_qty"));
                    return StepPlan.metadataRequest("d2", "r2", "orders", "columns");
                }
                assertTrue(schemaCtx.contains("ordered_qty"));
                assertTrue(schemaCtx.contains("status"), "both requested objects' columns accumulate");
                return null; // done — this test only proves accumulation, not execution
            }
        };
        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) { return null; }
        };
        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) { fail("must not execute"); return null; }
        };

        engine(fakePlanner, fakeEvaluator, fakeRuntime).reason(
                "q", "q", "rsession", "schema context", "run-1", "user@test.com",
                false, null, null, false, "conv-1", null, null, resolvedContract());

        assertEquals(3, plannerCalls.get());
    }

    // ── Scenario: iteration limits prevent infinite metadata-request loops ────────────────────

    @Test
    void repeatedMetadataRequestsAreBoundedByTheExistingMaxStepsLimit() {
        AtomicInteger plannerCalls = new AtomicInteger(0);
        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                plannerCalls.incrementAndGet();
                // A pathological planner that always asks for metadata, never SQL — must not
                // hang or loop beyond the engine's existing step budget.
                return StepPlan.metadataRequest("keeps asking", "r", "order_lines", "columns");
            }
        };
        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                fail("no SQL ever executed");
                return null;
            }
        };
        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) { fail("must never execute"); return null; }
        };

        ReasoningEngine.ReasoningResult result = engine(fakePlanner, fakeEvaluator, fakeRuntime).reason(
                "q", "q", "rsession", "schema context", "run-1", "user@test.com",
                false, null, null, false, "conv-1", null, null, resolvedContract());

        assertEquals(ReasoningEngine.MAX_STEPS, plannerCalls.get(),
                "the loop must stop at the existing MAX_STEPS bound, never looping indefinitely");
        assertTrue(result.queryData().isEmpty());
    }

    // ── Scenario: existing behavior is unchanged when sufficient metadata is already available ─

    @Test
    void plannerThatNeverRequestsMetadataBehavesExactlyAsBeforeThisFeatureExisted() {
        AtomicInteger plannerCalls = new AtomicInteger(0);
        ReasoningPlanner fakePlanner = new ReasoningPlanner(null, null) {
            @Override
            public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                plannerCalls.incrementAndGet();
                return new StepPlan("normal step", "SELECT id FROM orders", CONN, "", "r");
            }
        };
        ReasoningEvaluator fakeEvaluator = new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                return new EvaluationResult("SUFFICIENT", "done");
            }
        };
        AtomicInteger executions = new AtomicInteger(0);
        GovernedSqlRuntime fakeRuntime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) {
                executions.incrementAndGet();
                return new Outcome(Status.EXECUTED, null, null,
                        List.of(Map.of("id", 1)), "[{\"id\":1}]", 5L, List.of(), null, null, null);
            }
        };

        engine(fakePlanner, fakeEvaluator, fakeRuntime).reason(
                "q", "q", "rsession", "schema context", "run-1", "user@test.com",
                false, null, null, false, "conv-1", null, null, resolvedContract());

        assertEquals(1, plannerCalls.get());
        assertEquals(1, executions.get());
    }
}
