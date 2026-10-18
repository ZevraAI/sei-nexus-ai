package com.sei.nexus.agentrunner;

import com.sei.nexus.common.NexusException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZevraPlaybookTest {

    private static ZevraAgent agent() {
        return new ZevraAgent("agent-1", "public", "Ops Analyst", "ops-analyst", "desc",
                "You are an analyst.", "Answer questions.", List.of("conn"), 5, "ACTIVE",
                "u@x.com", null, null);
    }

    private static ZevraPlaybook playbook(String name, String evidence, String escalation) {
        return new ZevraPlaybook("pb-1", "agent-1", "public", name, "a stock-out is reported",
                "1. Find the item. 2. Check stock by location.", evidence, escalation, "ACTIVE",
                "u@x.com", null, null);
    }

    @Test
    void agentWithoutPlaybooksGetsTheSamePromptAsBefore() {
        String plain = AgentRunner.buildSystemPrompt(agent(), "GROUNDING");
        assertEquals(plain, AgentRunner.buildSystemPrompt(agent(), "GROUNDING", List.of()));
        assertFalse(plain.contains("PLAYBOOKS"));
    }

    @Test
    void playbooksAreShownToTheModelWhichDecidesWhetherOneApplies() {
        String prompt = AgentRunner.buildSystemPrompt(agent(), "GROUNDING",
                List.of(playbook("Stock-out triage", "inventory first, then orders", "stock is zero everywhere")));
        assertTrue(prompt.contains("PLAYBOOKS"));
        assertTrue(prompt.contains("Stock-out triage"));
        assertTrue(prompt.contains("Use when: a stock-out is reported"));
        assertTrue(prompt.contains("Steps: 1. Find the item."));
        assertTrue(prompt.contains("Evidence order: inventory first, then orders"));
        assertTrue(prompt.contains("Escalate / flag when: stock is zero everywhere"));
        assertTrue(prompt.contains("if none matches, ignore them all"));
        assertTrue(prompt.indexOf("GROUNDING") < prompt.indexOf("PLAYBOOKS"), "grounding comes first");
        assertTrue(prompt.contains("CRITICAL: You may ONLY query"), "safety rule must still be present");
    }

    @Test
    void optionalFieldsAreOmittedWhenBlank() {
        String text = AgentRunner.renderPlaybooks(List.of(playbook("P", null, null)));
        assertFalse(text.contains("Evidence order"));
        assertFalse(text.contains("Escalate"));
    }

    @Test
    void validationRequiresNameTriggerAndSteps() {
        assertThrows(NexusException.class,
                () -> ZevraAgentService.validated(null, "a", "t", " ", "w", "s", null, null, "u"));
        assertThrows(NexusException.class,
                () -> ZevraAgentService.validated(null, "a", "t", "n", "", "s", null, null, "u"));
        assertThrows(NexusException.class,
                () -> ZevraAgentService.validated(null, "a", "t", "n", "w", null, null, null, "u"));
    }

    @Test
    void validationNeverNeedsAStepBudgetAndNormalisesOptionalFields() {
        ZevraPlaybook p = ZevraAgentService.validated(null, "a", "t", " Name ", "w", "s", "  ", "", "u");
        assertNotNull(p.id());
        assertEquals("Name", p.name());
        assertNull(p.evidenceOrder());
        assertNull(p.escalation());
        assertEquals("ACTIVE", p.status());
    }
}
