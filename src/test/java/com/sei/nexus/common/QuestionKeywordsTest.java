package com.sei.nexus.common;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-19 — the single shared keyword extractor that consolidates the previously
 * drifted stop-word lists of ChatService.filterGraphContext and
 * AgentRunner.extractKeywords.
 */
class QuestionKeywordsTest {

    @Test
    void extractsMeaningfulTermsAndDropsStopWords() {
        assertEquals(Set.of("texas", "stores"),
                QuestionKeywords.extract("Show me all the Texas stores"));
    }

    @Test
    void dropsShortTokensAndUnionStopWords() {
        // "are", "was", "with", "from" come from the AgentRunner half of the union;
        // "show", "the" from the ChatService half; 1-2 char tokens always dropped.
        // "that" is in neither legacy list — consolidation is the exact union,
        // so it must survive (no behavior invented beyond the two sources).
        assertEquals(Set.of("invoices", "that", "overdue"),
                QuestionKeywords.extract("Show me the invoices that are overdue, from Q1"));
    }

    @Test
    void blankAndNullQuestionsYieldNoKeywords() {
        assertEquals(Set.of(), QuestionKeywords.extract(null));
        assertEquals(Set.of(), QuestionKeywords.extract("   "));
        assertEquals(Set.of(), QuestionKeywords.extract("show me all"));
    }

    @Test
    void splitsOnPunctuation() {
        assertEquals(Set.of("suppliers", "warehouses"),
                QuestionKeywords.extract("suppliers, warehouses?"));
    }
}
