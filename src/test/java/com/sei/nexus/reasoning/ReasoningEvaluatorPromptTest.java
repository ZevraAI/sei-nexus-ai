package com.sei.nexus.reasoning;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the conversational result-set reuse correctness contract in {@link
 * ReasoningEvaluator}'s {@code SYSTEM_PROMPT} — same convention as {@code
 * ConceptScopedMetadataResolverFileSearchTest#fileSearchSystemPromptNeverMentionsInvestigationHints}:
 * a prompt-text regression guard, not a live-model test. Guards against the prompt drifting back
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
}
