package com.sei.nexus.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.response.NaturalLanguageComposer;
import com.sei.nexus.temporal.AnomalyEvent;
import com.sei.nexus.temporal.OperationalBaseline;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified Answer Engine, Phase 4 — Step 4. Alert composes through the shared
 * {@link NaturalLanguageComposer}; this service owns only the alert policy (prompt + system prompt)
 * and the deterministic template fallback. Wired to a hand-rolled fake AI (no network) through a
 * real composer so the whole delegation path is exercised.
 */
class AlertComposerServiceTest {

    static class FakeAi extends AzureOpenAiClient {
        String seenSystem;
        String seenUser;
        boolean fail = false;
        FakeAi() { super(new ObjectMapper(), null); }
        @Override public String chat(List<ChatMessage> messages, String systemPrompt) {
            this.seenSystem = systemPrompt;
            this.seenUser = messages.get(0).content();
            if (fail) throw new RuntimeException("model down");
            return "Inventory turnover dropped 32% below its 90-day baseline; review procurement.";
        }
    }

    private AlertComposerService service(FakeAi ai) {
        return new AlertComposerService(new NaturalLanguageComposer(ai));
    }

    private AnomalyEvent anomaly() {
        return new AnomalyEvent("an-1", "bl-1", "dom-1", "ent-1", Instant.EPOCH,
                "inventory_turnover", 12.5, 8.5, -32.0, -2.4, "HIGH", "OPEN", "find-1");
    }

    private OperationalBaseline baseline() {
        return new OperationalBaseline("bl-1", "dom-1", "ag-1", "kpi-1", "inventory_turnover",
                "select 1", "conn-1", 8.5, 12.5, 1.6, "last 90 days", "[]",
                Instant.EPOCH, Instant.EPOCH, "ACTIVE", Instant.EPOCH);
    }

    private AlertRule rule() {
        return new AlertRule("r-1", "Turnover drop", "bl-1", "ag-1", "kpi-1", "inventory_turnover",
                "BELOW_WARNING", "HIGH", "ALL", null, null, 60, true, "system",
                Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void composesThroughSharedComposerWithAlertPolicy() {
        FakeAi ai = new FakeAi();
        String msg = service(ai).compose(rule(), anomaly(), baseline());

        assertEquals("Inventory turnover dropped 32% below its 90-day baseline; review procurement.", msg);
        assertTrue(ai.seenSystem.contains("concise, professional alert message"),
                "the alert policy is passed to the composer as the system prompt");
        assertTrue(ai.seenSystem.contains("Do not use markdown"),
                "the alert's plain-English formatting rule is policy, owned by this service");
        assertTrue(ai.seenUser.contains("inventory_turnover"),
                "the anomaly context reaches the model as the user prompt");
    }

    @Test
    void fallsBackToDeterministicTemplateWhenTheModelFails() {
        FakeAi ai = new FakeAi();
        ai.fail = true;
        String msg = service(ai).compose(rule(), anomaly(), baseline());

        assertTrue(msg.startsWith("HIGH alert:"), "on failure the deterministic template is returned");
        assertTrue(msg.contains("inventory_turnover"));
        assertTrue(msg.contains("baseline average"));
    }
}
