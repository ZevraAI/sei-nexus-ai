package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.ExecutionContractBuilder;
import com.sei.nexus.agentbrain.PromptAssembler;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.ResolvedBusinessModel;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.semanticmodel.AttributeRole;
import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE experiment (opt-in): measures, against the REAL ReasoningPlanner system prompt and the
 * REAL PromptAssembler/PromptContextBuilder/ExecutionContractBuilder rendering pipeline, whether
 * GPT-4o preserves a physical identifier it was correctly given rather than substituting a more
 * "conventional" variant — the exact failure class behind the clean-tenant {@code ordered_date
 * -> order_date} incident and the pre-existing {@code on_hand_qty -> quantity_on_hand} incident.
 *
 * <p>Guarded by -Dnexus.live.openai=true and OPENAI_API_KEY in the environment, so the normal
 * test suite never calls the API. Makes NO database writes and does not execute any generated
 * SQL — it only inspects the identifiers the model chose to write.
 *
 * <p>Uses a single business object per case (not the full 15-object clean-tenant scope) so the
 * 1500-char render budget never engages — this probe isolates identifier fidelity given the
 * correct token IS present in the rendered prompt, which is exactly what the clean-tenant
 * incident proved was true. Whether the budget/ranking mechanism itself is safe across many
 * objects is a separate, already-flagged concern, out of scope here.
 */
class IdentifierFidelityLiveProbe {

    private static final int TRIALS = 20;

    private final ExecutionContractBuilder contractBuilder =
            new ExecutionContractBuilder(new SqlTableReferenceExtractor());
    private final PromptContextBuilder promptContextBuilder = new PromptContextBuilder();
    private final PromptAssembler      promptAssembler      = new PromptAssembler();

    /** One test case: the object rendered, the question asked, and the identifiers to check for. */
    private record Case(String label, BusinessObject object, PhysicalTable table,
                        Map<String, PhysicalColumn> columns, String question,
                        String correctIdentifier, List<String> knownWrongSubstitutions) {}

    private static Case purchaseOrderCase() {
        // Real columns from the clean tenant's actual purchase_orders registration.
        String[][] cols = {
            {"id", "identifier", "uuid"}, {"po_number", "identifier", "character varying"},
            {"supplier_id", "identifier", "uuid"}, {"destination_warehouse_id", "identifier", "uuid"},
            {"fiscal_period_id", "identifier", "uuid"}, {"status", "dimension", "USER-DEFINED"},
            {"buyer_name", "attribute", "character varying"}, {"ordered_date", "dimension", "date"},
            {"expected_delivery_date", "dimension", "date"}, {"actual_delivery_date", "dimension", "date"},
            {"total_lines", "measure", "smallint"}, {"total_ordered_amount", "measure", "numeric"},
            {"notes", "attribute", "text"}, {"created_at", "dimension", "timestamp with time zone"},
            {"updated_at", "dimension", "timestamp with time zone"},
            {"created_by", "dimension", "uuid"}, {"updated_by", "dimension", "uuid"},
        };
        return buildCase("Purchase Order (ordered_date / total_ordered_amount)",
                "obj-po", "Purchase Orders", "purchase_orders", cols,
                "Show me all open orders",
                "ordered_date", List.of("order_date"));
    }

    private static Case inventoryBalanceCase() {
        // Real columns from the clean tenant's actual inventory_balances registration.
        String[][] cols = {
            {"id", "identifier", "uuid"}, {"product_id", "identifier", "uuid"},
            {"location_id", "identifier", "uuid"}, {"location_type", "dimension", "USER-DEFINED"},
            {"zone_id", "identifier", "uuid"}, {"on_hand_qty", "measure", "integer"},
            {"available_qty", "measure", "integer"}, {"in_transit_qty", "measure", "integer"},
            {"reorder_point", "measure", "integer"}, {"last_count_date", "dimension", "date"},
            {"last_movement_date", "dimension", "date"}, {"fiscal_period_id", "identifier", "uuid"},
            {"created_at", "dimension", "timestamp with time zone"},
            {"updated_at", "dimension", "timestamp with time zone"},
            {"created_by", "dimension", "uuid"}, {"updated_by", "dimension", "uuid"},
        };
        return buildCase("Inventory Balance (on_hand_qty)",
                "obj-inv", "Inventory Balances", "inventory_balances", cols,
                "I want to know the inventory balances of our products",
                "on_hand_qty", List.of("quantity_on_hand"));
    }

