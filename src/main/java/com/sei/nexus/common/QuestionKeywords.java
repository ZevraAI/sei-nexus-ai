package com.sei.nexus.common;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single shared keyword extractor for question-driven context filtering.
 *
 * Consolidates the previously duplicated (and drifted) stop-word lists of
 * ChatService.filterGraphContext and AgentRunner.extractKeywords into one
 * list — the union of both. Consumers that interpret the user's question to
 * select context must use this helper rather than growing another copy.
 */
public final class QuestionKeywords {

    private static final Set<String> STOP_WORDS = Set.of(
            "show", "me", "all", "get", "find", "list", "the", "a", "an",
            "what", "which", "how", "many", "give", "tell", "and", "or", "for",
            "is", "are", "was", "were", "of", "in", "on", "at", "to", "with", "from");

    private QuestionKeywords() {}

    /** Extracts meaningful keywords (3+ chars, stop words removed) from a question. */
    public static Set<String> extract(String question) {
        if (question == null || question.isBlank()) return Set.of();
        return Arrays.stream(question.toLowerCase().split("[\\s,?!.;:]+"))
                .filter(w -> w.length() >= 3 && !STOP_WORDS.contains(w))
                .collect(Collectors.toSet());
    }

    /** True when the word is on the shared stop-word list. Used by Business
     *  Language Resolution (PRO-31) to keep stop-word unigrams from matching
     *  index terms and to filter noise from retrieval-expansion tokens. */
    public static boolean isStopWord(String w) {
        return w != null && STOP_WORDS.contains(w);
    }

    /** Naive English singularisation, good enough for table/entity/term matching.
     *  Shared by planner context ranking (PRO-19) and entity candidate retrieval (PRO-22). */
    public static String singular(String w) {
        if (w.endsWith("ies") && w.length() > 4) return w.substring(0, w.length() - 3) + "y";
        if ((w.endsWith("sses") || w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes"))
                && w.length() > 4) return w.substring(0, w.length() - 2);
        if (w.endsWith("s") && !w.endsWith("ss") && w.length() > 3) return w.substring(0, w.length() - 1);
        return w;
    }
}
