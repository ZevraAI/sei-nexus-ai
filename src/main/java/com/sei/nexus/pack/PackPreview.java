package com.sei.nexus.pack;

import java.util.List;
import java.util.Map;

/**
 * Dry-run result showing what would be created if a pack were applied.
 * No database changes are made during a preview.
 */
public record PackPreview(
        String              packId,
        String              displayName,
        Map<String, String> entityMapping,       // entity name → matched table name
        List<String>        entitiesUnmatched,   // entities with no confident match
        int                 vocabularyTermCount,
        int                 suggestedQuestionsCount,
        int                 alertTemplateCount,
        double              coverageScore        // matched_entities / total_entities
) {}
