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
        List<String>        entitiesUnmatched,
        // Make Apply Pack Perform LLM Concept Classification: how many of the connection's
        // EXISTING Business Entities the LLM assigned a valid concept_key to, and how many were
        // analyzed but left concept_key = NULL (the LLM found no confident match — never a
        // failure, and never a Java-invented fallback). Entities whose analysis errored
        // entirely (not "unresolved" — genuinely failed) are counted in neither number.
        int                 entitiesClassified,
        int                 entitiesUnresolved
) {}
