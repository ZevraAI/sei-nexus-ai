package com.sei.nexus.reasoning;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the Planner Reasoning Specification's textual-matching guidance inside
 * {@code ReasoningPlanner}'s system prompt.
 *
 * <p>The live defect: "Show me all bangle products" produced a tolerant
 * {@code ILIKE '%bangle%'} filter while "Show me orders for bangle" produced
 * {@code = 'bangle'} for the identical Product.name reference — matching strategy was
 * being decided by the question's grammatical shape instead of the column's nature.
 * The Planner Reasoning Specification (architecture docs, reviewed and finalized)
 * added explicit guidance to close this gap. This test keeps that guidance from
 * silently regressing; it does not — and cannot — verify LLM compliance, only that
 * the instruction is present in what the model is given.</p>
 */
class ReasoningPlannerPromptTest {

    private static String systemPrompt() throws Exception {
        Field f = ReasoningPlanner.class.getDeclaredField("SYSTEM_PROMPT");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    @Test
    void matchingStrategyIsTiedToColumnNotQuestion() throws Exception {
        String p = systemPrompt();
        assertTrue(p.contains("Matching strategy for a text filter depends on the nature of the column"));
        assertTrue(p.contains("never on how the question is phrased"));
    }

    @Test
    void freeTextColumnsDefaultToTolerantMatching() throws Exception {
        String p = systemPrompt();
        assertTrue(p.contains("For a free-text column"));
        assertTrue(p.contains("legal-values list and no RESOLUTIONS entry for the term, do not assume the"));
        assertTrue(p.contains("user's phrase is the exact stored value"));
        assertTrue(p.contains("possessive apostrophe"));
    }

    @Test
    void identifiersAndCodesRemainExact() throws Exception {
        String p = systemPrompt();
        assertTrue(p.contains("For an identifier or code the user is clearly quoting verbatim"));
        assertTrue(p.contains("Do not apply tolerant matching to numbers"));
    }

    @Test
    void projectionGuidanceSelectsRelevantColumnsNotWildcard() throws Exception {
        // The defect: "SELECT * FROM purchase_order_lines WHERE product_id = '...'" was
        // Planner-generated and Runtime-rejected (SqlSafetyService Rule 4). The prior prompt
        // only prohibited SELECT * without saying how to choose columns instead.
        String p = systemPrompt();
        assertTrue(p.contains("Select the columns whose"));
        assertTrue(p.contains("business meaning is relevant to the user's question"));
        assertTrue(p.contains("using each column's role"));
        assertTrue(p.contains("not every available column, and"));
        assertTrue(p.contains("never the * wildcard"));
    }

    @Test
    void focusedMeansWellChosenNotMinimal() throws Exception {
        // The "turtleneck open orders" defect: "show me all open orders..." produced
        // SELECT po_number alone — a single identifier column, technically "focused" per the
        // prior wording but not a useful answer to a "show me" question. The prompt now
        // distinguishes an explicit identifier request from a general "show me" request.
        String p = systemPrompt();
        assertTrue(p.contains("\"Focused\" means well-chosen, not minimal"));
        assertTrue(p.contains("asks only for an identifier or code by name"));
        assertTrue(p.contains("that column alone is enough"));
        assertTrue(p.contains("a single identifier column is"));
        assertTrue(p.contains("NOT enough"));
        assertTrue(p.contains("its dimensions,"));
        assertTrue(p.contains("measures, and attributes, not just its identifiers"));
    }

    @Test
    void namedAttributeIsIncludedAlongsideDescriptiveColumns() throws Exception {
        String p = systemPrompt();
        assertTrue(p.contains("If the user names a specific"));
        assertTrue(p.contains("attribute (e.g. \"...and expected delivery date\"), include that column in"));
        assertTrue(p.contains("addition to the identifying and descriptive columns you already chose."));
    }

    @Test
    void fullRecordRequestsEnumerateColumnsExplicitly() throws Exception {
        String p = systemPrompt();
        assertTrue(p.contains("If the user explicitly asks for all fields or the full"));
        assertTrue(p.contains("record, list every approved column of the relevant table by name"));
        assertTrue(p.contains("never use"));
        assertTrue(p.contains("SELECT *."));
    }

    @Test
    void physicalIdentifiersAreAuthoritativeAndRuleGeneralizesBeyondOneExample() throws Exception {
        // The "ordered_date" defect: the Planner substituted the common ERP convention
        // "order_date" for the approved physical column "ordered_date", even though the prior
        // instruction already forbade this — but only ever demonstrated it via one worked
        // example (on_hand_qty). This pins the generalized rule that applies to every
        // identifier, not just ones resembling that one example.
        String p = systemPrompt();
        assertTrue(p.contains("PHYSICAL IDENTIFIERS ARE AUTHORITATIVE"));
        assertTrue(p.contains("not just ones that resemble a known example"));
        assertTrue(p.contains("natural-language wording, business terminology, common"));
        assertTrue(p.contains("ERP terminology, common SQL/database naming conventions"));
        assertTrue(p.contains("more familiar or more \"correct\" name for that concept"));
    }

    @Test
    void identifierRulesSeparateMeaningInterpretationFromPhysicalLookup() throws Exception {
        String p = systemPrompt();
        assertTrue(p.contains("Reason in two separate steps, and do not collapse them into one"));
        assertTrue(p.contains("interpret what the"));
        assertTrue(p.contains("user means — the business concept"));
        assertTrue(p.contains("find the physical identifier the schema already"));
        assertTrue(p.contains("assigns to that concept and copy it verbatim"));
        assertTrue(p.contains("Never substitute step 2 with a guess at the"));
        assertTrue(p.contains("conventional name for the concept from step 1"));
        // The pre-existing worked example remains — the rule generalizes around it, not away from it.
        assertTrue(p.contains("If the schema lists `on_hand_qty`, write"));
        assertTrue(p.contains("`on_hand_qty` exactly — never"));
        assertTrue(p.contains("a conventional variant like quantity_on_hand"));
    }

    @Test
    void preExistingLegalValueGuidanceIsUnchanged() throws Exception {
        // Principle 6/Limitations: this specification must not touch enum-domain handling.
        String p = systemPrompt();
        assertTrue(p.contains("Literals filtered on columns with listed legal values MUST be copied exactly"));
        assertTrue(p.contains("RESOLUTIONS map the user's terms to this tenant's canonical names and values"));
        assertTrue(p.contains("literal_bindings"));
    }
}
