package com.sei.nexus.automation;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

public record AutomationExecution(
        String id,
        String workflowId,
        String tenantSchema,
        @JsonRawValue String triggerPayloadJson,  // raw JSONB — serialised as embedded JSON object
        String status,                            // RUNNING | SUCCESS | FAILED | TIMEOUT
        @JsonRawValue String stepTracesJson,      // raw JSONB array — serialised as embedded JSON array
        @JsonRawValue String finalOutputJson,     // raw JSONB — nullable, serialised as embedded JSON
        String errorMessage,                      // nullable
        Instant startedAt,
        Instant completedAt                       // nullable
) {}
