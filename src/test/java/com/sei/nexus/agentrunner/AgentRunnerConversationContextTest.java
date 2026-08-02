package com.sei.nexus.agentrunner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production hardening — the routed-agent path must carry recent conversation turns so a follow-up
 * resolves referents introduced in an earlier answer (e.g. "that region"). Verified on the message
 * composition seam; the current question stays in messages[0] so pruning never drops it.
 */
class AgentRunnerConversationContextTest {

    @Test
    void followUpPrependsConversationContextAndKeepsTheQuestion() {
        String ctx = "=== RECENT CONVERSATION ===\n- User asked: which region has the most stores?\n"
                + "  Zevra answered: The Southeast region, with 5 stores.\n";
        String msg = AgentRunner.composeFirstMessage(ctx, "list the stores in that region");

        assertTrue(msg.contains("Southeast"),
                "the referent from the prior answer is available so 'that region' resolves");
        assertTrue(msg.contains("Current question: list the stores in that region"),
                "the current question is clearly delimited within the single first message");
    }

    @Test
    void firstTurnUsesTheRawQuestionUnchanged() {
        assertEquals("show me all products",
                AgentRunner.composeFirstMessage(null, "show me all products"),
                "no prior context ⇒ the agent sees exactly the question, unchanged");
        assertEquals("show me all products",
                AgentRunner.composeFirstMessage("   ", "show me all products"),
                "blank context is treated as no context");
    }
}
