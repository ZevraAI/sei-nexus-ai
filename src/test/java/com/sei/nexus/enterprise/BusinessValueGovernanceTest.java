package com.sei.nexus.enterprise;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Commit 1 (Metadata Foundation) — unit tests for the deterministic Business Value governance rules.
 * Pure logic, no database. The DB constraints (uniqueness) are validated separately against Postgres.
 */
class BusinessValueGovernanceTest {

    private static BusinessValue bv(String attr, String name) {
        return new BusinessValue("bv-" + name, attr, name, null, BusinessValue.SOURCE_AI, 0.9,
                BusinessValue.STATUS_APPROVED, "u", null, null, Instant.EPOCH, Instant.EPOCH);
    }

    private static BusinessValueMapping map(String bvKey, boolean crossApp, String source, Double conf) {
        return new BusinessValueMapping("m-1", "vd-1", "10", bvKey, source, conf,
                BusinessValue.STATUS_PENDING, crossApp, "u", null, null, Instant.EPOCH, Instant.EPOCH);
    }

    // ── duplicate concept detection ─────────────────────────────────────────────

    @Test
    void duplicateDetectedForSameNameUnderSameAttribute() {
        List<BusinessValue> existing = List.of(bv("po_status", "Draft"), bv("po_status", "Submitted"));
        assertTrue(BusinessValueGovernance.isDuplicate("po_status", "draft", existing),
                "same concept name under the same attribute is a duplicate (case-insensitive)");
        assertFalse(BusinessValueGovernance.isDuplicate("po_status", "Approved", existing),
                "a new concept name is not a duplicate");
    }

    @Test
    void sameNameUnderDifferentAttributeIsNotADuplicate() {
        // "Draft" for PO status vs "Draft" for document type are distinct concepts.
        List<BusinessValue> poStatus = List.of(bv("po_status", "Draft"));
        assertFalse(BusinessValueGovernance.isDuplicate("doc_type", "Draft", poStatus),
                "homonyms under different attributes are distinct, not duplicates");
    }

    // ── conflicting mapping detection ───────────────────────────────────────────

    @Test
    void conflictWhenPhysicalValueAlreadyMapsToADifferentConcept() {
        BusinessValueMapping existing = map("bv-Draft", false, BusinessValue.SOURCE_AI, 0.9);
        BusinessValueMapping proposed = map("bv-Submitted", false, BusinessValue.SOURCE_AI, 0.9);
        assertTrue(BusinessValueGovernance.conflictsWith(proposed, existing),
                "the same physical value cannot map to two different Business Values");
    }

    @Test
    void noConflictWhenSameConceptOrNoExisting() {
        BusinessValueMapping existing = map("bv-Draft", false, BusinessValue.SOURCE_AI, 0.9);
        assertFalse(BusinessValueGovernance.conflictsWith(map("bv-Draft", false, "AI", 0.9), existing),
                "re-affirming the same concept is not a conflict");
        assertFalse(BusinessValueGovernance.conflictsWith(map("bv-Draft", false, "AI", 0.9), null),
                "no existing mapping ⇒ no conflict");
    }

    // ── customer-approval requirement ───────────────────────────────────────────

    @Test
    void crossApplicationMappingsAlwaysRequireApproval() {
        assertTrue(BusinessValueGovernance.requiresCustomerApproval(
                        map("bv-Draft", /*crossApp*/ true, BusinessValue.SOURCE_AI, 0.99)),
                "cross-application mappings must be customer-approved regardless of confidence");
    }

    @Test
    void lowConfidenceAiMappingsRequireApproval_highConfidenceDoNot() {
        assertTrue(BusinessValueGovernance.requiresCustomerApproval(
                map("bv-x", false, BusinessValue.SOURCE_AI, 0.30)), "low-confidence AI ⇒ review");
        assertFalse(BusinessValueGovernance.requiresCustomerApproval(
                map("bv-x", false, BusinessValue.SOURCE_AI, 0.90)), "high-confidence single-app AI ⇒ auto");
    }

    @Test
    void manualMappingsAreAutoApproved() {
        assertFalse(BusinessValueGovernance.requiresCustomerApproval(
                        map("bv-x", false, BusinessValue.SOURCE_MANUAL, null)),
                "customer-authored mappings are self-approved");
    }
}
