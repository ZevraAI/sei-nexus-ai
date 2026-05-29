package com.sei.nexus.ai;

import java.util.Map;

/**
 * Result from {@link AzureOpenAiClient#chatWithTools}.
 * Either the LLM wants to call a tool, or it has a final answer.
 */
public record AgentToolResponse(
        boolean            finalAnswer,
        String             answer,        // set when finalAnswer=true
        String             toolName,      // set when finalAnswer=false
        String             toolCallId,    // OpenAI call ID for linking tool result
        Map<String,Object> args           // parsed tool arguments
) {
    public static AgentToolResponse ofFinal(String answer) {
        return new AgentToolResponse(true, answer, null, null, null);
    }

    public static AgentToolResponse ofToolCall(String toolName, String toolCallId,
                                                Map<String,Object> args) {
        return new AgentToolResponse(false, null, toolName, toolCallId, args);
    }
}