    private static Case buildCase(String label, String objectKey, String businessName, String table,
                                   String[][] cols, String question, String correctIdentifier,
                                   List<String> knownWrong) {
        List<BusinessAttribute> attrs = new java.util.ArrayList<>();
        Map<String, PhysicalColumn> targets = new LinkedHashMap<>();
        for (String[] c : cols) {
            String colKey = "c-" + c[0];
            AttributeRole role = switch (c[1]) {
                case "identifier" -> AttributeRole.IDENTIFIER;
                case "measure"    -> AttributeRole.MEASURE;
                case "dimension"  -> AttributeRole.DIMENSION;
                default           -> AttributeRole.ATTRIBUTE;
            };
            attrs.add(new BusinessAttribute(colKey, c[0], role)); // no distinct business name (matches real tenant: business_meaning blank)
            targets.put(colKey, new PhysicalColumn("conn-1", "retail_core", table, c[0], c[2]));
        }
        BusinessObject obj = new BusinessObject(objectKey, businessName, "", attrs, List.of());
        PhysicalTable tbl = new PhysicalTable("conn-1", "retail_core", table);
        return new Case(label, obj, tbl, targets, question, correctIdentifier, knownWrong);
    }

    private String renderSchemaBlock(Case c) {
        ResolvedBusinessModel model = new ResolvedBusinessModel(
                "agent-probe", List.of("conn-1"), c.question(),
                List.of(c.object()), Map.of(c.object().objectKey(), c.table()), c.columns());
        ExecutionContract contract = contractBuilder.compile(model);
        // RenderOptions(qualifyLocation, includeDataType, includePurpose, maxChars) — matches
        // ChatService.java's production call exactly (true, true, true, maxEntityContextChars).
        return promptAssembler.assemble(promptContextBuilder.build(contract),
                new PromptAssembler.RenderOptions(true, true, true, 1500));
    }

    /** Mirrors ReasoningPlanner.buildPrompt() exactly (question + schema + empty evidence). */
    private String userPrompt(Case c, String schemaBlock) {
        return "Question: " + c.question() + "\n\n"
                + "Approved schema:\n" + schemaBlock + "\n\n"
                + "Evidence so far:\nNo queries have been executed yet.";
    }

