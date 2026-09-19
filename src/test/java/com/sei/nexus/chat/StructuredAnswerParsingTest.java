package com.sei.nexus.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sei.nexus.response.StructuredAnswer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for {@link ChatService#parseStructuredAnswer} — the seam that turns the
 * answer-composition LLM call's JSON-mode response into {@link StructuredAnswer}. Pure static
 * method, no Spring context, no Mockito, no network (this repo's convention — see
 * ChatServiceHardeningTest). Uses a real {@link ObjectMapper} configured with the same
 * SNAKE_CASE naming strategy as the app's actual {@code @Primary} bean (WebConfig) — this is
 * exactly what makes an LLM response using {@code key_findings} deserialize into the record's
 * {@code keyFindings} field automatically, with no per-call configuration.
 */
class StructuredAnswerParsingTest {

    private static ObjectMapper snakeCaseMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return mapper;
    }

    @Test
    void parsesFullStructuredResponseWithSnakeCaseKeys() {
        String json = """
                {
                  "answer": "Three of five open purchase orders are partially received.",
                  "understanding": "Three of five orders are partially received, representing $135,300 in value.",
                  "key_findings": ["The affected orders represent the majority of open order value."],
                  "related_facts": ["Expected delivery dates extend into the future."],
                  "recommendation": "Follow up with suppliers on the partially received orders.",
                  "follow_up_questions": ["Show only partially received orders", "Review orders by supplier"]
                }
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals("Three of five open purchase orders are partially received.", result.answer());
        assertEquals("Three of five orders are partially received, representing $135,300 in value.",
                result.understanding());
        assertEquals(1, result.keyFindings().size());
        assertEquals(1, result.relatedFacts().size());
        assertEquals("Follow up with suppliers on the partially received orders.", result.recommendation());
        assertEquals(2, result.followUpQuestions().size());
    }

    @Test
    void parsesResponseWithLegitimatelyEmptySections() {
        String json = """
                {"answer": "17 stores are currently open.", "understanding": "17 stores are currently open.",
                 "key_findings": [], "related_facts": [], "recommendation": null, "follow_up_questions": []}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals("17 stores are currently open.", result.answer());
        assertTrue(result.keyFindings().isEmpty());
        assertTrue(result.relatedFacts().isEmpty());
        assertNull(result.recommendation());
        assertTrue(result.followUpQuestions().isEmpty());
    }

    @Test
    void toleratesStrayProseOrFencingAroundTheJsonObject() {
        String json = "Here is the JSON:\n```json\n{\"answer\": \"The system is operating normally.\"}\n```";

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals("The system is operating normally.", result.answer());
    }

    @Test
    void malformedJsonDegradesToPlainTextFallbackNeverThrows() {
        StructuredAnswer result = ChatService.parseStructuredAnswer(
                "not json at all", snakeCaseMapper(), "The system is operating normally.");

        assertEquals("The system is operating normally.", result.answer());
        assertNull(result.understanding());
        assertNull(result.keyFindings());
        assertNull(result.relatedFacts());
        assertNull(result.recommendation());
        assertNull(result.followUpQuestions());
        assertNotNull(result.metrics(), "metrics defaults to an empty list, never null, even on fallback");
        assertTrue(result.metrics().isEmpty());
    }

    @Test
    void nullJsonDegradesToPlainTextFallbackNeverThrows() {
        StructuredAnswer result = ChatService.parseStructuredAnswer(null, snakeCaseMapper(), "fallback answer");
        assertEquals("fallback answer", result.answer());
    }

    @Test
    void blankAnswerFieldFallsBackToThePassedFallbackAnswer() {
        String json = "{\"answer\": \"\", \"understanding\": \"Something was still understood.\"}";

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback answer");

        assertEquals("fallback answer", result.answer());
        assertEquals("Something was still understood.", result.understanding());
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // "metrics" — the model's optional, whole-answer headline figures (a sibling of "sections",
    // never derived from it). Verbatim relay only; Java's only judgment is a mechanical non-blank
    // existence check (see ResponseArtifactsBuilder#metrics for the precedence/fallback rule).
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void parsesMetricsAlongsideTheLegacyFlatShape() {
        String json = """
                {"answer": "Marcus Webb is the buyer for 3 of 5 open purchase orders.",
                 "metrics": [
                   {"label": "Top Buyer", "value": "Marcus Webb"},
                   {"label": "Total Value", "value": "$373,750"}
                 ]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals(2, result.metrics().size());
        assertEquals("Top Buyer", result.metrics().get(0).label());
        assertEquals("Marcus Webb", result.metrics().get(0).value());
        assertEquals("Total Value", result.metrics().get(1).label());
        assertEquals("$373,750", result.metrics().get(1).value());
    }

    @Test
    void parsesMetricsAlongsideTheSectionsBasedContract() {
        String json = """
                {"answer": "...",
                 "sections": [{"type": "DATASET", "dataset_refs": ["step-1"], "display": true}],
                 "metrics": [{"label": "Earliest Delivery", "value": "Oct 3, 2025"}]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals(1, result.sections().size());
        assertEquals(1, result.metrics().size());
        assertEquals("Earliest Delivery", result.metrics().get(0).label());
        assertEquals("Oct 3, 2025", result.metrics().get(0).value());
    }

    @Test
    void absentMetricsFieldParsesAsAnEmptyListNeverNull() {
        String json = "{\"answer\": \"17 stores are currently open.\"}";

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertNotNull(result.metrics());
        assertTrue(result.metrics().isEmpty());
    }

    @Test
    void aMetricEntryMissingLabelOrValueIsDroppedNeverPartiallyRelayed() {
        String json = """
                {"answer": "...",
                 "metrics": [
                   {"label": "Top Buyer", "value": "Marcus Webb"},
                   {"label": "", "value": "should be dropped: blank label"},
                   {"label": "Should be dropped: blank value", "value": ""},
                   {"label": "Should be dropped: null value", "value": null}
                 ]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals(1, result.metrics().size());
        assertEquals("Top Buyer", result.metrics().get(0).label());
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // sections-based contract — the model's UI-content plan
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void parsesSectionsAndDerivesLegacyFlatFieldsByTypeTagMechanically() {
        String json = """
                {
                  "answer": "You have 5 open purchase orders. The most ordered item is Widget ABC (SKU-123), with 1,500 units ordered.",
                  "sections": [
                    {"type": "DATASET", "title": "Open Orders", "purpose": "Shows all open orders",
                     "dataset_refs": ["step-1"], "display": true},
                    {"type": "HIGHLIGHT", "title": "Most Ordered Item", "purpose": "Shows the identified item",
                     "dataset_refs": ["step-3", "step-5"], "display": true,
                     "content": "Widget ABC (SKU-123) is the most ordered item with 1,500 units ordered."},
                    {"type": "FINDINGS", "title": "Key Findings",
                     "items": ["Widget ABC (SKU-123) has the highest ordered quantity at 1,500 units."]},
                    {"type": "RECOMMENDATION", "content": "Review inventory for Widget ABC."}
                  ]
                }
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals(4, result.sections().size());
        assertEquals("DATASET", result.sections().get(0).type());
        assertEquals(List.of("step-1"), result.sections().get(0).datasetRefs());
        assertEquals(List.of("step-3", "step-5"), result.sections().get(1).datasetRefs(),
                "a section may declare more than one grounding dataset");

        // Mechanical, type-tag-driven projection — never independently populated by the model.
        // Both the FINDINGS section's item AND the HIGHLIGHT's content contribute to keyFindings.
        assertEquals(2, result.keyFindings().size());
        assertTrue(result.keyFindings().contains(
                "Widget ABC (SKU-123) has the highest ordered quantity at 1,500 units."));
        assertTrue(result.keyFindings().contains(
                "Widget ABC (SKU-123) is the most ordered item with 1,500 units ordered."));
        assertEquals("Review inventory for Widget ABC.", result.recommendation());
        assertNull(result.understanding(), "there is no UNDERSTANDING section type in this contract");
    }

    @Test
    void multipleDatasetSectionsBothSurviveParsingNeitherIsDropped() {
        String json = """
                {"answer": "...",
                 "sections": [
                   {"type": "DATASET", "dataset_refs": ["step-1"], "display": true},
                   {"type": "DATASET", "dataset_refs": ["step-5"], "display": true}
                 ]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        long datasetSections = result.sections().stream().filter(s -> "DATASET".equals(s.type())).count();
        assertEquals(2, datasetSections, "the model choosing two datasets for one answer must survive parsing intact");
    }

    @Test
    void aSingleSectionMayDeclareMultipleDatasetRefsAllSurviveParsing() {
        String json = """
                {"answer": "...",
                 "sections": [
                   {"type": "HIGHLIGHT", "dataset_refs": ["step-1", "step-3", "step-5"],
                    "content": "Combined narrative spanning three datasets."}
                 ]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals(List.of("step-1", "step-3", "step-5"), result.sections().get(0).datasetRefs());
    }

    @Test
    void responseWithNoSectionsFallsBackToTheLegacyFlatFieldShape() {
        // An older/degraded model turn that still returns the pre-sections shape — lenient
        // JSON-shape handling, not a semantic fallback.
        String json = """
                {"answer": "17 stores are currently open.", "key_findings": ["a finding"]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertTrue(result.sections().isEmpty());
        assertEquals(1, result.keyFindings().size());
        assertEquals("a finding", result.keyFindings().get(0));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // FOLLOW_UP_QUESTIONS "reasoning" — a property of the FOLLOW_UP_QUESTIONS section itself (see
    // StructuredAnswer.Section), NOT an independent top-level field. This is the structural fix:
    // the former top-level "follow_up_questions_reasoning" has been removed completely, not kept
    // as a compatibility field — reasoning and items now live on the same section object.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void parsesFollowUpQuestionsReasoningStructurallyAttachedToItsOwnSection() {
        String json = """
                {"answer": "You have 5 open purchase orders.",
                 "sections": [
                   {"type": "FOLLOW_UP_QUESTIONS", "items": ["Show orders by supplier", "Show overdue orders"],
                    "reasoning": "Proposed drilling into supplier and overdue-status breakdowns."}
                 ]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals(2, result.followUpQuestions().size());
        StructuredAnswer.Section followUps = result.sections().stream()
                .filter(s -> "FOLLOW_UP_QUESTIONS".equals(s.type())).findFirst().orElseThrow();
        assertEquals("Proposed drilling into supplier and overdue-status breakdowns.", followUps.reasoning());
        assertEquals(2, followUps.items().size(), "reasoning and items are properties of the same section object");
    }

    @Test
    void noFollowUpQuestionsSection_meansNoFollowUpsAndNoReasoningAnywhere() {
        String json = """
                {"answer": "PO-2025-00234 is currently in Partially Received status.",
                 "sections": [
                   {"type": "TEXT", "content": "no-op filler section to keep sections non-empty for this test"}
                 ]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertTrue(result.followUpQuestions().isEmpty(), "no FOLLOW_UP_QUESTIONS section means no fabricated follow-ups");
        assertTrue(result.sections().stream().noneMatch(s -> "FOLLOW_UP_QUESTIONS".equals(s.type())),
                "the section is omitted entirely — there is no longer a top-level reasoning field to carry a justification");
    }

    @Test
    void followUpQuestionsSectionWithNullReasoningParsesFine_legacyOrDegradedResponse() {
        String json = """
                {"answer": "17 stores are currently open.",
                 "sections": [
                   {"type": "FOLLOW_UP_QUESTIONS", "items": ["How many closed this quarter?"]}
                 ]}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        StructuredAnswer.Section followUps = result.sections().stream()
                .filter(s -> "FOLLOW_UP_QUESTIONS".equals(s.type())).findFirst().orElseThrow();
        assertNull(followUps.reasoning(), "a response missing this optional field must not fail to parse");
        assertEquals(1, result.followUpQuestions().size());
    }

    @Test
    void malformedJsonFallbackHasNoSectionsAndNoFollowUpQuestionsNeverThrows() {
        StructuredAnswer result = ChatService.parseStructuredAnswer(
                "not json at all", snakeCaseMapper(), "The system is operating normally.");

        assertTrue(result.sections().isEmpty());
        assertTrue(result.followUpQuestions() == null || result.followUpQuestions().isEmpty());
    }

    @Test
    void noTopLevelFollowUpQuestionsReasoningField_andNoTopLevelFollowUpQuestionsFieldEither() throws Exception {
        // Structural assertion for the RCA fix: the canonical representation is exclusively
        // sections[] -> type=FOLLOW_UP_QUESTIONS -> items/reasoning. Confirmed via reflection —
        // StructuredAnswer carries no field/accessor by either legacy name.
        for (var m : StructuredAnswer.class.getMethods()) {
            assertNotEquals("followUpQuestionsReasoning", m.getName(),
                    "the former top-level follow_up_questions_reasoning field must be removed completely, not kept as compatibility");
        }
        // "followUpQuestions" (the mechanically-derived list of the section's own items) is
        // pre-existing and intentionally kept — see StructuredAnswer's javadoc; only the
        // *reasoning* field, and any *new* top-level follow_up_questions field, are disallowed.
        var schema = ChatService.dataAnswerJsonSchema();
        @SuppressWarnings("unchecked")
        var properties = (java.util.Map<String, Object>) schema.get("properties");
        assertFalse(properties.containsKey("follow_up_questions_reasoning"));
        assertFalse(properties.containsKey("follow_up_questions"),
                "FOLLOW_UP_QUESTIONS remains exclusively a sections[] entry — no separate top-level field");
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // dataAnswerJsonSchema() — structural well-formedness of the strict schema itself (PRO-XX
    // follow_up_questions mandatory-field hardening). Confirms the schema is valid, serializable JSON
    // Schema shape — not a live API round-trip (that is the live-test step of this turn).
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void dataAnswerJsonSchemaIsWellFormedStrictModeShape() throws Exception {
        var schema = ChatService.dataAnswerJsonSchema();

        // Round-trips through real JSON serialization — proves it's valid JSON, not just a Java Map.
        ObjectMapper mapper = new ObjectMapper();
        String serialized = mapper.writeValueAsString(schema);
        var reparsed = mapper.readValue(serialized, java.util.Map.class);
        assertEquals("object", reparsed.get("type"));

        assertEquals(Boolean.FALSE, schema.get("additionalProperties"),
                "strict mode requires additionalProperties:false at the top level");

        var properties = (java.util.Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("answer"));
        assertTrue(properties.containsKey("sections"));
        assertTrue(properties.containsKey("metrics"));
        assertFalse(properties.containsKey("follow_up_questions_reasoning"),
                "the former independent top-level reasoning field must be gone completely — no compatibility field");
        assertFalse(properties.containsKey("follow_up_questions"),
                "FOLLOW_UP_QUESTIONS is exclusively a sections[] entry — never a separate top-level field either");

        var required = (List<String>) schema.get("required");
        // Strict mode requires every top-level property to be listed in "required".
        assertEquals(properties.keySet(), java.util.Set.copyOf(required));
        assertEquals(java.util.Set.of("answer", "sections", "metrics"), properties.keySet());

        var sectionsSchema = (java.util.Map<String, Object>) properties.get("sections");
        assertEquals("array", sectionsSchema.get("type"));
        var sectionItemSchema = (java.util.Map<String, Object>) sectionsSchema.get("items");
        assertEquals(Boolean.FALSE, sectionItemSchema.get("additionalProperties"));
        var sectionProps = (java.util.Map<String, Object>) sectionItemSchema.get("properties");
        var sectionRequired = (List<String>) sectionItemSchema.get("required");
        assertEquals(sectionProps.keySet(), java.util.Set.copyOf(sectionRequired),
                "every Section property must be schema-required (nullable types express optionality)");
        assertTrue(sectionProps.containsKey("reasoning"),
                "reasoning is a property of the shared Section schema — structurally attached to the "
                        + "same object as items, the fix for the FOLLOW_UP_QUESTIONS reasoning/items divergence");
        assertEquals(List.of("string", "null"), ((java.util.Map<String, Object>) sectionProps.get("reasoning")).get("type"));

        var metricsSchema = (java.util.Map<String, Object>) properties.get("metrics");
        var metricItemSchema = (java.util.Map<String, Object>) metricsSchema.get("items");
        assertEquals(Boolean.FALSE, metricItemSchema.get("additionalProperties"));
    }

    // Domain neutrality — nothing about parsing assumes any business domain; a healthcare-shaped
    // payload flows through identically to a purchasing-shaped one.
    @Test
    void domainNeutralParsing_healthcarePayload() {
        String json = """
                {"answer": "Average patient wait time increased to 42 minutes this week.",
                 "key_findings": ["The increase is concentrated in the emergency department."],
                 "recommendation": "Review emergency department staffing allocation."}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals("Average patient wait time increased to 42 minutes this week.", result.answer());
        assertEquals(1, result.keyFindings().size());
        assertEquals("Review emergency department staffing allocation.", result.recommendation());
    }
}
