package com.sei.nexus.chat;

import com.sei.nexus.strategy.ExecutionStrategy;
import com.sei.nexus.strategy.IntentType;
import com.sei.nexus.strategy.RequestAnalysis;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Architectural conformance (Unified Answer Engine): the Agent Router is never the front door
 * (Invariant 2). This pins the single dispatch gate {@link ChatService#shouldInvokeAgentRouter}:
 *
 * <pre>
 *   ExecutionStrategySelector
 *        ├── CHAT   → AgentRouter NOT INVOKED
 *        └── AGENT  → AgentRouter INVOKED
 * </pre>
 *
 * A regression guard: if anyone reintroduces unconditional (data-ownership-driven) routing, this
 * static seam fails. Pure — no Spring context, no ChatService construction.
 */
class ExecutionStrategyDispatchTest {

    private static RequestAnalysis strategy(ExecutionStrategy s) {
        return new RequestAnalysis(s, IntentType.UNKNOWN, 0.9, "test");
    }

    @Test
    void chatStrategyDoesNotInvokeTheAgentRouter() {
        assertFalse(ChatService.shouldInvokeAgentRouter(strategy(ExecutionStrategy.CHAT)),
                "CHAT must never reach the Agent Router — the router is not the front door");
    }

    @Test
    void agentStrategyInvokesTheAgentRouter() {
        assertTrue(ChatService.shouldInvokeAgentRouter(strategy(ExecutionStrategy.AGENT)),
                "the Agent Router (which agent) runs only after AGENT (how) is chosen");
    }

    @Test
    void unwiredFutureStrategiesDoNotInvokeTheAgentRouter() {
        // REPORT / EXECUTIVE_BRIEF / WORKFLOW use the CHAT fallback seam today; none is an agent.
        for (ExecutionStrategy s : new ExecutionStrategy[]{
                ExecutionStrategy.REPORT, ExecutionStrategy.EXECUTIVE_BRIEF, ExecutionStrategy.WORKFLOW}) {
            assertFalse(ChatService.shouldInvokeAgentRouter(strategy(s)),
                    s + " must not invoke the Agent Router");
        }
    }

    @Test
    void nullAnalysisIsTreatedAsNonAgent() {
        assertFalse(ChatService.shouldInvokeAgentRouter(null),
                "a missing analysis must never open the agent path");
    }
}
