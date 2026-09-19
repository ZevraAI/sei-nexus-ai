package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.usage.UsageService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PHASE 2 PART A — MODEL EVALUATION (gpt-4o vs gpt-4.1) for {@link ReasoningPlanner},
 * INDEPENDENT of Stage 1. DIAGNOSTIC ONLY, real OpenAI, no DB.
 *
 * <p>{@code REAL_SCHEMA_CTX} below is not invented — it is the exact, byte-for-byte {@code
 * schemaCtx} string captured from a REAL run of the production pipeline (real tenant {@code
 * persistent-ai-test}, real connection {@code conn-c1590229}, real Stage 1 File Search, real
 * {@code ExecutionContractBuilder}/{@code PromptContextBuilder}/{@code PromptAssembler}) for the
 * question "Show me all open purchase orders", frozen here so both models receive an IDENTICAL,
 * real, already-resolved schema/resolutions/legal-values context — removing Stage 1 as a variable
 * entirely (per the Part A requirement that gpt-4o and gpt-4.1 receive EXACTLY the same schema
 * context, learned business evidence, and legal values).
 *
 * <p>8 scenarios, all against a single real {@code retail_core.purchase_orders} table (columns
 * confirmed above: {@code po_number, buyer_name, ordered_date, expected_delivery_date,
 * actual_delivery_date, status [draft|submitted|acknowledged|partially_received|received|
 * cancelled|closed], supplier_id, total_lines, total_ordered_amount, ...}), covering: simple
 * lookup, aggregate, filtered query, legal enum value / learned business knowledge ("open"),
 * unsupported literal requiring clarification, multi-step investigation, follow-up/result-set
 * boundary, and physical-identifier fidelity.
 */
class ModelComparisonPlannerCleanLiveValidation {

    private static final List<String> MODELS = List.of("gpt-4o", "gpt-4.1");

    static class RecordingUsageService extends UsageService {
        record Call(String model, int promptTokens, int completionTokens, int cachedTokens, String callType) {}
        final List<Call> calls = new ArrayList<>();
        RecordingUsageService() { super(null); }
        @Override
        public void record(String model, int promptTokens, int completionTokens, int cachedTokens, String callType) {
            calls.add(new Call(model, promptTokens, completionTokens, cachedTokens, callType));
        }
    }

    /** Byte-for-byte real capture — see class javadoc. */
    private static final String REAL_SCHEMA_CTX = """
            === RESOLUTIONS ===
            "open purchase orders" = value: status IN ('submitted', 'acknowledged', 'partially_received')   [company]
            "purchase orders" = entity: Purchase Order (table: retail_core.purchase_orders)   [company]
            "open" = value: status = 'open'   [company]

            The following is the COMPLETE and AUTHORITATIVE database schema available to you. No tables or columns exist beyond those listed here. Ignore any prior knowledge of ERP, retail, inventory, or generic SQL schemas — the identifiers in this schema are the ONLY valid SQL identifiers you may use.

            TABLE `retail_core.purchase_orders`  — Purchase Orders
              connection_key: conn-c1590229 (use this exact value)
              purpose: This table stores header information for each purchase order raised against a supplier.
              columns (write each `column` exactly as shown):
                • `actual_delivery_date` (dimension, date)
                • `buyer_name` (attribute, character varying)
                • `created_at` (dimension, timestamp with time zone)
                • `created_by` (dimension, uuid)
                • `destination_warehouse_id` (identifier, uuid)
                • `expected_delivery_date` (dimension, date)
                • `fiscal_period_id` (identifier, uuid)
                • `id` (identifier, uuid)
                • `notes` (identifier, text)
                • `ordered_date` (dimension, date)
                • `po_number` (identifier, character varying)
                • `status` (dimension, USER-DEFINED)  [legal values: draft | submitted | acknowledged | partially_received | received | cancelled | closed]
                • `supplier_id` (identifier, uuid)
                • `total_lines` (measure, smallint)
                • `total_ordered_amount` (measure, numeric)
                • `updated_at` (dimension, timestamp with time zone)
                • `updated_by` (dimension, uuid)

            IDENTIFIER RULES (absolute):
            • The table and column names in the schema are literal SQL identifiers, not examples or descriptions. Copy each one character-for-character.
            • PHYSICAL IDENTIFIERS ARE AUTHORITATIVE. For every table and column — not just ones that resemble a known example — the only valid physical identifier is the exact one shown in the schema. Never derive, rename, normalize, translate, abbreviate, pluralize, reorder, or substitute one based on the user's natural-language wording, business terminology, common ERP terminology, common SQL/database naming conventions, or what you consider a more familiar or more "correct" name for that concept — even when the schema's identifier is unfamiliar, unusual, abbreviated, reordered, or otherwise different from how the user or common convention would phrase it.
            • Reason in two separate steps, and do not collapse them into one: (1) interpret what the user means — the business concept; (2) find the physical identifier the schema already assigns to that concept and copy it verbatim. Never substitute step 2 with a guess at the conventional name for the concept from step 1 — the schema, not convention, is the source of the physical name. If the schema lists `on_hand_qty`, write `on_hand_qty` exactly — never a conventional variant like quantity_on_hand.
            • Use only the tables and columns listed in the schema. If a column you need is not listed, do not invent one — state that the schema does not contain the requested field instead of guessing.
            • Never assume a table has a column under a conventional name — e.g. quantity, amount, date, status, id, name — merely because that name is common for the concept you need. This applies even when the table itself is known to be relevant: a table whose column list is missing entirely, or marked as omitted for space, gives you NO column names to use — not even conventional ones. Only a column physically listed for that table is ever valid.
            """;

