package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Missing-Column Metadata Request — {@link ReasoningPlanner#nextStep}'s parsing of the
 * planner LLM's {@code requires_metadata} response, exercised against a scripted fake
 * {@link AzureOpenAiClient} (this project's convention — no Mockito, no DB, no live model).
 *
 * <p>Same discipline as {@link ReasoningPlannerClarificationTest}: these tests prove the JAVA
 * PARSING is correct given a scripted LLM response — they do not, and cannot, prove the real
 * model always requests metadata instead of guessing. Java performs zero interpretation of
 * {@code object} beyond structural parsing; whatever string the model provides is passed through
 * verbatim for {@link ColumnMetadataRequestHandler} to validate.
 */
class ReasoningPlannerMetadataRequestTest {

    static class ScriptedAiClient extends AzureOpenAiClient {
        String scriptedResponse;
        ScriptedAiClient() { super(new ObjectMapper(), null); }
        @Override
        public String chat(List<ChatMessage> messages, String systemPrompt) {
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

    @Test
    void requiresMetadataResponseParsesAsAMetadataRequestNotSql() {
        aiClient.scriptedResponse = """
                {"done":false,"description":"Need line item columns",
                 "requires_metadata":{"object":"order_lines","metadataType":"columns"},
                 "rationale":"the table's columns were omitted from the schema context"}""";

        ReasoningPlanner.StepPlan plan = planner.nextStep(
                "how many of each item did we order", "schema context", evidence);

        assertNotNull(plan);
        assertTrue(plan.isMetadataRequest());
        assertFalse(plan.isClarification());
        assertNull(plan.sql(), "a metadata-request step must never carry SQL");
        assertEquals("order_lines", plan.metadataRequest().object());
        assertEquals("columns", plan.metadataRequest().metadataType());
    }

    @Test
    void metadataTypeDefaultsToColumnsWhenOmitted() {
        aiClient.scriptedResponse = """
                {"done":false,"requires_metadata":{"object":"order_lines"},"rationale":"need columns"}""";

        ReasoningPlanner.StepPlan plan = planner.nextStep("q", "schema context", evidence);

        assertNotNull(plan);
        assertTrue(plan.isMetadataRequest());
        assertEquals("columns", plan.metadataRequest().metadataType());
    }

    @Test
    void objectFieldIsRelayedVerbatimJavaNeverInterpretsIt() {
        // Whatever string the model puts here — including one containing spaces, mixed case, or
        // a schema-qualified form — must be relayed byte-for-byte; Java performs no
        // normalization, keyword extraction, or business-term mapping on it.
        aiClient.scriptedResponse = """
                {"done":false,"requires_metadata":{"object":"Retail_Core.Purchase_Order_Lines","metadataType":"columns"},"rationale":"r"}""";

        ReasoningPlanner.StepPlan plan = planner.nextStep("q", "schema context", evidence);

        assertEquals("Retail_Core.Purchase_Order_Lines", plan.metadataRequest().object());
    }

    @Test
    void blankObjectFieldIsNotTreatedAsAMetadataRequest() {
        aiClient.scriptedResponse = """
                {"done":false,"requires_metadata":{"object":"","metadataType":"columns"}}""";

        ReasoningPlanner.StepPlan plan = planner.nextStep("q", "schema context", evidence);

        // No object, no sql, no clarification — an incomplete response resolves to "done",
        // exactly like the existing "neither sql nor clarification" case; Java never guesses one.
        assertNull(plan);
    }

    @Test
    void missingRequiresMetadataFieldFallsThroughToNormalSqlParsing() {
        // Existing behavior unchanged when a metadata request is absent — a normal SQL step
        // parses exactly as before this feature existed.
        aiClient.scriptedResponse = """
                {"done":false,"description":"normal step","sql":"SELECT id FROM orders","connection_key":"conn-1","rationale":"r"}""";

        ReasoningPlanner.StepPlan plan = planner.nextStep("q", "schema context", evidence);

        assertNotNull(plan);
        assertFalse(plan.isMetadataRequest());
        assertFalse(plan.isClarification());
        assertEquals("SELECT id FROM orders", plan.sql());
    }

    @Test
    void aMetadataRequestTakesPriorityOverAnAccompanyingSqlField() {
        // A response carrying both must still be treated as a metadata request — never silently
        // falls through to executing SQL built without the columns the model itself flagged as
        // missing.
        aiClient.scriptedResponse = """
                {"done":false,"requires_metadata":{"object":"order_lines","metadataType":"columns"},
                 "sql":"SELECT quantity FROM order_lines","connection_key":"conn-1","rationale":"r"}""";

        ReasoningPlanner.StepPlan plan = planner.nextStep("q", "schema context", evidence);

        assertTrue(plan.isMetadataRequest());
        assertNull(plan.sql());
    }

    @Test
    void systemPromptInstructsRequestingMetadataInsteadOfGuessingConventionalNames() throws Exception {
        String rule = metadataRequestRuleText();

        assertTrue(rule.contains("requires_metadata"));
        assertTrue(rule.contains("do not guess"));
    }

    // ── Provenance/sufficiency rule: existence-knowledge, a relationship/JOIN hint, and an
    //     individually-learned column are each explicitly distinguished from actually having
    //     received that object's own metadata ────────────────────────────────────────────────

    @Test
    void systemPromptDistinguishesKnowingAnObjectExistsFromHavingItsMetadata() throws Exception {
        String rule = metadataRequestRuleText();

        assertTrue(rule.contains("Knowing that an object exists is NOT the same as having its metadata"));
    }

    @Test
    void systemPromptRefusesToTreatAJoinHintAsCompleteAttributeMetadata() throws Exception {
        String rule = metadataRequestRuleText();

        assertTrue(rule.toLowerCase(java.util.Locale.ROOT).contains("relationship/join"));
        assertTrue(rule.contains("does not constitute having that object's metadata"));
        assertTrue(rule.contains("must never be treated as that object's complete attribute list"));
    }

    @Test
    void systemPromptForbidsSelectingOrInventingOtherColumnsFromAJoinHintAlone() throws Exception {
        String rule = metadataRequestRuleText();

        assertTrue(rule.contains("must never be used as a basis to select or invent any other column"));
    }

    @Test
    void systemPromptGivesNoDomainSpecificExampleForTheMetadataRequestRule() throws Exception {
        // The rule and its JSON shape must generalize to any tenant/industry/table/column —
        // pin that the rule text itself never names a specific business domain, object, or
        // column — including the exact terms this rule was written in response to.
        String rule = metadataRequestRuleText();
        String lower = rule.toLowerCase(java.util.Locale.ROOT);

        for (String forbidden : List.of("purchase order", "supplier", "retail", "product",
                "item", "quantity", "sku", "amount", "date", "status")) {
            assertFalse(lower.contains(forbidden), "rule text must not mention '" + forbidden + "'");
        }
    }

    // ── Identity precedence: "Approved schema" is the ONLY authoritative source for
    //     requires_metadata.object — a Knowledge Graph entity/concept label must never be
    //     substituted for it, even when differently worded (singular/plural/etc.) ──────────────

    @Test
    void systemPromptEstablishesApprovedSchemaAsTheOnlyAuthoritativeIdentitySource() throws Exception {
        String rule = metadataRequestRuleText();

        assertTrue(rule.contains("MUST be the exact table name or exact business name"));
        assertTrue(rule.contains("ONLY authoritative identity source"));
    }

    @Test
    void systemPromptForbidsSubstitutingAKnowledgeGraphLabelForTheApprovedSchemaIdentity() throws Exception {
        String rule = metadataRequestRuleText();

        assertTrue(rule.contains("Knowledge Graph"));
        assertTrue(rule.contains("must never be substituted for the corresponding"));
        assertTrue(rule.contains("singular, plural, or otherwise"),
                "the rule must explicitly cover wording differences (e.g. singular vs. plural), "
                        + "not just an arbitrary label mismatch");
    }

    @Test
    void systemPromptTellsTheModelJavaWillNotNormalizeAKnowledgeGraphLabelForIt() throws Exception {
        String rule = metadataRequestRuleText();
        assertTrue(rule.toLowerCase(java.util.Locale.ROOT)
                .contains("do not expect java to"));
        assertTrue(rule.toLowerCase(java.util.Locale.ROOT)
                .contains("match, normalize, or correct"));
    }

    @Test
    void identityPrecedenceRuleIsDomainNeutral() throws Exception {
        String rule = metadataRequestRuleText();
        String lower = rule.toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : List.of("purchase order", "supplier", "retail", "product",
                "item", "quantity", "sku", "healthcare", "customer", "employee", "account")) {
            assertFalse(lower.contains(forbidden), "identity-precedence rule must not mention '" + forbidden + "'");
        }
    }

    @Test
    void requiresMetadataJsonShapeIsUnchangedByTheIdentityPrecedenceRule() throws Exception {
        // No new/renamed fields — still exactly {"object": ..., "metadataType": ...}.
        String rule = metadataRequestRuleText();
        assertTrue(rule.contains(
                "\"requires_metadata\":{\"object\":\"<the exact table name, or business name, "
                        + "as shown in Approved schema>\",\"metadataType\":\"columns\"}"));
    }

    // ── Reinforcing rule: finalizing an entity-identifying answer requires descriptive
    //     evidence, not merely the entity's identifier (even one learned via JOIN) ─────────────

    @Test
    void systemPromptReinforcesThatFinalizingAnEntityAnswerRequiresDescriptiveEvidence() throws Exception {
        String rule = reinforcingRuleText();

        assertTrue(rule.contains("identifies, names, or describes an entity"));
        assertTrue(rule.contains("descriptive attribute"));
        assertTrue(rule.contains("does not constitute having sufficient descriptive metadata"));
    }

    @Test
    void systemPromptCoversAnIdentifierLearnedThroughJoinGuidanceInTheReinforcingRuleToo() throws Exception {
        // CASE C: an identifier surfaced via JOIN/relationship guidance still isn't descriptive
        // metadata for that object.
        String rule = reinforcingRuleText();
        assertTrue(rule.toLowerCase(java.util.Locale.ROOT).contains("join"));
    }

    @Test
    void systemPromptReinforcingRuleDirectsToRequiresMetadataNotGuessing() throws Exception {
        String rule = reinforcingRuleText();
        assertTrue(rule.contains("requires_metadata"));
        assertTrue(rule.toLowerCase(java.util.Locale.ROOT).contains("guessing"));
    }

    @Test
    void systemPromptReinforcingRuleIsDomainNeutral() throws Exception {
        String rule = reinforcingRuleText();
        String lower = rule.toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : List.of("purchase order", "product", "item", "supplier",
                "inventory", "quantity", "sku", "healthcare", "retail", "customer", "employee", "account")) {
            assertFalse(lower.contains(forbidden), "reinforcing rule must not mention '" + forbidden + "'");
        }
    }

    // ── Action-framing fix: SQL and requires_metadata are presented as two EQUALLY VALID
    //     actions gated by one explicit test ("confirmed columns for every referenced table?"),
    //     rather than SQL as the default job and requires_metadata as a secondary exception.
    //     This addresses the RCA-confirmed structural cause of inconsistent metadata-request
    //     compliance — the rule CONTENT was already correct; only the framing changed.
    //
    //     IMPORTANT — what these tests do and do not prove: these are prompt-text pins (this
    //     session's established convention). They prove the (a)/(b) framing is present and
    //     that the JSON contract is unchanged. They do NOT, and cannot, prove that a live model
    //     will always choose correctly between (a) and (b) for a given evidence state — that is
    //     exactly the judgment Agent Brain owns and this repo's tests never simulate. Where a
    //     scripted-response test is used below, it proves Java's PARSING is correct for a given
    //     LLM output, not that the model reliably produces that output. ──────────────────────

    @Test
    void systemPromptPresentsSqlAndMetadataRequestAsTwoEquallyValidActions() throws Exception {
        String prompt = plannerSystemPrompt();

        assertTrue(prompt.contains("choose exactly ONE of two equally valid actions"),
                "the opening framing must present SQL and requires_metadata as co-equal actions, "
                        + "not SQL as the default job with requires_metadata as a secondary exception");
        assertTrue(prompt.contains("only when you already have confirmed columns"),
                "SQL must be explicitly gated on confirmed columns for every referenced table");
        assertTrue(prompt.contains("is not a shortcut to"),
                "the framing must explicitly reject 'produce SQL anyway' as a valid interpretation "
                        + "of the planner's job when columns are unconfirmed");
    }

    @Test
    void jsonResponseShapesAreFramedAsCoordinateOptionsNotDefaultPlusExceptions() throws Exception {
        String prompt = plannerSystemPrompt();

        // The old "OR, when..." exception-chain wording must be gone from the response-shape
        // section — replaced by a shape list keyed to the (a)/(b)/clarification/done choice
        // already made in the opening framing.
        assertFalse(prompt.contains("OR, when you need a table's columns"),
                "the old default-plus-exception JSON framing must be replaced");
        assertTrue(prompt.contains("(a) SQL:"));
        assertTrue(prompt.contains("(b) Metadata request"));
    }

    @Test
    void requiresMetadataJsonContractIsUnchangedByTheFramingFix() throws Exception {
        // The contract itself — field names and shape — must be byte-identical to before this
        // fix: {"object": ..., "metadataType": ...}. Only the surrounding framing changed.
        String prompt = plannerSystemPrompt();
        assertTrue(prompt.contains(
                "\"requires_metadata\":{\"object\":\"table_name\",\"metadataType\":\"columns\"}"));
        assertTrue(prompt.contains(
                "\"requires_metadata\":{\"object\":\"<the exact table name, or business name, "
                        + "as shown in Approved schema>\",\"metadataType\":\"columns\"}"));
    }

    @Test
    void sqlShapeAndFieldsAreUnchangedByTheFramingFix() throws Exception {
        // The SQL response shape's own fields (sql/connection_key/object_keys/rationale/
        // literal_bindings) must also be untouched — this fix reframes when each shape applies,
        // never what either shape contains.
        String prompt = plannerSystemPrompt();
        assertTrue(prompt.contains(
                "\"sql\":\"SELECT ...\",\"connection_key\":\"conn-xxxxxxxx\",\"object_keys\":\"key1,key2\""));
    }

    @Test
    void actionFramingFixIsDomainNeutral() throws Exception {
        String prompt = plannerSystemPrompt();
        int start = prompt.indexOf("Every turn, choose exactly ONE of two equally valid actions");
        assertTrue(start >= 0);
        int end = prompt.indexOf("Rules:", start);
        String framing = prompt.substring(start, end).toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : List.of("purchase order", "product", "item", "supplier",
                "inventory", "quantity", "sku", "healthcare", "retail", "customer", "employee", "account")) {
            assertFalse(framing.contains(forbidden), "action-framing text must not mention '" + forbidden + "'");
        }
    }

    @Test
    void scriptedSqlResponseStillParsesCorrectlyAfterTheFramingFix() {
        // Regression, not a new capability: a well-formed SQL response (columns confirmed) must
        // still parse as SQL, not be mis-routed to a metadata request by the reframing.
        aiClient.scriptedResponse = """
                {"done":false,"description":"list widgets","sql":"SELECT id FROM widgets","connection_key":"conn-1","rationale":"columns confirmed"}""";

        ReasoningPlanner.StepPlan plan = planner.nextStep("q", "schema context", evidence);

        assertNotNull(plan);
        assertFalse(plan.isMetadataRequest());
        assertEquals("SELECT id FROM widgets", plan.sql());
    }

    @Test
    void scriptedMetadataRequestResponseStillParsesCorrectlyAfterTheFramingFix() {
        // Regression: a well-formed requires_metadata response must still parse as a metadata
        // request after the reframing — proves Java's parsing of the (b) shape is unaffected.
        aiClient.scriptedResponse = """
                {"done":false,"description":"need columns","requires_metadata":{"object":"widgets","metadataType":"columns"},"rationale":"columns omitted for budget"}""";

        ReasoningPlanner.StepPlan plan = planner.nextStep("q", "schema context", evidence);

        assertNotNull(plan);
        assertTrue(plan.isMetadataRequest());
        assertEquals("widgets", plan.metadataRequest().object());
    }

    /** Isolates the metadata-request rule's own text (between its heading and the next rule). */
    private static String metadataRequestRuleText() throws Exception {
        String systemPrompt = plannerSystemPrompt();
        int ruleStart = systemPrompt.indexOf("Before generating SQL, ensure that any resolved object");
        assertTrue(ruleStart >= 0, "metadata-request rule not found in SYSTEM_PROMPT");
        int ruleEnd = systemPrompt.indexOf("Before finalizing an answer that identifies", ruleStart);
        assertTrue(ruleEnd >= 0, "reinforcing rule not found after the metadata-request rule");
        return systemPrompt.substring(ruleStart, ruleEnd);
    }

    /** Isolates the reinforcing "finalize an entity answer" rule's own text. */
    private static String reinforcingRuleText() throws Exception {
        String systemPrompt = plannerSystemPrompt();
        int ruleStart = systemPrompt.indexOf("Before finalizing an answer that identifies");
        assertTrue(ruleStart >= 0, "reinforcing rule not found in SYSTEM_PROMPT");
        int ruleEnd = systemPrompt.indexOf("Joins, aggregations", ruleStart);
        return systemPrompt.substring(ruleStart, ruleEnd);
    }

    private static String plannerSystemPrompt() throws Exception {
        java.lang.reflect.Field f = ReasoningPlanner.class.getDeclaredField("SYSTEM_PROMPT");
        f.setAccessible(true);
        return (String) f.get(null);
    }
}
