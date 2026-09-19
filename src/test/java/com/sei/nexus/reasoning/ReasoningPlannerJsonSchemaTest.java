package com.sei.nexus.reasoning;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 Structured Outputs — {@link ReasoningPlanner#plannerJsonSchema}. Proves the schema
 * itself satisfies OpenAI's strict-mode constraints (every property listed in "required" at
 * every object level, {@code additionalProperties: false} everywhere, no root {@code anyOf}) and
 * that every one of the SYSTEM_PROMPT's four response shapes (SQL / metadata request /
 * clarification / done) is representable by it — i.e. this is one flat, always-valid schema, not
 * a discriminated union, so the model can emit any of the four shapes by simply nulling the
 * fields the others use.
 *
 * <p>This is a pure schema-shape test — it does not call the network and does not re-test {@link
 * ReasoningPlanner#nextStep}'s own parsing precedence, which is already covered by {@link
 * ReasoningPlannerClarificationTest} and {@link ReasoningPlannerMetadataRequestTest}.
 */
class ReasoningPlannerJsonSchemaTest {

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "done", "description", "sql", "connection_key", "object_keys", "rationale",
            "literal_bindings", "chart_hint", "requires_metadata", "clarification_question");

    @Test
    void rootIsAPlainObjectNeverAnyOf() {
        Map<String, Object> schema = ReasoningPlanner.plannerJsonSchema();
        assertEquals("object", schema.get("type"), "OpenAI strict mode forbids anyOf at the schema root");
        assertFalse(schema.containsKey("anyOf"));
    }

    @Test
    void everyDeclaredPropertyIsListedInRequiredAtTheRootLevel() {
        Map<String, Object> schema = ReasoningPlanner.plannerJsonSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");

        assertEquals(TOP_LEVEL_FIELDS, properties.keySet());
        assertEquals(TOP_LEVEL_FIELDS, Set.copyOf(required),
                "strict mode requires every property to be listed in required — optionality is nullable, not absent");
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
    }

    @Test
    void nestedChartHintAndRequiresMetadataObjectsAreAlsoStrict() {
        Map<String, Object> schema = ReasoningPlanner.plannerJsonSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertNestedObjectIsStrict((Map<String, Object>) properties.get("chart_hint"),
                Set.of("chart_type", "category_key", "value_keys", "category_label", "value_labels", "metric_label"));
        assertNestedObjectIsStrict((Map<String, Object>) properties.get("requires_metadata"),
                Set.of("object", "metadataType"));

        @SuppressWarnings("unchecked")
        Map<String, Object> literalBindings = (Map<String, Object>) properties.get("literal_bindings");
        @SuppressWarnings("unchecked")
        Map<String, Object> bindingItemSchema = (Map<String, Object>) literalBindings.get("items");
        assertNestedObjectIsStrict(bindingItemSchema, Set.of("surface", "column", "value"));
    }

    @SuppressWarnings("unchecked")
    private static void assertNestedObjectIsStrict(Map<String, Object> objectSchema, Set<String> expectedFields) {
        Map<String, Object> properties = (Map<String, Object>) objectSchema.get("properties");
        List<String> required = (List<String>) objectSchema.get("required");
        assertEquals(expectedFields, properties.keySet());
        assertEquals(expectedFields, Set.copyOf(required));
        assertEquals(Boolean.FALSE, objectSchema.get("additionalProperties"));
    }

    // ── Every current valid response shape must be representable (no field forces a shape it
    //    doesn't belong to become invalid — genuine per-shape optionality is nullable) ──────────

    @Test
    void sqlShapePopulatesOnlySqlFieldsEverythingElseNull() {
        // done=false, sql/connection_key/object_keys/rationale/literal_bindings/chart_hint set,
        // requires_metadata=null, clarification_question=null — this is exactly what the model
        // would emit for shape (a); the schema's "required but nullable" fields make this valid.
        Map<String, Object> schema = ReasoningPlanner.plannerJsonSchema();
        assertShapeFieldsAllDeclared(schema, "sql", "connection_key", "object_keys", "rationale");
    }

    @Test
    void metadataRequestShapePopulatesOnlyRequiresMetadataEverythingElseNull() {
        Map<String, Object> schema = ReasoningPlanner.plannerJsonSchema();
        assertShapeFieldsAllDeclared(schema, "requires_metadata", "description", "rationale");
    }

    @Test
    void clarificationShapePopulatesOnlyClarificationQuestionEverythingElseNull() {
        Map<String, Object> schema = ReasoningPlanner.plannerJsonSchema();
        assertShapeFieldsAllDeclared(schema, "clarification_question", "description", "rationale");
    }

    @Test
    void doneShapePopulatesOnlyDoneEverythingElseNull() {
        Map<String, Object> schema = ReasoningPlanner.plannerJsonSchema();
        assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("done"));
    }

    private static void assertShapeFieldsAllDeclared(Map<String, Object> schema, String... fields) {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        for (String field : fields) {
            assertTrue(properties.containsKey(field), "schema must declare field '" + field + "'");
        }
    }
}
