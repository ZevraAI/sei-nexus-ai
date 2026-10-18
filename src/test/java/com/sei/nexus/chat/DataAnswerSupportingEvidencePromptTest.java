package com.sei.nexus.chat;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the answer-writing prompt tells the model how to present a dataset gathered only to
 * verify what values exist: as supporting evidence, captioned for what it literally shows, never as
 * the result and never with a conclusion the rows cannot show. Cannot verify LLM compliance, only that
 * the instruction is in what the model is given.
 */
class DataAnswerSupportingEvidencePromptTest {

    private static String prompt() throws Exception {
        Field f = ChatService.class.getDeclaredField("DATA_ANSWER_JSON_SYSTEM_PROMPT");
        f.setAccessible(true);
        return ((String) f.get(null)).replaceAll("\\s+", " ");
    }

    @Test
    void verificationDatasetsAreNotDisplayedBesideTheAnswer() throws Exception {
        String p = prompt();
        assertTrue(p.contains("SUPPORTING EVIDENCE"));
        assertTrue(p.contains("it is not the answer"));
        assertTrue(p.contains("Do not display it"));
        assertTrue(p.contains("it stays visible in the reasoning trace"));
        assertTrue(p.contains("no row has it right now, not that it is invalid or unused"));
        assertTrue(p.contains("Display such a dataset only when the user explicitly asked for those values"));
    }
}
