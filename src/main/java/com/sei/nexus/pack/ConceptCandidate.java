package com.sei.nexus.pack;

import java.util.List;

/**
 * Global Concept Resolution (Phase 1, read-only) — one candidate Global Business Concept
 * for a tenant Business Object, with the full evidence trail behind it. Never persisted.
 */
public record ConceptCandidate(
        String groupKey,
        String conceptKey,
        String conceptName,
        List<ConceptEvidence> evidence,
        EvidenceStrength overallStrength
) {}
