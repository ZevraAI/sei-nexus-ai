package com.sei.nexus.pack;

import java.util.List;

/**
 * Global Concept Resolution (Phase 1, read-only) — the full resolution result for one
 * tenant Business Object against its connection's assigned Industry Pack. Never persisted;
 * returned directly from {@link GlobalConceptResolver} for inspection only.
 */
public record BusinessObjectResolution(
        String connectionKey,
        String packKey,
        String entityKey,
        String entityName,
        String objectKey,
        String tableName,
        ResolutionOutcome outcome,
        List<ConceptCandidate> candidates
) {}