    private static String systemPrompt() throws Exception {
        Field f = ReasoningPlanner.class.getDeclaredField("SYSTEM_PROMPT");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    /**
     * The EXACT schema-context text real production rendered for the clean-tenant incident's
     * question ("show me all open orders") — 15 real business objects, real relevance ranking,
     * real 1500-char budget applied — reproduced byte-for-byte via the actual
     * {@code AgentBrain.relevance()} / {@code PromptAssembler} algorithms run against the real
     * tenant's persisted metadata (verified deterministically in the prior investigation; this is
     * not hand-approximated). Purchase Order ranks #1 and renders in full (its business_name
     * "Purchase Orders" matches the keyword "orders"); the other 14 objects have their columns
     * omitted by the budget — exactly as production did. This is deliberately NOT the same as the
     * single-object cases above: those isolate identifier fidelity in a noise-free prompt and, as
     * measured below, do not reproduce the defect at all — this case restores the real surrounding
     * complexity (14 other tables, budget-omission stubs) that the single-object cases strip away.
     */
    private static final String CLEAN_TENANT_SCHEMA_BLOCK = """
            Agent: Data Analyst | Domain: PLATFORM

            === RESOLUTIONS ===
            "open" = value: status = 'open'   [company]

            The following is the COMPLETE and AUTHORITATIVE database schema available to you. \
            No tables or columns exist beyond those listed here. Ignore any prior knowledge of ERP, \
            retail, inventory, or generic SQL schemas — the identifiers in this schema are the ONLY \
            valid SQL identifiers you may use.

            TABLE `retail_core.purchase_orders`  — Purchase Orders
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table stores information about purchase orders made to suppliers.
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

            TABLE `retail_core.stores`
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table stores information about retail store locations.
              (columns omitted to fit the context budget)
                `state_province`  [observed values: Arizona | California | Florida | Georgia | Illinois | Massachusetts | Minnesota | New York | Pennsylvania | Texas | Washington]
                `status`  [legal values: open | temporarily_closed | seasonal | under_construction | closed]
                `store_type`  [legal values: flagship | standard | outlet | kiosk | seasonal]

            TABLE `retail_core.inventory_adjustments`  — Inventory Adjustments
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table records adjustments made to inventory counts.
              (columns omitted to fit the context budget)
                `adjustment_type`  [legal values: shrinkage | cycle_count | full_count | damage_writeoff | found_stock | system_correction]
                `location_type`  [legal values: warehouse | store]
                `status`  [legal values: draft | submitted | approved | posted]

            TABLE `retail_core.inventory_balances`  — Inventory Balances
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table stores the inventory levels of products at various locations.
              (columns omitted to fit the context budget)
                `location_type`  [legal values: warehouse | store]

            TABLE `retail_core.inventory_transactions`  — Inventory Transactions
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table logs all inventory movements and adjustments.
              (columns omitted to fit the context budget)
                `location_type`  [legal values: warehouse | store]
                `transaction_type`  [legal values: receipt | quarantine_in | quarantine_out | transfer_out | transfer_in | sale | return | adjustment]

            TABLE `retail_core.products`
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table stores information about products available for sale.
              (columns omitted to fit the context budget)
                `season`  [legal values: year_round | spring_summer | fall_winter | holiday]
                `status`  [legal values: pending_introduction | active | seasonal | discontinued]

            TABLE `retail_core.product_categories`  — Product Categorys
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table defines the hierarchical structure of product categories.
              (columns omitted to fit the context budget)
                `level`  [legal values: department | class | subclass]

            TABLE `retail_core.promotions`
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table stores details about promotional campaigns and discounts.
              (columns omitted to fit the context budget)
                `discount_type`  [legal values: percentage | fixed_amount | buy_x_get_y]
                `promotion_type`  [legal values: national | regional | store_specific | clearance]
                `status`  [legal values: planned | active | completed | cancelled]

            TABLE `retail_core.receipts`
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table stores information about goods received from purchase orders.
              (columns omitted to fit the context budget)
                `status`  [legal values: pending_inspection | accepted | partially_accepted | rejected]

            TABLE `retail_core.regions`
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table stores information about different geographical regions where the business operates.
              (columns omitted to fit the context budget)

            TABLE `retail_core.sales_transactions`  — Sales Transactions
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table stores individual sales transactions made in retail stores.
              (columns omitted to fit the context budget)
                `channel`  [legal values: in_store | online | curbside_pickup]
                `transaction_type`  [legal values: sale | return | exchange]

            TABLE `retail_core.sales_transaction_lines`  — Sales Transaction Lines
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table records individual line items within a sales transaction.
              (columns omitted to fit the context budget)

            TABLE `retail_core.suppliers`
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table contains information about suppliers.
              (columns omitted to fit the context budget)
                `state_province`  [observed values: Arizona | California | Colorado | Florida | Georgia | Illinois | Massachusetts | New York | Pennsylvania | Tennessee | Texas | Washington | Wisconsin]
                `status`  [legal values: active | under_review | suspended | inactive]
                `tier`  [legal values: tier_1 | tier_2 | tier_3]

            TABLE `retail_core.supplier_contracts`  — Supplier Contracts
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table stores details about contracts with suppliers, including pricing and terms.
              (columns omitted to fit the context budget)
                `contract_type`  [legal values: standard | volume_discount | exclusive | spot_buy]

            TABLE `retail_core.warehouses`
              connection_key: conn-26a01eb1 (use this exact value)
              purpose: This table stores information about warehouse facilities.
              (columns omitted to fit the context budget)
                `facility_type`  [legal values: distribution_center | fulfillment_center | returns_facility | cold_storage | overstock | cross_dock]
                `state_province`  [observed values: California | Delaware | Georgia | Illinois | New Jersey | North Carolina | Tennessee]
                `status`  [legal values: active | inactive | under_construction | decommissioned]

            IDENTIFIER RULES (absolute):
            • The table and column names in the schema are literal SQL identifiers, not examples or \
            descriptions. Copy each one character-for-character.
            • PHYSICAL IDENTIFIERS ARE AUTHORITATIVE. For every table and column — not just ones that \
            resemble a known example — the only valid physical identifier is the exact one shown in the \
            schema. Never derive, rename, normalize, translate, abbreviate, pluralize, reorder, or \
            substitute one based on the user's natural-language wording, business terminology, common \
            ERP terminology, common SQL/database naming conventions, or what you consider a more \
            familiar or more "correct" name for that concept — even when the schema's identifier is \
            unfamiliar, unusual, abbreviated, reordered, or otherwise different from how the user or \
            common convention would phrase it.
            • Reason in two separate steps, and do not collapse them into one: (1) interpret what the \
            user means — the business concept; (2) find the physical identifier the schema already \
            assigns to that concept and copy it verbatim. Never substitute step 2 with a guess at the \
            conventional name for the concept from step 1 — the schema, not convention, is the source \
            of the physical name. If the schema lists `on_hand_qty`, write `on_hand_qty` exactly — never \
            a conventional variant like quantity_on_hand.
            • Use only the tables and columns listed in the schema. If a column you need is not listed, \
            do not invent one — state that the schema does not contain the requested field instead of \
            guessing.
            """;

    @Test
    void identifierFidelityAgainstTheRealCleanTenantSchemaShape() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live probe disabled");
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");

        ObjectMapper mapper = new ObjectMapper();
        AzureOpenAiClient client = new AzureOpenAiClient(mapper, null);
        setField(client, "apiKey", apiKey);
        setField(client, "chatModel", "gpt-4o");
        java.lang.reflect.Method init = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        init.setAccessible(true);
        init.invoke(client);

        String sysPrompt = systemPrompt();
        String user = "Question: show me all open orders\n\n"
                + "Approved schema:\n" + CLEAN_TENANT_SCHEMA_BLOCK + "\n\n"
                + "Evidence so far:\nNo queries have been executed yet.";

        int exact = 0, wrongOrderDate = 0, wrongTotalAmount = 0, malformed = 0;
        System.out.println("\n########## REAL 15-object clean-tenant schema shape — \"show me all open orders\" ##########");
        for (int t = 1; t <= TRIALS; t++) {
            try {
                String raw = client.chat(List.of(ChatMessage.user(user)), sysPrompt);
                String json = extractJson(raw);
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(json, Map.class);
                Object sqlObj = parsed.get("sql");
                String sql = sqlObj == null ? null : sqlObj.toString();
                if (sql == null) { malformed++; System.out.println("  trial " + t + ": MALFORMED raw=" + raw); continue; }
                String low = sql.toLowerCase();
                boolean hasOrderDateWrong = low.contains("order_date") && !low.contains("ordered_date");
                boolean hasTotalAmountWrong = low.contains("total_amount") && !low.contains("total_ordered_amount");
                if (hasOrderDateWrong) wrongOrderDate++;
                if (hasTotalAmountWrong) wrongTotalAmount++;
                if (!hasOrderDateWrong && !hasTotalAmountWrong) exact++;
                System.out.println("  trial " + t + ": " + sql);
            } catch (Exception e) {
                malformed++;
                System.out.println("  trial " + t + ": ERROR " + e.getMessage());
            }
        }
        System.out.println("REAL-SHAPE RESULT: " + exact + "/" + TRIALS + " fully correct, "
                + wrongOrderDate + "/" + TRIALS + " substituted order_date, "
                + wrongTotalAmount + "/" + TRIALS + " substituted total_amount, "
                + malformed + "/" + TRIALS + " malformed");
    }

