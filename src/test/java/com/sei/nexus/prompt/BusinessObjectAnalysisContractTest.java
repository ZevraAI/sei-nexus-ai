package com.sei.nexus.prompt;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The canonical Business Object Analysis contract, tested in isolation — proves the shared
 * pieces {@code OnboardingService.analyzeTableBatch()} and
 * {@code EnterpriseMapService.analyzeForOnboarding()} both embed actually enforce the rules
 * this whole convergence exists for, independent of either caller's own envelope/orchestration.
 */
class BusinessObjectAnalysisContractTest {

    @Test
    void categoryValuesMatchTheOriginalOnboardingTaxonomy() {
        assertEquals(
                List.of("Customers", "Transactions", "Finance", "Operations", "Products", "HR", "Other"),
                BusinessObjectAnalysisContract.CATEGORY_VALUES,
                "no new taxonomy — must remain exactly the enum onboarding already used");
    }

    @Test
    void fieldSchemaRequiresCategoryAndDoesNotContainTableEnvelopeStructure() {
        String schema = BusinessObjectAnalysisContract.FIELD_SCHEMA;
        assertTrue(schema.contains("\"category\""));
        assertTrue(schema.contains("\"businessName\""), "Discover's field, now canonical");
        assertTrue(schema.contains("\"operationalMeaning\""), "Onboarding's field, now canonical");
        assertTrue(schema.contains("\"identifierColumns\""), "Discover's field, now canonical");
        assertTrue(schema.contains("\"vocabularySuggestions\""));
        assertTrue(schema.contains("\"sqlEquivalent\""), "vocabulary shape includes sqlEquivalent, optional-blank");
        // Envelope structure (table_name, tables array, suggestedQuestions) is caller-specific —
        // must never leak into the shared canonical field set.
        assertFalse(schema.contains("table_name"));
        assertFalse(schema.contains("\"tables\""));
        assertFalse(schema.contains("suggestedQuestions"), "wizard-bootstrap-only, not canonical");
        assertFalse(schema.contains("lifecycleStates"), "confirmed zero consumers — deliberately dropped");
    }

    @Test
    void rulesRequireCategoryToBeNonBlankAndConcise() {
        String rules = BusinessObjectAnalysisContract.RULES;
        assertTrue(rules.contains("category is required for every table"));
        assertTrue(rules.contains("never blank"));
        assertTrue(rules.contains("never the physical table name, a column name, or SQL"));
    }

    @Test
    void applyCanonicalDefaultsFillsGapsWithoutOverwritingRealValues() {
        Map<String, Object> withRealCategory = new LinkedHashMap<>(Map.of(
                "category", "Procurement", "entityName", "Supplier"));
        BusinessObjectAnalysisContract.applyCanonicalDefaults(withRealCategory, "suppliers");
        assertEquals("Procurement", withRealCategory.get("category"), "a real value must survive untouched");
        assertEquals("Supplier", withRealCategory.get("entityName"));

        Map<String, Object> empty = new LinkedHashMap<>();
        BusinessObjectAnalysisContract.applyCanonicalDefaults(empty, "purchase_order_lines");
        assertEquals("Other", empty.get("category"));
        assertEquals("Purchase Order Lines", empty.get("entityName"));
        assertEquals("", empty.get("purpose"));
        assertEquals(List.of(), empty.get("vocabularySuggestions"));

        Map<String, Object> blank = new LinkedHashMap<>(Map.of("category", "   "));
        BusinessObjectAnalysisContract.applyCanonicalDefaults(blank, "x");
        assertEquals("Other", blank.get("category"), "whitespace-only category counts as missing");
    }

    @Test
    void canonicalStubHasEveryRequiredFieldNeverJustTableNameAndError() {
        Map<String, Object> stub = BusinessObjectAnalysisContract.canonicalStub("fiscal_periods");
        assertEquals("Fiscal Periods", stub.get("entityName"));
        assertEquals("Other", stub.get("category"));
        assertEquals("", stub.get("purpose"));
        assertEquals(List.of(), stub.get("vocabularySuggestions"));
    }
}
