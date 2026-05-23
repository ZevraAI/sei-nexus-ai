package com.sei.nexus.pack;

import java.util.List;
import java.util.Map;

/** Result returned after successfully applying a pack to a tenant. */
public record PackApplicationResult(
        String              packKey,
        String              displayName,
        int                 entitiesCreated,
        int                 vocabularyTermsAdded,
        int                 suggestedQuestionsAdded,
        double              coverageScore,
        Map<String, String> entityMapping,
        List<String>        entitiesUnmatched
) {}