    @Test
    void identifierFidelityAcrossRepeatedTrials() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live probe disabled");
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");

        ObjectMapper mapper = new ObjectMapper();
        AzureOpenAiClient client = new AzureOpenAiClient(mapper, null);
        setField(client, "apiKey", apiKey);
        setField(client, "chatModel", "gpt-4o");
        // @PostConstruct doesn't fire outside a Spring container — initialize the
        // global-throttle Semaphore manually (matches AzureOpenAiClientThrottleTest's pattern).
        java.lang.reflect.Method init = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        init.setAccessible(true);
        init.invoke(client);

        String sysPrompt = systemPrompt();

        for (Case c : List.of(purchaseOrderCase(), inventoryBalanceCase())) {
            String schemaBlock = renderSchemaBlock(c);
            String user = userPrompt(c, schemaBlock);

            int exact = 0, wrong = 0, malformed = 0;
            System.out.println("\n########## " + c.label() + " — question: \"" + c.question() + "\" ##########");
            for (int t = 1; t <= TRIALS; t++) {
                try {
                    String raw = client.chat(List.of(ChatMessage.user(user)), sysPrompt);
                    String json = extractJson(raw);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = mapper.readValue(json, Map.class);
                    Object sqlObj = parsed.get("sql");
                    String sql = sqlObj == null ? null : sqlObj.toString();
                    if (sql == null) { malformed++; System.out.println("  trial " + t + ": MALFORMED (no sql field) raw=" + raw); continue; }
                    String low = sql.toLowerCase();
                    boolean hasCorrect = low.contains(c.correctIdentifier().toLowerCase());
                    boolean hasWrong = c.knownWrongSubstitutions().stream().anyMatch(w -> low.contains(w.toLowerCase()));
                    if (hasWrong) {
                        wrong++;
                        System.out.println("  trial " + t + ": SUBSTITUTED -> " + sql);
                    } else if (hasCorrect) {
                        exact++;
                        System.out.println("  trial " + t + ": exact");
                    } else {
                        malformed++;
                        System.out.println("  trial " + t + ": neither identifier present -> " + sql);
                    }
                } catch (Exception e) {
                    malformed++;
                    System.out.println("  trial " + t + ": ERROR " + e.getMessage());
                }
            }
            System.out.println(c.label() + " RESULT: " + exact + "/" + TRIALS + " exact, "
                    + wrong + "/" + TRIALS + " substituted, " + malformed + "/" + TRIALS + " malformed/other");
        }
    }

    private static String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
