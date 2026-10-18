package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prompt economy for the planner (docs/investigations/2026-10-18-llm-cost-analysis.md §8.3). The
 * planner's static system prompt is about two thirds of every planner call, and the planner is
 * called ~2.4 times per question — so its size is a direct cost. The optional breakdown / chart-hint
 * / label rules were condensed WITHOUT dropping any rule or field; this test pins that every one of
 * them is still there, and sets a size ceiling so the prompt cannot silently bloat again.
 */
class PlannerPromptEconomyTest {

    /** Ceiling in characters (~4 chars per token). After the condensing it is 21,910; it was
     *  ~25,950 before. Raise it deliberately, with a cost review. */
    private static final int MAX_SYSTEM_PROMPT_CHARS = 23_100;

    static class CapturingClient extends AzureOpenAiClient {
        String systemPrompt;
        CapturingClient() { super(new ObjectMapper(), null); }
        @Override public String respondForPlanner(List<ChatMessage> m, String sys, String n, Map<String, Object> s) {
            systemPrompt = sys;
            return "{\"done\":true}";
        }
    }

    private static String planner() {
        CapturingClient c = new CapturingClient();
        new ReasoningPlanner(c, new ObjectMapper()).nextStep("q", "schema", new EvidenceStore());
        return c.systemPrompt;
    }

    @Test
    void sizeIsReportedAndStaysUnderTheCeiling() {
        String p = planner();
        System.out.println("PLANNER_SYSTEM_PROMPT_CHARS=" + p.length());
        assertTrue(p.length() <= MAX_SYSTEM_PROMPT_CHARS,
                "planner system prompt grew to " + p.length() + " chars (ceiling " + MAX_SYSTEM_PROMPT_CHARS + ")");
    }

    @Test
    void supplementaryBreakdownRuleKeepsEveryConstraint() {
        String p = planner();
        assertTrue(p.contains("SUPPLEMENTARY BREAKDOWN STEPS (optional, your own judgment)"));
        assertTrue(p.contains("one or two more (never more)"));
        assertTrue(p.contains("RESULT DATA has a natural categorical dimension"));
        assertTrue(p.contains("Decide from the result data alone, never from"));
        assertTrue(p.contains("whether the user's wording mentioned"));
        assertTrue(p.contains("Never for a"));
        assertTrue(p.contains("single-record or single-value lookup"));
        assertTrue(p.contains("never mandatory"));
        assertTrue(p.contains("the LITERAL AUTHORITY RULE"));
        assertTrue(p.contains("the same governance"));
        assertTrue(p.contains("business-facing"));
    }

    @Test
    void chartHintRuleKeepsItsShapeAndAllFourChartTypes() {
        String p = planner();
        assertTrue(p.contains("OPTIONAL \"chart_hint\""));
        for (String field : List.of("chart_type", "category_key", "category_label", "value_keys",
                "value_labels", "metric_label")) {
            assertTrue(p.contains("\"" + field + "\""), "chart_hint field missing from the prompt: " + field);
        }
        for (String type : List.of("\"stats\"", "\"bar\"", "\"donut\"", "\"area\"")) {
            assertTrue(p.contains(type), "chart type missing: " + type);
        }
        assertTrue(p.contains("copied exactly from THIS step's own SELECT list"));
        assertTrue(p.contains("never the raw expression"));
        assertTrue(p.contains("never changes the SQL you write"));
    }

    @Test
    void labelRuleKeepsItsLengthOrderAndPresentationOnlyConstraints() {
        String p = planner();
        assertTrue(p.contains("SAME LENGTH AND ORDER as \"value_keys\""));
        assertTrue(p.contains("plain-English presentation strings only"));
        assertTrue(p.contains("never a"));
        assertTrue(p.contains("business rule or filter"));
        assertTrue(p.contains("relays your labels verbatim"));
    }

    @Test
    void theCondensedRulesAreStillDomainNeutralExamplesAside() {
        String p = planner();
        // The three condensed blocks reuse the same generic examples they always had (status,
        // order_count); nothing tenant- or question-specific was introduced.
        assertFalse(p.toLowerCase().contains("try_retail"));
        assertFalse(p.toLowerCase().contains("continental"));
    }

    @Test
    void referenceKeysNeedLabelsRuleCoversPolymorphicReferences() {
        String n = planner().replaceAll("\\s+", " ");
        assertTrue(n.contains("REFERENCE KEYS NEED LABELS"));
        assertTrue(n.contains("JOIN the object that owns that"));
        assertTrue(n.contains("polymorphic"));
        assertTrue(n.contains("a label join must never drop rows or change totals: use LEFT JOIN"),
                "a label join may not lose rows or change totals");
        assertTrue(n.contains("cover EVERY discriminator value present in the data"),
                "no discriminator value may be silently skipped");
        assertTrue(n.contains("COALESCE their labels"));
        assertTrue(n.contains("Have every candidate's metadata (request it) before writing the SQL"),
                "every candidate object needs its metadata before the SQL is written");
        assertTrue(n.contains("fall back to the raw key only for a row that matches no candidate"),
                "the fallback is for unmatched rows, not an escape from resolving a candidate");
        assertTrue(n.contains("verify first (e.g. SELECT DISTINCT on the discriminator)"),
                "when it cannot tell what the key points at, it investigates instead of guessing");
        String rule = n.substring(n.indexOf("REFERENCE KEYS NEED LABELS"), n.indexOf("If NO evidence exists yet")).toLowerCase();
        for (String forbidden : new String[] {"warehouse", "store", "inventory", "location", "retail"}) {
            assertFalse(rule.contains(forbidden), "the rule must stay domain-neutral: " + forbidden);
        }
    }
}
