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
                  "next_steps": ["Show only partially received orders", "Review orders by supplier"]
                }
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals("Three of five open purchase orders are partially received.", result.answer());
        assertEquals("Three of five orders are partially received, representing $135,300 in value.",
                result.understanding());
        assertEquals(1, result.keyFindings().size());
        assertEquals(1, result.relatedFacts().size());
        assertEquals("Follow up with suppliers on the partially received orders.", result.recommendation());
        assertEquals(2, result.nextSteps().size());
    }

    @Test
    void parsesResponseWithLegitimatelyEmptySections() {
        String json = """
                {"answer": "17 stores are currently open.", "understanding": "17 stores are currently open.",
                 "key_findings": [], "related_facts": [], "recommendation": null, "next_steps": []}
                """;

        StructuredAnswer result = ChatService.parseStructuredAnswer(json, snakeCaseMapper(), "fallback");

        assertEquals("17 stores are currently open.", result.answer());
        assertTrue(result.keyFindings().isEmpty());
        assertTrue(result.relatedFacts().isEmpty());
        assertNull(result.recommendation());
        assertTrue(result.nextSteps().isEmpty());
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
        assertNull(result.nextSteps());
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
