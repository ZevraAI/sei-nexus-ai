package com.sei.nexus.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.AgentBrain;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.ExecutionContractBuilder;
import com.sei.nexus.agentbrain.PromptAssembler;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.ResolvedBusinessModel;
import com.sei.nexus.ai.AgentMessage;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.enterprise.DataColumn;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FORENSIC CAPTURE (diagnostic, not a behavioral test). Drives the real prompt pipeline
 * (AgentBrain → ExecutionContractBuilder → PromptContextBuilder → PromptAssembler →
 * AgentRunner.buildSystemPrompt) and the real {@link AzureOpenAiClient#chatWithTools} into the
 * real serialization choke point, then captures the exact JSON that would be transmitted to
 * Azure OpenAI — aborting immediately before the network send (nexus.capture.abortBeforeSend),
 * so nothing leaves the machine and no API cost is incurred.
 *
 * <p>The Enterprise Map is seeded with the columns verified live from
 * tenant_maryland_corporations / inventory_balances (object platform-conn-5780d333-inventory-balances),
 * so the payload's identifiers are the real ones. The goal is to prove whether
 * {@code quantity_on_hand}/{@code quantity_available}/{@code quantity_reserved} appear anywhere
 * in the transmitted request, or only {@code on_hand_qty}/{@code available_qty}.
 */
class AzurePayloadCaptureTest {

    /** Enterprise Map seeded with the exact live rows (business_meaning is empty in prod). */
    static class SeededMap extends EnterpriseMapRepository {
        SeededMap() { super(null); }

        @Override public List<DataObject> findDataObjectsByConnectionKeys(List<String> keys) {
            return List.of(new DataObject(
                    "platform-conn-5780d333-inventory-balances", "PLATFORM", "inventory_balances",
                    "conn-5780d333", "retail_core", "inventory_balances", "Inventory Balances",
                    "Inventory on hand", "id", "location_type", "", "location_type",
                    // usage/filter/avoid — combined text as rendered live (order preserved)
                    "Use this table to get a comprehensive view of inventory levels across different "
                            + "locations and zones. Filter by location_type, location_id, or zone_id to "
                            + "focus on specific areas of interest. Avoid using 'created_by' and "
                            + "'updated_by' for filtering as they are meant for audit purposes.",
                    "", "", 100, false, "SCANNED", 1, Instant.now(), Instant.now()));
        }

        @Override public List<DataColumn> findColumnsByObject(String objectKey) {
            List<DataColumn> c = new ArrayList<>();
            //     columnKey        name                type                     id     status filt
            c.add(col("col-86e26d53","available_qty",     "integer",               false, true,  true));
            c.add(col("col-86e27cee","created_at",        "timestamp with time zone", false, false, true));
            c.add(col("col-86e281ff","created_by",        "uuid",                  false, false, true));
            c.add(col("col-86e27a43","fiscal_period_id",  "uuid",                  true,  false, true));
            c.add(col("col-86e25c85","id",                "uuid",                  true,  false, true));
            c.add(col("col-86e2700c","in_transit_qty",    "integer",               false, true,  true));
            c.add(col("col-86e277ba","last_count_date",   "date",                  false, false, true));
            c.add(col("col-86e27524","last_movement_date","date",                  false, false, true));
            c.add(col("col-86e26569","location_id",       "uuid",                  true,  false, true));
            c.add(col("col-86e262cd","location_type",     "USER-DEFINED",          false, true,  true));
            c.add(col("col-86e26aa3","on_hand_qty",       "integer",               false, true,  true));
            c.add(col("col-86e26012","product_id",        "uuid",                  true,  false, true));
            c.add(col("col-86e27266","reorder_point",     "integer",               false, false, false));
            c.add(col("col-86e27f83","updated_at",        "timestamp with time zone", false, false, true));
            c.add(col("col-86e284b4","updated_by",        "uuid",                  false, false, true));
            c.add(col("col-86e26803","zone_id",           "uuid",                  true,  false, true));
            return c;
        }

        private static DataColumn col(String key, String name, String type,
                                      boolean id, boolean status, boolean filt) {
            return new DataColumn(key, "platform-conn-5780d333-inventory-balances", name, type,
                    true, "" /* business_meaning empty in prod */, id, status, false, false, filt,
                    null, null, "DECLARED", Instant.now(), Instant.now());
        }
    }

    // These are JVM-wide system properties, not per-test state — Surefire reuses one
    // JVM across test classes by default, so leaving them set here previously leaked
    // into whatever test ran next (observed: AzureOpenAiClientThrottleTest's real HTTP
    // seam was aborted by a stale abortBeforeSend from this test's prior run).
    @AfterEach
    void clearCaptureProperties() {
        System.clearProperty("nexus.capture.payload.dir");
        System.clearProperty("nexus.capture.abortBeforeSend");
    }

    @Test
    void captureExactTransmittedPayload() throws Exception {
        Path dir = Files.createTempDirectory("openai-capture");
        System.setProperty("nexus.capture.payload.dir", dir.toString());
        System.setProperty("nexus.capture.abortBeforeSend", "true");

        // ---- real prompt pipeline (identical components to AgentRunner) ----
        EnterpriseSemanticAssembler assembler = new EnterpriseSemanticAssembler(new SeededMap());
        AgentBrain brain = new AgentBrain(assembler, null);   // agent scope: resolver never consulted
        ExecutionContractBuilder builder = new ExecutionContractBuilder(new SqlTableReferenceExtractor());
        PromptContextBuilder promptContextBuilder = new PromptContextBuilder();
        PromptAssembler promptAssembler = new PromptAssembler();

        ZevraAgent agent = new ZevraAgent("agent-md", "tenant_maryland_corporations",
                "Inventory Ops", "inv-ops", "desc",
                "You are an inventory operations analyst.", "Answer questions about inventory.",
                List.of("conn-5780d333"), 8, "ACTIVE", "prakash.stk12@gmail.com", null, null);
        String question = "I want to know the inventory balances of our products";

        ResolvedBusinessModel model = brain.resolve(agent, question);
        ExecutionContract contract = builder.compile(model);
        String grounding = promptAssembler.assemble(promptContextBuilder.build(contract));
        String systemPrompt = AgentRunner.buildSystemPrompt(agent, grounding);   // real post-assembler step

        // ---- real tool definitions + initial conversation (as AgentRunner builds them) ----
        AgentToolRegistry toolRegistry = new AgentToolRegistry(null, null, null);
        List<Map<String, Object>> tools = toolRegistry.getToolDefinitions(agent.connectionKeys());
        List<AgentMessage> messages = List.of(AgentMessage.user(question));

        // ---- real client + real serialization; abort before the network send ----
        AzureOpenAiClient client = new AzureOpenAiClient(new ObjectMapper(), null);
        setField(client, "apiKey", "unused-capture-only");
        setField(client, "chatModel", "gpt-4o");   // matches OPENAI_CHAT_MODEL default in application.yml

        try {
            client.chatWithTools(messages, systemPrompt, tools);
            fail("expected abort-before-send");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("send aborted"), expected.getMessage());
        }

        // ---- read back and print the EXACT transmitted bytes ----
        Path payloadFile;
        try (var s = Files.list(dir)) {
            payloadFile = s.findFirst().orElseThrow();
        }
        String payload = Files.readString(payloadFile);

        System.out.println("\n########## EXACT TRANSMITTED PAYLOAD (" + payload.length() + " bytes) ##########");
        System.out.println(payload);
        System.out.println("########## END PAYLOAD ##########\n");

        System.out.println("IDENTIFIER PRESENCE IN TRANSMITTED PAYLOAD:");
        for (String id : List.of("on_hand_qty", "available_qty", "reserved_qty",
                "quantity_on_hand", "quantity_available", "quantity_reserved")) {
            System.out.println("  " + id + " present=" + payload.contains(id));
        }

        // Assertions: grounded identifiers present as backticked literals; ERP canonical names
        // never appear as approved (backticked) identifiers. The hardened fidelity rules mention
        // `quantity_on_hand` only as an UNbackticked negative example, so we assert on the
        // backticked form; quantity_available/quantity_reserved appear nowhere at all.
        assertTrue(payload.contains("`on_hand_qty`"),   "grounded on_hand_qty must be present as a literal");
        assertTrue(payload.contains("`available_qty`"), "grounded available_qty must be present as a literal");
        assertFalse(payload.contains("`quantity_on_hand`"),  "quantity_on_hand must not appear as an approved identifier");
        assertFalse(payload.contains("quantity_available"),  "canonical quantity_available must NOT be present");
        assertFalse(payload.contains("quantity_reserved"),   "canonical quantity_reserved must NOT be present");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
