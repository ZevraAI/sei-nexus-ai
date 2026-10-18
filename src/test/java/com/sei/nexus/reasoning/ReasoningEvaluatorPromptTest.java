package com.sei.nexus.reasoning;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the conversational result-set reuse correctness contract in {@link
 * ReasoningEvaluator}'s {@code SYSTEM_PROMPT} — a prompt-text
 * regression guard, not a live-model test. Guards against the prompt drifting back
 * to conflating "answerable from existing evidence" with "existing evidence is the correct
 * result set" — the exact defect {@link FollowUpFilterChangeReuseTest} exercises end-to-end with
 * scripted evaluator behavior.
 */
class ReasoningEvaluatorPromptTest {

    private static String systemPrompt() throws Exception {
        Field f = ReasoningEvaluator.class.getDeclaredField("SYSTEM_PROMPT");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    @Test
    void promptDistinguishesAnswerableFromCorrectResultSet() throws Exception {
        String prompt = systemPrompt().toLowerCase();
        assertTrue(prompt.contains("answerable"), "prompt must name the 'answerable' axis explicitly");
        assertTrue(prompt.contains("result set") || prompt.contains("result-set"),
                "prompt must name the 'correct result set' axis explicitly");
    }

    @Test
    void promptStatesReadingThroughEvidenceIsNotEnough() throws Exception {
        String prompt = systemPrompt().toLowerCase();
        assertTrue(prompt.contains("even if you personally could read through the existing rows")
                        || prompt.contains("computing an answer from the existing rows"),
                "prompt must explicitly reject 'I can compute the right answer by reading the rows' "
                        + "as a substitute for actually returning the correct result set");
    }

    @Test
    void promptDecomposesTheJudgmentIntoASeparateResultSetMatchesField() throws Exception {
        String prompt = systemPrompt();
        assertTrue(prompt.contains("\"resultSetMatches\""),
                "the judgment must be decomposed into an explicit, separately-answered field — "
                        + "live validation showed a single holistic 'decision' label was not reliably "
                        + "followed by the real model even with explicit prose instructions");
        assertTrue(prompt.contains("\"decision\": \"SUFFICIENT | NEED_MORE_DATA | DEAD_END\""),
                "the decision enum itself must be unchanged — no new decision value introduced");
        assertTrue(prompt.contains("\"rationale\""));
    }

    @Test
    void promptListsFilterGroupingSortLimitAsResultSetChanges() throws Exception {
        String prompt = systemPrompt().toLowerCase();
        for (String term : new String[] {"filter", "grouping", "sort", "limit"}) {
            assertTrue(prompt.contains(term), "prompt must name '" + term + "' as a result-set-changing dimension");
        }
    }

    // ── Semantic-Answerability rule (identifier ≠ descriptive evidence for an entity question) ─

    @Test
    void promptRequiresSemanticAnswerabilityNotJustResultSetShape() throws Exception {
        String prompt = systemPrompt().toLowerCase();
        assertTrue(prompt.contains("semantically answerable"),
                "the prompt must name the semantic-answerability axis explicitly — a correct "
                        + "result-set shape is not by itself sufficient");
        assertTrue(prompt.contains("opaque identifier"),
                "the prompt must name the specific failure mode: an opaque identifier standing "
                        + "in for descriptive evidence");
    }

    @Test
    void promptDistinguishesAnIdentifierFromDescriptiveEvidenceForEntityQuestions() throws Exception {
        String prompt = systemPrompt().toLowerCase();
        assertTrue(prompt.contains("identify, name, or describe"),
                "the rule must apply to the general class of entity-identifying questions, not "
                        + "one specific phrasing");
        assertTrue(prompt.contains("not necessarily sufficient")
                        || prompt.contains("is not yet sufficient"),
                "the prompt must state that an identifier does not automatically satisfy an "
                        + "entity-identification question");
    }

    @Test
    void promptCoversAnIdentifierLearnedViaJoinRelationshipToo() throws Exception {
        // CASE C: a JOIN/relationship reference exposes an identifier for another object, but
        // that object's own descriptive metadata has not been retrieved — the identifier must
        // still not count as sufficient descriptive evidence.
        String prompt = systemPrompt().toLowerCase();
        assertTrue(prompt.contains("join"),
                "the rule must explicitly cover an identifier learned via a JOIN/relationship "
                        + "reference, not only one obtained by direct query");
    }

    @Test
    void promptDoesNotRequireMoreEvidenceMerelyBecauseAnIdentifierExists() throws Exception {
        // The rule must be narrow: only trigger when the question specifically asks to
        // identify/name/describe an entity and no descriptive value was gathered — never a
        // blanket "always fetch more when you see an identifier" rule.
        String prompt = systemPrompt().toLowerCase();
        assertTrue(prompt.contains("do not require further evidence merely because"),
                "the rule must explicitly avoid over-triggering on the mere presence of an "
                        + "identifier column");
    }

    @Test
    void promptForbidsInventingOrAssumingConventionalColumnsToFillTheGap() throws Exception {
        String prompt = systemPrompt().toLowerCase();
        assertTrue(prompt.contains("do not assume a conventional column name exists or invent one"),
                "the evaluator must never fabricate or assume a descriptive column exists — it "
                        + "can only judge the evidence actually gathered");
    }

    @Test
    void promptRemainsDomainNeutral() throws Exception {
        String prompt = systemPrompt().toLowerCase();
        for (String forbidden : new String[] {"purchase order", "product", "item", "supplier",
                "inventory", "quantity", "sku", "healthcare", "retail", "customer", "employee", "account"}) {
            assertFalse(prompt.contains(forbidden),
                    "evaluator prompt must not mention the domain-specific term '" + forbidden + "'");
        }
    }

    @Test
    void jsonContractIsUnchangedByTheSemanticAnswerabilityRule() throws Exception {
        // The rule is folded into the existing "decision" judgment's guidance text — no new
        // output field, no new decision value. SUFFICIENT/NEED_MORE_DATA/DEAD_END and
        // resultSetMatches/decision/rationale remain exactly as before.
        String prompt = systemPrompt();
        assertTrue(prompt.contains("\"resultSetMatches\": true"));
        assertTrue(prompt.contains("\"decision\": \"SUFFICIENT | NEED_MORE_DATA | DEAD_END\""));
        assertTrue(prompt.contains("\"rationale\": \"one sentence explaining your decision\""));
        // The semantic-answerability rule added no field. The zero-row rule later added exactly
        // one — zeroRowResultConfirmed, the model's own narrow self-report that Java clamps on
        // (see ReasoningEvaluatorZeroRowClampTest) — so the declared shape is now exactly 4.
        assertTrue(prompt.contains("\"zeroRowResultConfirmed\": null"));
        int braceOpen = prompt.indexOf("Return JSON only:");
        int braceClose = prompt.indexOf("}", braceOpen);
        String responseShape = prompt.substring(braceOpen, braceClose + 1);
        assertEquals(4, responseShape.split("\":").length - 1,
                "the declared JSON response shape must have exactly 4 fields");
    }

    @Test
    void aBreakdownByAnEntityIsHeldToTheSameReadableIdentificationStandard() throws Exception {
        String prompt = systemPrompt().replaceAll("\\s+", " ");
        assertTrue(prompt.contains("break results down by, group by, or list them per an entity"),
                "grouping or listing BY an entity is identifying that entity: an opaque key is not enough");
        assertTrue(prompt.contains("break results down by an entity and the evidence gathered contains only an opaque identifier"),
                "NEED_MORE_DATA case (d) must cover the breakdown case too");
    }

    @Test
    void aResultThatSilentlyCoversOnlyPartOfTheDataIsNotSufficient() throws Exception {
        String n = systemPrompt().replaceAll("\\s+", " ");
        assertTrue(n.contains("COMPLETENESS."));
        assertTrue(n.contains("Read the SQL: if it narrowed the data in a way the question did not ask for"));
        assertTrue(n.contains("keeps only one of several values of a type or category column"));
        assertTrue(n.contains("so the evidence is not yet sufficient (NEED_MORE_DATA)"));
        assertTrue(n.contains("Narrowing the question itself asked for is fine."),
                "a restriction the user actually asked for must not be treated as a gap");
    }

    @Test
    void opacityIsJudgedFromTheSampleValuesAndTheShapeIsDescribedConcretely() throws Exception {
        String n = systemPrompt().replaceAll("\\s+", " ");
        assertTrue(n.contains("Judge opacity from the sample values in the result summary"));
        assertTrue(n.contains("a generated unique id (a UUID, a long surrogate number) is opaque"));
        assertTrue(n.contains("while a name, title or familiar code is not"));
        // it must still not ask for a conventional column to be invented
        assertTrue(n.contains("Do not assume a conventional column name exists or invent one"));
    }
}
