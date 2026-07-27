package com.sei.nexus.enterprise;

import java.time.Instant;

/**
 * The semantic interpretation of one observed physical value: it maps a physical value — referenced
 * by its natural key {@code (valueDomainKey, physicalValue)} within the existing
 * {@link ValueDomain} — to a {@link BusinessValue}. It stores <b>no</b> new physical value; the
 * value it names is already an element of the domain's {@code domain_values} set.
 *
 * <p>Invariants (enforced by the schema): exactly one Business Value per
 * {@code (valueDomainKey, physicalValue)}; a Business Value may map to many physical values.
 * {@code crossApplication} mappings require customer approval before they are used.
 */
public record BusinessValueMapping(
        String  mappingKey,
        String  valueDomainKey,     // existing nexus_value_domain
        String  physicalValue,      // natural key within the domain (an element of domain_values)
        String  businessValueKey,
        String  source,             // AI | MANUAL
        Double  confidence,
        String  approvalStatus,     // PENDING | APPROVED | REJECTED
        boolean crossApplication,
        String  createdBy,
        String  approvedBy,
        Instant approvedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean isApproved() { return BusinessValue.STATUS_APPROVED.equals(approvalStatus); }
}
