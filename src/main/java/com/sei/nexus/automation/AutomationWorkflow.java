package com.sei.nexus.automation;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

public record AutomationWorkflow(
        String id,
        String tenantSchema,
        String name,
        String description,
        String slug,
        String status,          // DRAFT | ACTIVE | ARCHIVED
        String triggerType,     // WEBHOOK | MANUAL
        @JsonRawValue String graphJson,   // raw JSONB — serialised as embedded JSON object
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {}
