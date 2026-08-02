package com.sei.nexus.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.AgentBrain;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.ExecutionContractBuilder;
import com.sei.nexus.agentbrain.PromptAssembler;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.ResolvedBusinessModel;
import com.sei.nexus.ai.AgentMessage;
import com.sei.nexus.ai.AgentToolResponse;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE experiment (opt-in): actually calls GPT-4o with the hardened grounding and reports the
 * SQL it generates — to measure whether prompt hardening alone stops identifier substitution.
 * Guarded by -Dnexus.live.openai=true and OPENAI_API_KEY in the environment, so the normal
 * test suite never calls the API. Makes NO database writes and does not execute the SQL; it
 * only inspects the model's generated identifiers.
 */
class PromptHardeningLiveProbe {

    private static final String QUESTION = "I want to know the inventory balances of our products";
    private static final int SAMPLES = 3;

    @Test
    void liveGeneratedSqlUsesGroundedIdentifiers() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live probe disabled");
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");

        ObjectMapper mapper = new ObjectMapper();

        // Real pipeline + hardened prompt (seeded with the verified live inventory_balances rows).
        EnterpriseSemanticAssembler assembler =
                new EnterpriseSemanticAssembler(new AzurePayloadCaptureTest.SeededMap());
        AgentBrain brain = new AgentBrain(assembler, null);   // agent scope: resolver never consulted
        ExecutionContractBuilder builder = new ExecutionContractBuilder(new SqlTableReferenceExtractor());
        PromptContextBuilder pcb = new PromptContextBuilder();
        PromptAssembler pa = new PromptAssembler();

        ZevraAgent agent = new ZevraAgent("agent-md", "tenant_maryland_corporations",
                "Inventory Ops", "inv-ops", "desc",
                "You are an inventory operations analyst.", "Answer questions about inventory.",
                List.of("conn-5780d333"), 8, "ACTIVE", "prakash.stk12@gmail.com", null, null);

        ResolvedBusinessModel model = brain.resolve(agent, QUESTION);
        ExecutionContract contract = builder.compile(model);
        String grounding = pa.assemble(pcb.build(contract));
        String systemPrompt = AgentRunner.buildSystemPrompt(agent, grounding);

        AgentToolRegistry toolRegistry = new AgentToolRegistry(null, null, null);
        List<Map<String, Object>> tools = toolRegistry.getToolDefinitions(agent.connectionKeys());

        AzureOpenAiClient client = new AzureOpenAiClient(mapper, null);
        setField(client, "apiKey", apiKey);
        setField(client, "chatModel", "gpt-4o");

        // The contract-scoped describe_schema answer (tables only), as the real runtime returns.
        String schemaAnswer = mapper.writeValueAsString(
                List.of(Map.of("table", "inventory_balances", "schema", "retail_core")));

        System.out.println("\n########## LIVE GPT-4o PROBE — hardened prompt ##########");
        for (int s = 1; s <= SAMPLES; s++) {
            String sql = firstGeneratedSql(client, systemPrompt, tools, mapper, schemaAnswer);
            System.out.println("\n--- sample " + s + " ---");
            System.out.println("generated SQL: " + (sql == null ? "(none — model gave final_answer)" : sql));
            if (sql != null) {
                String low = sql.toLowerCase();
                System.out.println("  uses on_hand_qty          : " + low.contains("on_hand_qty"));
                System.out.println("  uses available_qty        : " + low.contains("available_qty"));
                System.out.println("  substituted quantity_on_hand   : " + low.contains("quantity_on_hand"));
                System.out.println("  substituted quantity_available : " + low.contains("quantity_available"));
                System.out.println("  invented   quantity_reserved   : " + low.contains("quantity_reserved"));
                System.out.println("  invented   reserved_qty        : " + low.contains("reserved_qty"));
            }
        }
        System.out.println("########## END LIVE PROBE ##########\n");
    }

    /** Runs a minimal ReAct loop; returns the SQL of the first query_database call, or null. */
    private String firstGeneratedSql(AzureOpenAiClient client, String systemPrompt,
                                     List<Map<String, Object>> tools, ObjectMapper mapper,
                                     String schemaAnswer) throws Exception {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.user(QUESTION));
        for (int i = 0; i < 5; i++) {
            AgentToolResponse r = client.chatWithTools(messages, systemPrompt, tools);
            if (r.finalAnswer() || "final_answer".equals(r.toolName())) return null;
            if ("query_database".equals(r.toolName())) {
                Object sql = r.args() == null ? null : r.args().get("sql");
                return sql == null ? null : sql.toString();
            }
            if ("describe_schema".equals(r.toolName())) {
                String argsJson = mapper.writeValueAsString(r.args());
                messages.add(AgentMessage.assistantToolCall(r.toolCallId(), r.toolName(), argsJson));
                messages.add(AgentMessage.toolResult(r.toolCallId(), schemaAnswer));
                continue;
            }
            return null; // any other tool — stop
        }
        return null;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
