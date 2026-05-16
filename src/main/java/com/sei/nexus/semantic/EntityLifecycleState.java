package com.sei.nexus.semantic;

import java.time.Instant;

public record EntityLifecycleState(
    String stateKey,
    String entityKey,
    String stateName,
    String stateCode,
    String meaning,
    boolean isTerminal,
    boolean isException,
    Integer normalSequence,
    String nextStates,
    String detectionRule,
    Instant createdAt
) {}
