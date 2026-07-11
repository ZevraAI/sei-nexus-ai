package com.sei.nexus.enterprise;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-27 — deterministic content classification of sampled values (stage 2 of
 * the two-stage defense). Safe = short token-like business values; unsafe =
 * PII/identifier/free-text shapes. Fail-closed by design.
 */
class SampleContentClassifierTest {

    private static String classify(String... values) {
        return SampleContentClassifier.rejectReason(List.of(values));
    }

    // ── business values that MUST remain eligible ────────────────────────────

    @Test
    void businessStatusesAreSafe() {
        assertNull(classify("open", "temporarily_closed", "seasonal", "under_construction", "closed"));
        assertNull(classify("PENDING", "APPROVED", "REJECTED", "ON_HOLD"));
    }

    @Test
    void businessTypesAndCategoriesAreSafe() {
        assertNull(classify("flagship", "standard", "outlet", "kiosk", "seasonal"));
        assertNull(classify("Deluxe Room", "Standard Room", "Executive Suite"),
                "multi-word title-case categories are not person names — no dictionary hit");
    }

    @Test
    void geographicValuesAreSafe() {
        assertNull(classify("Texas", "California", "Georgia", "Virginia", "Florida"),
                "single-word state names never match the multi-word person shape — "
                        + "Georgia/Virginia are also deliberately absent from the name dictionary");
        assertNull(classify("New York", "New Hampshire", "North Carolina", "Rhode Island"),
                "two-word states: first tokens (New/North/Rhode) are not given names");
    }

    @Test
    void businessCodesAreSafe() {
        assertNull(classify("NET30", "NET60", "COD", "PREPAID"));
        assertNull(classify("A", "B", "C", "D"));
        assertNull(classify("1", "2", "3", "4", "5"), "short numeric grades are not digit-string PII");
    }

    // ── shapes that MUST be rejected ─────────────────────────────────────────

    @Test
    void personNamesAreRejected() {
        assertEquals("person-name-shaped values",
                classify("John Smith", "Mary Johnson", "Robert Lee", "Susan Brown", "David Wilson"));
    }

    @Test
    void patientStyleNameListsAreRejected() {
        assertEquals("person-name-shaped values",
                classify("Emily Carter", "Ahmed Hassan", "Maria Garcia", "Wei Zhang"));
    }

    @Test
    void emailsAreRejectedOnAnySingleMatch() {
        assertEquals("email-shaped values",
                classify("open", "closed", "jane.doe@example.com"));
    }

    @Test
    void phoneNumbersAreRejected() {
        assertEquals("phone/account-shaped digit strings",
                classify("(555) 123-4567", "555-987-6543", "555.222.1111"));
    }

    @Test
    void invoiceNumbersAreRejected() {
        assertEquals("identifier-shaped values (invoice/reference pattern)",
                classify("INV-2024-00187", "INV-2024-00188", "INV-2024-00189"));
    }

    @Test
    void freeTextIsRejected() {
        assertEquals("free-text values (word count)",
                classify("Customer complained about late delivery and requested a refund",
                        "open"));
        assertNotNull(classify(
                "x".repeat(90)), "over-length single value is free text");
    }

    @Test
    void ssnShapesAreRejected() {
        assertEquals("ssn-shaped values", classify("123-45-6789", "closed"));
    }

    // ── fail-closed edges ────────────────────────────────────────────────────

    @Test
    void emptySampleIsRejected() {
        assertNotNull(SampleContentClassifier.rejectReason(List.of()));
        assertNotNull(SampleContentClassifier.rejectReason(null));
    }

    @Test
    void isolatedNameShapeBelowThresholdStaysSafe() {
        // One name-shaped value among many business values must not nuke the
        // domain (personNames >= 2 AND fraction >= 0.4 required).
        assertNull(classify("Grand Opening", "Clearance", "Holiday Sale",
                "Back To School", "Maria Sale"));
    }
}
