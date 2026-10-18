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
    void verificationDatasetsArePresentedAsSupportingEvidenceNotAsTheResult() throws Exception {
        String p = prompt();
        assertTrue(p.contains("SUPPORTING EVIDENCE"));
        assertTrue(p.contains("it is not the answer"));
        assertTrue(p.contains("never a conclusion the rows cannot show by themselves"));
        assertTrue(p.contains("no row has it right now, not that it is invalid or unused"));
        assertTrue(p.contains("Never present it as the result of the user's question"));
    }
}
