package com.sei.nexus.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every non-data outcome prompt carries the shared analyst voice, without losing its facts. */
class ChatServiceAnswerVoiceTest {

    @Test
    void everyOutcomePromptCarriesTheSharedVoice() {
        List<String> prompts = List.of(
                ChatService.zeroRowSystemPrompt(false),
                ChatService.zeroRowSystemPrompt(true),
                ChatService.failedQuerySystemPrompt(),
                ChatService.blockedQuerySystemPrompt(),
                ChatService.validationFailedSystemPrompt(),
                ChatService.noQueryExecutedSystemPrompt(),
                ChatService.clarificationSystemPrompt());
        for (String p : prompts) {
            assertTrue(p.contains("VOICE"), "missing shared voice: " + p);
            assertTrue(p.contains("senior analyst"));
        }
    }

    @Test
    void voiceBlockNeverContradictsAnOutcomeFact() {
        String v = ChatService.ANALYST_VOICE.toLowerCase();
        // Outcome prompts forbid these claims; the shared block must not reintroduce them.
        assertFalse(v.contains("executed successfully"));
        assertFalse(v.contains("governance"));
        assertFalse(v.contains("no records"));
    }

    @Test
    void fixedRepliesStayDistinctAndHonest() {
        String error = ChatService.resultFallbackMessage(false, true, false).toLowerCase();
        String blocked = ChatService.resultFallbackMessage(false, false, true).toLowerCase();
        assertTrue(error.contains("could not be executed"));
        assertFalse(error.contains("blocked"));
        assertTrue(blocked.contains("blocked"));
        assertFalse(blocked.contains("could not be executed"));
    }
}
