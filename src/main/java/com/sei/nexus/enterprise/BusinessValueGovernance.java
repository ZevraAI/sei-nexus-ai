package com.sei.nexus.enterprise;

import java.util.List;
import java.util.Locale;

/**
 * Pure, deterministic governance rules for Business Values — duplicate detection, conflicting-mapping
 * detection, and the customer-approval requirement. No I/O; the database enforces the hard
 * uniqueness constraints (one Business Value per {@code (value_domain_key, physical_value)}), while
 * these rules let the onboarding pipeline decide what to auto-accept versus route to review.
 */
public final class BusinessValueGovernance {

    /** Below this AI confidence a mapping/concept must be customer-reviewed (same gate family as
     *  the learning-promotion threshold used elsewhere, 0.55). */
    public static final double AUTO_APPROVE_CONFIDENCE = 0.55;

    private BusinessValueGovernance() {}

    /**
     * A concept is a duplicate when another Business Value under the <b>same</b> business attribute
     * already carries the same name (case-insensitive). Duplicates must be merged, not created.
     */
    public static boolean isDuplicate(String businessAttributeKey, String name,
                                      List<BusinessValue> existing) {
        if (name == null) return false;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return existing.stream()
                // scope to the same business attribute — homonyms under a different attribute are distinct
                .filter(v -> java.util.Objects.equals(businessAttributeKey, v.businessAttributeKey()))
                .anyMatch(v -> n.equals(v.name() == null ? null : v.name().trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * A proposed mapping conflicts when a mapping already exists for the same
     * {@code (value_domain_key, physical_value)} but points at a <b>different</b> Business Value.
     * (The schema also rejects this; this lets the pipeline surface it before the write.)
     */
    public static boolean conflictsWith(BusinessValueMapping proposed, BusinessValueMapping existingForPhysical) {
        if (existingForPhysical == null) return false;
        return !existingForPhysical.businessValueKey().equals(proposed.businessValueKey());
    }

    /**
     * Customer approval is required when the mapping is cross-application, or when an AI-proposed
     * mapping's confidence is below the auto-approve threshold. Manual (customer-authored) mappings
     * and high-confidence single-application AI mappings may be auto-approved.
     */
    public static boolean requiresCustomerApproval(BusinessValueMapping m) {
        if (m.crossApplication()) return true;
        if (BusinessValue.SOURCE_MANUAL.equals(m.source())) return false;
        double conf = m.confidence() == null ? 0.0 : m.confidence();
        return conf < AUTO_APPROVE_CONFIDENCE;
    }
}
