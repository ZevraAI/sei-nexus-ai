package com.sei.nexus.chat;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the corrected FOLLOW_UP_QUESTIONS / "follow_up_questions_reasoning" semantics inside
 * {@code ChatService}'s {@code DATA_ANSWER_JSON_SYSTEM_PROMPT} — the renamed successor to the
 * former {@code next_steps} / {@code next_steps_reasoning} contract (intentional, no
 * backward-compatible alias; see the contract-rename task this file was renamed for).
 *
 * <p>The underlying defect this closes: the prompt was encouraging the model to reason about
 * whether the current dataset is "complete" or whether there is "no further angle to drill into"
 * before proposing a follow-up question — but FOLLOW_UP_QUESTIONS represents plausible questions a
 * user might naturally ask next, not a determination that the current answer is missing
 * something, and not an investigation step, action, or recommendation for Zevra itself. A live
 * response for "show me all open purchase orders" showed {@code next_steps_reasoning} justifying
 * an empty list with "the dataset provides a complete list... with no further angle to drill
 * into" — exactly the confused framing this test guards against regressing, now under the
 * renamed, semantically corrected field/section names.
 *
 * <p>This test cannot — and does not attempt to — verify LLM compliance, only that the corrected
 * instruction is present in what the model is given (same posture as
 * {@code ReasoningPlannerPromptTest}).</p>
 */
class DataAnswerFollowUpQuestionsSemanticsPromptTest {

    // Text-block source wraps prose across multiple source lines, each line break becoming a
    // literal \n in the compiled constant — normalized to single spaces so assertions aren't
    // fragile to exactly where a phrase happens to wrap in the source.
    private static String systemPrompt() throws Exception {
        Field f = ChatService.class.getDeclaredField("DATA_ANSWER_JSON_SYSTEM_PROMPT");
        f.setAccessible(true);
        return ((String) f.get(null)).replaceAll("\\s+", " ");
    }

    @Test
    void followUpQuestionsAreOptionalAndSpeculativeNotACompletenessJudgment() throws Exception {
        String p = systemPrompt();
        assertTrue(p.contains("optional and speculative BY DESIGN"));
        assertTrue(p.contains("do not represent required actions"));
        assertTrue(p.contains("NOT a determination that the current result is incomplete"));
        assertTrue(p.contains(
                "You do NOT need to establish that the dataset is incomplete, insufficient, or "
                        + "missing something before proposing a follow-up question"));
    }

    @Test
    void followUpQuestionsAreNotInvestigationStepsOrActionsForZevra() throws Exception {
        String p = systemPrompt();
        assertTrue(p.contains(
                "NOT investigation steps, reasoning steps, tasks, actions, recommendations, "
                        + "required next actions, or workflow instructions for Zevra"));
        assertTrue(p.contains(
                "\"what questions might this user naturally ask next?\" → FOLLOW-UP QUESTIONS"));
        assertTrue(p.contains(
                "\"what should Zevra/the investigation do next?\" → actions/investigation steps"));
    }

    @Test
    void followUpQuestionsMustBeGroundedButNeverInventUnavailableCapabilities() throws Exception {
        String p = systemPrompt();
        assertTrue(p.contains("must still be grounded in the available investigation evidence"));
        assertTrue(p.contains("do not invent an unavailable dataset, fact, metric, or capability"));
        assertTrue(p.contains(
                "do NOT turn FOLLOW_UP_QUESTIONS into generic advice such as \"review the data,\" "
                        + "\"take action,\" \"would you like more information?\", or \"should I "
                        + "analyze this further?\""));
    }

    @Test
    void completenessOfCurrentAnswerIsNotByItselfAReasonToOmitFollowUpQuestions() throws Exception {
        String p = systemPrompt();
        assertTrue(p.contains(
                "do not omit a genuinely useful FOLLOW_UP_QUESTIONS section merely because the current dataset is complete"));
        assertTrue(p.contains(
                "completeness of the current answer and usefulness of a follow-up question are "
                        + "unrelated"));
        assertTrue(p.contains(
                "it can be useful even when the current answer is already complete"));
    }

    @Test
    void omittingTheSectionEntirelyIsTheOnlyValidWayToExpressNoUsefulFollowUps() throws Exception {
        // Structural fix (this turn): there is no longer a top-level reasoning field to justify an
        // empty follow-up list with prose — the model either emits the section (items + reasoning
        // together) or omits it entirely; it may never emit an empty "items" array.
        String p = systemPrompt();
        assertTrue(p.contains(
                "If no useful follow-up questions exist, OMIT the FOLLOW_UP_QUESTIONS section "
                        + "entirely"));
        assertTrue(p.contains("never include one with an empty \"items\" array"));
    }

    @Test
    void reasoningIsStructurallyAttachedToTheSameSectionAsItsItems() throws Exception {
        // This turn's structural fix: "reasoning" lives on the FOLLOW_UP_QUESTIONS section object
        // itself (alongside "items"), never as an independent top-level field — the RCA'd defect
        // this whole task closes.
        String p = systemPrompt();
        assertTrue(p.contains("\"reasoning\" (this section's other field)"));
        assertTrue(p.contains("It must describe the ACTUAL items emitted in this same section, "
                + "never a different or hypothetical set of questions"));
        assertTrue(p.contains(
                "Do NOT write \"reasoning\" describing candidate follow-up questions without also "
                        + "emitting those same questions in \"items\""));
    }

    @Test
    void bothItemsAndReasoningMustBePopulatedTogetherWhenFollowUpsAreUseful() throws Exception {
        String p = systemPrompt();
        assertTrue(p.contains(
                "emit the FOLLOW_UP_QUESTIONS section with both \"items\" (the actual questions) "
                        + "and \"reasoning\" (why they're useful) populated"));
    }

    @Test
    void schemaAndPromptUseFollowUpQuestionsTerminologyNotLegacyNextSteps() throws Exception {
        String p = systemPrompt();
        assertFalse(p.contains("next_steps"), "the legacy next_steps field name must not remain in the prompt");
        assertFalse(p.contains("\"NEXT_STEPS\""), "the legacy NEXT_STEPS section type must not remain in the prompt");
        assertTrue(p.contains("\"FOLLOW_UP_QUESTIONS\""));
        // The former independent top-level field is gone completely — not merely renamed, removed.
        assertFalse(p.contains("follow_up_questions_reasoning"),
                "the former top-level follow_up_questions_reasoning field name must not remain in the prompt");
    }
}
