package com.sei.nexus.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-34 — pins the decision-router prompt contract.
 *
 * The live defect: the LITERAL CANDIDATES section (PRO-33) renders into the
 * decision context, and without a routing rule about it the router read
 * "matched no known term … ask for clarification" as grounds for
 * ASK_CLARIFICATION — returning before the QUERY_LIVE_DATA path, so the SQL
 * planner (the owner of the constrained literal choice) and the literal
 * validator never executed. Rule 6 closes that gap; this test keeps it,
 * and the surrounding contract, from silently regressing.
 *
 * <p>ANSWER_FROM_PRIOR_RESULTS removal — the router previously judged whether
 * previously-gathered evidence was sufficient to answer a follow-up, without
 * ever seeing that evidence (e.g. "i need order details" against a cached
 * supplier-aggregate produced a schema-description non-answer instead of a
 * real query). That judgment now belongs entirely to ReasoningEvaluator,
 * seeded from the conversation's ExecutionReference — see
 * ReasoningEngine.reason(). This test keeps the decision type from
 * silently reappearing in the router's vocabulary.
 */
class DecisionRoutingPromptTest {

    @Test
    void literalCandidatesRoutingRuleIsPresent() {
        String p = ChatService.DECISION_SYSTEM_PROMPT;
        assertTrue(p.contains("A LITERAL CANDIDATES section means an unfamiliar term in the question already has"));
        assertTrue(p.contains("Such a question is NOT ambiguous: use QUERY_LIVE_DATA"));
        assertTrue(p.contains("Do NOT use\n   ASK_CLARIFICATION for a term that has literal candidates")
                || p.contains("Do NOT use"));
        assertTrue(p.contains("clarification for those\n   terms belongs to the SQL planner")
                || p.contains("belongs to the SQL planner"));
    }

    @Test
    void answerFromPriorResultsIsNotARouterDecisionType() {
        String p = ChatService.DECISION_SYSTEM_PROMPT;
        assertFalse(p.contains("ANSWER_FROM_PRIOR_RESULTS"),
                "evidence sufficiency belongs to ReasoningEvaluator, not the router");
        assertTrue(p.contains("EVERY follow-up question in an existing conversation"));
    }

    @Test
    void preExistingRoutingContractIsUnchanged() {
        String p = ChatService.DECISION_SYSTEM_PROMPT;
        // The clarification guard itself stays (PRO-32 §3.4 escalation is preserved)
        assertTrue(p.contains("Use ASK_CLARIFICATION ONLY if the question is completely ambiguous"));
        // PRO-31 static sentence
        assertTrue(p.contains("RESOLUTIONS map the user's terms to this tenant's canonical names and values."));
        // PRO-33 static sentence
        assertTrue(p.contains("Literals filtered on columns with listed legal values MUST be copied exactly"));
        // Decision types: ANSWER_FROM_PRIOR_RESULTS removed (see class Javadoc)
        assertTrue(p.contains("ANSWER_FROM_MEMORY|QUERY_LIVE_DATA|HYBRID_DOC_AND_DATA|ASK_CLARIFICATION|KNOWLEDGE_GAP\""));
    }
}
