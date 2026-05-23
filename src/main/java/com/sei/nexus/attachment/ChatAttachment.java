package com.sei.nexus.attachment;

import java.time.Instant;

public record ChatAttachment(
        String  attachmentKey,
        String  conversationId,
        String  fileName,
        String  attachmentType,   // IMAGE | TABULAR | DOCUMENT | TEXT
        String  mimeType,
        Long    fileSizeBytes,
        String  summary,          // AI-generated one-liner
        String  extractedText,    // full content injected into LLM context
        String  thumbnailBase64,  // base64 image thumbnail (images only)
        String  createdBy,
        Instant createdAt,
        Instant expiresAt
) {}