    private record Scenario(String label, String question, EvidenceStore evidence) {}

    @SuppressWarnings("unchecked")
    private static EvidenceStore stepOneUnfilteredEvidence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = """
                [{"po_number":"PO-1001","status":"submitted","supplier_id":"sup-aaa"},
                 {"po_number":"PO-1002","status":"acknowledged","supplier_id":"sup-aaa"},
                 {"po_number":"PO-1003","status":"received","supplier_id":"sup-bbb"},
                 {"po_number":"PO-1004","status":"closed","supplier_id":"sup-ccc"}]
                """;
        List<Map<String, Object>> rows = mapper.readValue(json, List.class);
        EvidenceStore evidence = new EvidenceStore();
        evidence.add(0, "All purchase orders, unfiltered",
                "SELECT po_number, status, supplier_id FROM retail_core.purchase_orders",
                "conn-c1590229", rows, null, null, null, 90L);
        return evidence;
    }

    private static final List<Scenario> SCENARIOS = buildScenarios();

    private static List<Scenario> buildScenarios() {
        try {
            return List.of(
                    new Scenario("1. Simple lookup", "Show me all purchase orders", new EvidenceStore()),
                    new Scenario("2. Aggregate", "How many purchase orders are there in total?", new EvidenceStore()),
                    new Scenario("3. Filtered query (exact legal value)", "Show me all submitted purchase orders", new EvidenceStore()),
                    new Scenario("4. Legal enum value / learned business knowledge", "Show me all open purchase orders", new EvidenceStore()),
                    new Scenario("5. Unsupported literal requiring clarification", "Show me all purchase orders that are pending review", new EvidenceStore()),
                    new Scenario("6. Multi-step investigation (aggregation over prior step)", "Which supplier has the most purchase orders among these?", stepOneUnfilteredEvidence()),
                    new Scenario("7. Follow-up / result-set-reuse boundary", "What about the closed ones?", stepOneUnfilteredEvidence()),
                    new Scenario("8. Physical identifier fidelity", "Show me the buyer name and order date for all purchase orders", new EvidenceStore())
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void compareModelsAcrossCleanPlannerScenarios() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        ObjectMapper mapper = new ObjectMapper();

        System.out.println("\n########## PHASE 2 PART A — CLEAN PLANNER MODEL COMPARISON ##########");

        for (String model : MODELS) {
            System.out.println("\n=================== MODEL: " + model + " ===================");
            RecordingUsageService usage = new RecordingUsageService();
            AzureOpenAiClient aiClient = new AzureOpenAiClient(mapper, usage);
            setField(aiClient, "apiKey", apiKey);
            setField(aiClient, "chatModel", model);
            setField(aiClient, "maxConcurrentCalls", 6);
            Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
            initThrottle.setAccessible(true);
            initThrottle.invoke(aiClient);

            ReasoningPlanner planner = new ReasoningPlanner(aiClient, mapper);

            for (Scenario s : SCENARIOS) {
                long t0 = System.nanoTime();
                ReasoningPlanner.StepPlan plan = planner.nextStep(s.question(), REAL_SCHEMA_CTX, s.evidence());
                long latencyMs = (System.nanoTime() - t0) / 1_000_000;

                System.out.println("\n[" + s.label() + "] \"" + s.question() + "\"");
                if (plan == null) {
                    System.out.println("  OUTPUT: null (done/no further queries)  latencyMs=" + latencyMs);
                } else if (plan.isClarification()) {
                    System.out.println("  OUTPUT: CLARIFICATION — " + plan.clarificationQuestion() + "  latencyMs=" + latencyMs);
                } else if (plan.isMetadataRequest()) {
                    System.out.println("  OUTPUT: METADATA_REQUEST — " + plan.metadataRequest() + "  latencyMs=" + latencyMs);
                } else {
                    System.out.println("  OUTPUT: sql=" + plan.sql() + "  latencyMs=" + latencyMs);
                }
            }

            System.out.println("\n--- USAGE (" + model + ") ---");
            for (var call : usage.calls) {
                System.out.println("  callType=" + call.callType() + " model=" + call.model()
                        + " promptTokens=" + call.promptTokens() + " cachedTokens=" + call.cachedTokens()
                        + " completionTokens=" + call.completionTokens());
            }
        }
    }

    /**
     * PHASE 3 — POST-MIGRATION REAL-TENANT VALIDATION. Runs the same 8 scenarios ONCE against the
     * REAL deployed configuration (Planner uses {@code plannerModel}=gpt-4.1; unrelated fields
     * left at their real defaults) rather than looping A/B — this proves the actually-intended
     * post-migration behavior, not a comparison.
     */
    @Test
    void validateDeployedConfigurationAcrossCleanPlannerScenarios() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        ObjectMapper mapper = new ObjectMapper();

        System.out.println("\n########## PHASE 3 — POST-MIGRATION PLANNER VALIDATION (real deployed config) ##########");

        RecordingUsageService usage = new RecordingUsageService();
        AzureOpenAiClient aiClient = new AzureOpenAiClient(mapper, usage);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "plannerModel", "gpt-4.1");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        ReasoningPlanner planner = new ReasoningPlanner(aiClient, mapper);

        for (Scenario s : SCENARIOS) {
            long t0 = System.nanoTime();
            ReasoningPlanner.StepPlan plan = planner.nextStep(s.question(), REAL_SCHEMA_CTX, s.evidence());
            long latencyMs = (System.nanoTime() - t0) / 1_000_000;

            System.out.println("\n[" + s.label() + "] \"" + s.question() + "\"");
            if (plan == null) {
                System.out.println("  OUTPUT: null (done/no further queries)  latencyMs=" + latencyMs);
            } else if (plan.isClarification()) {
                System.out.println("  OUTPUT: CLARIFICATION — " + plan.clarificationQuestion() + "  latencyMs=" + latencyMs);
            } else if (plan.isMetadataRequest()) {
                System.out.println("  OUTPUT: METADATA_REQUEST — " + plan.metadataRequest() + "  latencyMs=" + latencyMs);
            } else {
                System.out.println("  OUTPUT: sql=" + plan.sql() + "  latencyMs=" + latencyMs);
            }
        }

        System.out.println("\n--- USAGE (deployed config) ---");
        for (var call : usage.calls) {
            System.out.println("  callType=" + call.callType() + " model=" + call.model()
                    + " promptTokens=" + call.promptTokens() + " cachedTokens=" + call.cachedTokens()
                    + " completionTokens=" + call.completionTokens());
            assertEquals("gpt-4.1", call.model(), "every Planner call in the deployed configuration must use gpt-4.1");
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
