package com.sei.nexus.chat;

public record ChatRequest(
        String agentKey,
        String conversationId,
        String question
) {}
