package com.sei.nexus.semantic;

import java.time.Instant;

/**
 * A business term → SQL pattern mapping learned from team usage.
 *
 * <p>Confidence semantics:
 * <ul>
 *   <li>New mapping starts at 0.5</li>
 *   <li>Reinforced (re-used, thumbs-up): +0.05, capped at 1.0</li>
 *   <li>Corrected by user: −0.20, floor at 0.0</li>
 *   <li>use_count ≥ 10 AND confidence ≥ 0.8 → auto-promoted to formal vocabulary</li>
 *   <li>use_count ≥ 5 AND confidence < 0.2 → purged by nightly scheduler</li>
 * </ul>
 *
 * <p>source values: QUERY_SUCCESS | USER_CORRECTION | POSITIVE_FEEDBACK
 *
 * <p>{@code conceptKey}: null = promoted but not yet classified into a concept; required before
 * Vector Store projection. A promoted mapping is never automatically eligible for projection into
 * the tenant's OpenAI Vector Store — projection is keyed off a concept, and there is no reliable,
 * safe way to infer which concept a learned business_term/sql_pattern belongs to from the SQL,
 * domain, or table names alone. This field is set exactly one way: an admin explicitly assigning
 * it (see {@link LearnedMappingRepository#assignConceptKey}) — never inferred.
 */
public record LearnedMapping(
        String  mappingKey,
        String  domainKey,       // null = applies to all domains in this tenant
        String  businessTerm,
        String  sqlPattern,
        String  sourceRunKey,
        String  source,
        double  confidence,
        int     useCount,
        Instant lastUsedAt,
        boolean promoted,
        Instant createdAt,
        Instant updatedAt,
        String  conceptKey
) {}
