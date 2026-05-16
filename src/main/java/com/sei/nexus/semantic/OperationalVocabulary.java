package com.sei.nexus.semantic;

import java.time.Instant;

public record OperationalVocabulary(
    String termKey,
    String domainKey,
    String entityKey,
    String term,
    String definition,
    String sqlEquivalent,
    String examples,
    String status,
    Instant createdAt,
    Instant updatedAt
) {}
