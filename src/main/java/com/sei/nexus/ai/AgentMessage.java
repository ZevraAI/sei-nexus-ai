package com.sei.nexus.ai;

import java.util.List;
import java.util.Map;

/**
 * A message in an agent tool-calling conversation.
 * Supports user, assistant (text or tool_call), and tool result roles.
 */
public record AgentMessage(
        String              role,
        String              content,
        String              toolCallId,
        List<Map<String,Object>> toolCalls   // present when role=assistant and LLM called a tool
) {
    public static AgentMessage user(String content) {
        return new AgentMessage("user", content, null, null);
    }

    public static AgentMessage assistantText(String content) {
        return new AgentMessage("assistant", content, null, null);
    }

    public static AgentMessage assistantToolCall(String toolCallId, String toolName,
                                                  String argsJson) {
        Map<String, Object> call = Map.of(
                "id",   toolCallId,
                "type", "function",
                "function", Map.of("name", toolName, "arguments", argsJson)
        );
        return new AgentMessage("assistant", null, null, List.of(call));
    }

    public static AgentMessage toolResult(String toolCallId, String resultJson) {
        return new AgentMessage("tool", resultJson, toolCallId, null);
    }
}
