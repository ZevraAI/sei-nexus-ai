package com.sei.nexus.reasoning;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 Structured Outputs — {@link ReasoningEvaluator#evaluatorJsonSchema}. Proves the schema
 * satisfies OpenAI strict-mode constraints and preserves the exact three-field contract {@link
 * ReasoningEvaluator#evaluate} already parses ({@code resultSetMatches}/{@code decision}/{@code
 * rationale}) — no field added, none removed, {@code decision}'s enum matches exactly what
 * SYSTEM_PROMPT asks for. The deterministic clamp itself is covered by {@link
 * ReasoningEvaluatorResultSetMatchesClampTest}; this test only proves the schema's own shape.
 */
class ReasoningEvaluatorJsonSchemaTest {

    @Test
    void rootIsAPlainObjectWithExactlyTheThreeFieldsAllRequired() {
        Map<String, Object> schema = ReasoningEvaluator.evaluatorJsonSchema();
        assertEquals("object", schema.get("type"));
        assertFalse(schema.containsKey("anyOf"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");

        Set<String> expected = Set.of("resultSetMatches", "decision", "rationale");
        assertEquals(expected, properties.keySet());
        assertEquals(expected, Set.copyOf(required));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
    }

    @Test
    void decisionEnumMatchesExactlyWhatTheSystemPromptAsksFor() {
        Map<String, Object> schema = ReasoningEvaluator.evaluatorJsonSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> decisionSchema = (Map<String, Object>) properties.get("decision");
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) decisionSchema.get("enum");

        assertEquals(Set.of("SUFFICIENT", "NEED_MORE_DATA", "DEAD_END"), Set.copyOf(enumValues),
                "must match the prompt's own decision values exactly — NEED_DIFFERENT_APPROACH is never "
                        + "requested by SYSTEM_PROMPT, so it is intentionally not part of the enforced enum");
    }

    @Test
    void resultSetMatchesIsANonNullableBooleanNeverAllowingTheClampToBeSkippedByAMissingField() {
        Map<String, Object> schema = ReasoningEvaluator.evaluatorJsonSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultSetMatchesSchema = (Map<String, Object>) properties.get("resultSetMatches");

        assertEquals("boolean", resultSetMatchesSchema.get("type"),
                "always present as a real boolean per the strict schema — never absent for the clamp to miss");
    }
}
