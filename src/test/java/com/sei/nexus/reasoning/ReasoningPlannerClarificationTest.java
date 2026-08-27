package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Semantic Reasoning Over Authoritative Value Domains — {@link ReasoningPlanner#nextStep}'s
 * parsing of the planner LLM's response, exercised directly against a scripted fake {@link
 * AzureOpenAiClient} (this project's convention — no Mockito, no DB, no live model).
 *
 * <p>These tests prove the JAVA PARSING/PLUMBING is correct — they script what a well-behaved
 * (or poorly-behaved) LLM response looks like and confirm {@link ReasoningPlanner} relays it
 * faithfully. They do not, and cannot, prove the real model always reasons correctly — that is
 * exactly the point: Java only ever accepts or rejects the model's own JSON shape, never derives
 * a business meaning itself.
 */
class ReasoningPlannerClarificationTest {

    static class ScriptedAiClient extends AzureOpenAiClient {
        String scriptedResponse;
        String lastUserMessage;
        String lastSystemPrompt;

        ScriptedAiClient() { super(new ObjectMapper(), null); }

        @Override
        public String chat(List<ChatMessage> messages, String systemPrompt) {
            lastUserMessage  = messages.get(0).content();
            lastSystemPrompt = systemPrompt;
            return scriptedResponse;
        }
    }

    private ScriptedAiClient aiClient;
    private ReasoningPlanner planner;
    private EvidenceStore evidence;

    @BeforeEach
    void setUp() {
        aiClient = new ScriptedAiClient();
        planner = new ReasoningPlanner(aiClient, new ObjectMapper());
        evidence = new EvidenceStore();
    }

    // ── Test 1 — an exact legal value is used correctly ──────────────────────────

    @Test
    void exactLegalValueLiteralProducesANormalSqlStep() {
        aiClient.scriptedResponse = """
                {"done":false,"description":"Submitted purchase orders","sql":"SELECT po_number FROM retail_core.purchase_orders WHERE status = 'submitted'","connection_key":"conn-1","rationale":"exact legal value match"}""";

        ReasoningPlanner.StepPlan plan = planner.nextStep(
                "show purchase orders with status submitted", "schema context", evidence);

        assertNotNull(plan);
        assertFalse(plan.isClarification());
        assertEquals("SELECT po_number FROM retail_core.purchase_orders WHERE status = 'submitted'", plan.sql());
        assertEquals("conn-1", plan.connectionKey());
    }

    // ── Test 2 — the planner declining (clarification) is parsed, never silently
    //             turned into SQL, and Java invents nothing in its place ──────────

    @Test
    void clarificationResponseIsParsedAsADeclinedStepNotSql() {
        aiClient.scriptedResponse = """
                {"done":false,"clarification_question":"'open' is not one of purchase_orders.status's legal values (draft, submitted, acknowledged, partially_received, received, cancelled, closed). Which one did you mean?","rationale":"no legal value or business definition matched 'open'"}""";

        ReasoningPlanner.StepPlan plan = planner.nextStep(
                "show purchase orders with status open", "schema context", evidence);

        assertNotNull(plan);
        assertTrue(plan.isClarification());
        assertNull(plan.sql(), "a clarification step must never carry SQL");
        assertNull(plan.connectionKey());
        assertTrue(plan.clarificationQuestion().contains("draft"),
                "the clarification text is exactly what the LLM produced — Java did not alter it");
        assertTrue(plan.clarificationQuestion().contains("open"));
    }

    @Test
    void aResponseWithNeitherSqlNorClarificationIsTreatedAsDone() {
        // Malformed/incomplete response (no sql, no clarification_question) — must not be
        // silently promoted to an executable step with Java filling in a guess.
        aiClient.scriptedResponse = """
                {"done":false,"description":"nothing useful"}""";

        ReasoningPlanner.StepPlan plan = planner.nextStep("show purchase orders with status open",
                "schema context", evidence);

        assertNull(plan, "an incomplete response with neither sql nor a clarification must resolve to 'done', never a guessed step");
    }

    // ── Test 3 — a business term the LLM resolves via available context is relayed
    //             verbatim, unmodified by Java (Java performs no keyword matching) ──

    @Test
    void aBusinessConceptResolvedByTheLlmToMultipleLegalValuesIsRelayedVerbatim() {
        // Simulates the LLM having reasoned "open purchase orders" against business context
        // available to it and constructing an IN(...) of legal values itself — Java's only role
        // is to relay this SQL string exactly as returned, never to construct or validate the
        // IN-list itself.
        aiClient.scriptedResponse = """
                {"done":false,"description":"Open purchase orders","sql":"SELECT po_number FROM retail_core.purchase_orders WHERE status IN ('draft','submitted','acknowledged','partially_received')","connection_key":"conn-1","rationale":"business context defines an open purchase order as one that has not yet been fully received or closed"}""";

        ReasoningPlanner.StepPlan plan = planner.nextStep(
                "show open purchase orders", "schema context", evidence);

        assertNotNull(plan);
        assertFalse(plan.isClarification());
        assertEquals("SELECT po_number FROM retail_core.purchase_orders WHERE status IN "
                + "('draft','submitted','acknowledged','partially_received')", plan.sql(),
                "Java relays the LLM's own constructed SQL verbatim — it never builds or edits the IN-list itself");
    }

    // ── Test 4 — the planner receives authoritative legal values in a clearly
    //             distinguishable form (prompt-contract assertions) ─────────────────

    @Test
    void thePromptDistinguishesAuthoritativeLegalValuesFromObservedSamples() throws Exception {
        java.lang.reflect.Field f = ReasoningPlanner.class.getDeclaredField("SYSTEM_PROMPT");
        f.setAccessible(true);
        String systemPrompt = (String) f.get(null);

        assertTrue(systemPrompt.contains("[legal values: ...] is AUTHORITATIVE"));
        assertTrue(systemPrompt.contains("CLOSED, COMPLETE set"));
        assertTrue(systemPrompt.contains("[observed values: ...] is a SAMPLE only"));
    }

    // ── Test 6 — Java contains no hard-coded "open" -> status mapping anywhere in
    //             this class's source, and never substitutes a value into the SQL ──

    @Test
    void javaSourceContainsNoHardCodedOpenToStatusMapping() throws Exception {
        // Reads the actual compiled class's source-adjacent behavior indirectly: since Java
        // never constructs "sql" itself (see thePromptDistinguishesAuthoritativeLegalValuesFrom...
        // and aBusinessConceptResolvedByTheLlmToMultipleLegalValuesIsRelayedVerbatim above — sql
        // is always exactly what the LLM returned, character for character), there is no code
        // path in nextStep() capable of injecting a Java-chosen literal. This test pins that
        // absence behaviorally: an LLM response that returns "open" verbatim as a literal (a
        // real model mistake) is relayed exactly as-is — Java neither rejects nor "corrects" it
        // on its own, because that decision belongs entirely to LiteralValidator/GovernedSqlRuntime
        // downstream, never to this parsing step.
        aiClient.scriptedResponse = """
                {"done":false,"description":"test","sql":"SELECT * FROM retail_core.purchase_orders WHERE status = 'open'","connection_key":"conn-1","rationale":"test"}""";

        ReasoningPlanner.StepPlan plan = planner.nextStep("show purchase orders with status open",
                "schema context", evidence);

        assertEquals("SELECT * FROM retail_core.purchase_orders WHERE status = 'open'", plan.sql(),
                "Java must never silently rewrite, correct, or substitute the LLM's literal — it only relays or, "
                        + "via the clarification path, declines");
    }
}
