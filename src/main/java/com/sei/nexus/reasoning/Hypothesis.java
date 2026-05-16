package com.sei.nexus.reasoning;

import java.time.Instant;

public record Hypothesis(
        String hypothesisKey,
        String sessionKey,
        String hypothesisText,
        Double confidence,
        String supportingEvidence,
        String contradictingEvidence,
        String status,
        Instant formedAt,
        Instant resolvedAt
) {}
