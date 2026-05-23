package com.sei.nexus.semantic;

import java.time.Instant;

/**
 * A detected user correction — a follow-up question that contradicts or refines
 * a previous answer from Zevra.
 *
 * <p>correction_type values:
 * TIMEFRAME | ENTITY | FILTER | METRIC | DIRECTION | OTHER
 */
public record Correction(
        String  correctionKey,
        String  conversationId,
        String  originalRunKey,
        String  correctionRunKey,
        String  originalInterpretation,
        String  correctedInterpretation,
        String  correctionType,
        boolean appliedToContext,
        Instant extractedAt
) {}
