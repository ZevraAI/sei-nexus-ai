package com.sei.nexus.chat;

public record ChatRequest(
        String agentKey,
        String conversationId,
        String question,
        String attachmentKey,   // optional — key of a pre-uploaded ChatAttachment
        String clientRunKey     // optional — frontend-provided run key for SSE pre-subscription
) {}
