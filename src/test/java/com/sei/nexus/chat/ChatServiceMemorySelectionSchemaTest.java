package com.sei.nexus.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 Structured Outputs — {@link ChatService#memorySelectionJsonSchema}. Proves the schema
 * satisfies OpenAI strict-mode constraints and preserves {@code
 * MEMORY_SELECTION_SYSTEM_PROMPT}'s exact {@code {"entity_keys": ["..."]}} contract — the field
 * name/shape {@link ChatService}'s own exact-roster-membership validation (in {@code
 * buildMemorySelectionContext}, covered by {@link ChatServiceConversationMemoryTest}) already
 * expects. This test only proves the schema's own shape, never re-tests that validation logic.
 */
class ChatServiceMemorySelectionSchemaTest {

    @Test
    void rootIsAPlainObjectWithExactlyEntityKeysRequired() {
        Map<String, Object> schema = ChatService.memorySelectionJsonSchema();
        assertEquals("object", schema.get("type"));
        assertFalse(schema.containsKey("anyOf"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");

        assertEquals(Set.of("entity_keys"), properties.keySet());
        assertEquals(Set.of("entity_keys"), Set.copyOf(required));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
    }

    @Test
    void entityKeysIsAnArrayOfStringsNeverNullableSoAnEmptySelectionIsAlwaysAnEmptyArray() {
        Map<String, Object> schema = ChatService.memorySelectionJsonSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> entityKeysSchema = (Map<String, Object>) properties.get("entity_keys");

        assertEquals("array", entityKeysSchema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) entityKeysSchema.get("items");
        assertEquals("string", items.get("type"));
    }
}
