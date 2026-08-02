package com.sei.nexus.enterprise;

import java.time.Instant;

/**
 * A canonical business concept (e.g. {@code Draft}, {@code Submitted}, {@code Approved}) — semantic
 * metadata only. It carries no physical values and never executes SQL. Scoped to a logical business
 * attribute ({@code businessAttributeKey}) so the same concept is shared across applications while
 * homonyms under different attributes stay distinct. Tenant-owned (persisted in the tenant schema).
 *
 * @see BusinessValueMapping the physical-value → concept link
 */
public record BusinessValue(
        String  businessValueKey,
        String  businessAttributeKey,   // logical attribute this concept belongs to (scoping)
        String  name,                   // canonical concept, e.g. "Draft"
        String  description,
        String  source,                 // AI | MANUAL
        Double  confidence,             // 0.0..1.0, nullable
        String  approvalStatus,         // PENDING | APPROVED | REJECTED
        String  createdBy,
        String  approvedBy,
        Instant approvedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String SOURCE_AI     = "AI";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String STATUS_PENDING  = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    public boolean isApproved() { return STATUS_APPROVED.equals(approvalStatus); }
}
