package com.sei.nexus.instruction;

import java.time.OffsetDateTime;

/**
 * A standing instruction proposed by a user and reviewed by an admin or domain owner, e.g. "always
 * show the item description on order line items". Only APPROVED instructions reach a prompt.
 */
public record Instruction(
        String instructionId,
        String instructionText,
        String status,
        boolean alwaysApply,
        String submittedBy,
        String reviewedBy,
        OffsetDateTime createdAt,
        OffsetDateTime reviewedAt
) {
    public static final String PENDING  = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String ARCHIVED = "ARCHIVED";
}
