package com.sei.nexus.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified Answer Engine conformance — the Execution Strategy Selector classifies HOW a request
 * executes from its execution characteristics, and its parsing applies the safe-default rules:
 * unknown/malformed input, and an under-confident AGENT, all resolve to CHAT (the selector's single
 * decision never silently escalates to AGENT). Pure: pins {@link LlmExecutionStrategySelector#parseAnalysis}
 * with a real ObjectMapper — no LLM client, no repository, no Spring.
 */
class ExecutionStrategySelectorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private RequestAnalysis parse(String json) {
        return LlmExecutionStrategySelector.parseAnalysis(json, mapper);
    }

    // ── simple retrieval is CHAT even though it names agent-owned data (Invariant 3) ──────────

    @Test
    void simpleRetrievalClassifiesAsChat() {
        RequestAnalysis a = parse("""
                {"strategy":"CHAT","intentType":"INFORMATIONAL","confidence":0.95,"reasoning":"single query"}""");
        assertEquals(ExecutionStrategy.CHAT, a.strategy());
        assertEquals(IntentType.INFORMATIONAL, a.intentType());
    }

    // ── autonomous investigation is AGENT (Invariant 4) ───────────────────────────────────────

    @Test
    void autonomousInvestigationClassifiesAsAgent() {
        RequestAnalysis a = parse("""
                {"strategy":"AGENT","intentType":"OPERATIONAL_INVESTIGATION","confidence":0.9,"reasoning":"multi-step"}""");
        assertEquals(ExecutionStrategy.AGENT, a.strategy());
        assertEquals(IntentType.OPERATIONAL_INVESTIGATION, a.intentType());
    }

    // ── safe defaults ─────────────────────────────────────────────────────────────────────────

    @Test
    void underConfidentAgentSafelyDefaultsToChat() {
        RequestAnalysis a = parse("""
                {"strategy":"AGENT","intentType":"OPERATIONAL_INVESTIGATION","confidence":0.3,"reasoning":"unsure"}""");
        assertEquals(ExecutionStrategy.CHAT, a.strategy(),
                "an uncertain AGENT classification must resolve to CHAT, never escalate");
        // intent is still carried through for reuse downstream
        assertEquals(IntentType.OPERATIONAL_INVESTIGATION, a.intentType());
    }

    @Test
    void unknownStrategyResolvesToChat() {
        RequestAnalysis a = parse("""
                {"strategy":"REPORT_XYZ","intentType":"ANALYTICAL","confidence":0.99}""");
        assertEquals(ExecutionStrategy.CHAT, a.strategy(),
                "an unrecognised strategy label must never escalate to AGENT");
    }

    @Test
    void malformedJsonResolvesToChat() {
        assertEquals(ExecutionStrategy.CHAT, parse("not json at all").strategy());
        assertEquals(ExecutionStrategy.CHAT, parse("").strategy());
    }

    @Test
    void futureStrategiesParseIntoTheTypeSystem() {
        // REPORT/EXECUTIVE_BRIEF/WORKFLOW exist in the type system for extensibility; a high-confidence
        // (non-AGENT) label parses to itself and is handled by the documented CHAT fallback seam.
        assertEquals(ExecutionStrategy.REPORT,
                parse("{\"strategy\":\"REPORT\",\"confidence\":0.9}").strategy());
        assertEquals(ExecutionStrategy.WORKFLOW,
                parse("{\"strategy\":\"WORKFLOW\",\"confidence\":0.9}").strategy());
    }

    @Test
    void analysisIsNeverNullValued() {
        RequestAnalysis a = parse("{}");
        assertNotNull(a.strategy());
        assertNotNull(a.intentType());
        assertNotNull(a.reasoning());
    }
}
