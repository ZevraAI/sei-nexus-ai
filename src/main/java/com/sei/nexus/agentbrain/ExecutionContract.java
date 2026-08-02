package com.sei.nexus.agentbrain;

import java.time.Instant;
import java.util.List;

/**
 * The immutable, fully compiled business artifact for one agent run (ADR-0003 A10).
 *
 * <p>Produced only by {@link ExecutionContractBuilder} from a {@link ResolvedBusinessModel}.
 * It carries the run's resolved business meaning ({@link SemanticView}, for the prompt
 * path) and its deterministic execution surface ({@link ExecutionBindings}, for the
 * runtime), plus identity for audit / replay / lineage. It contains no business logic and
 * no prompt text, and is never mutated after construction — treat it as a compiled
 * execution plan.
 */
public record ExecutionContract(
        String            contractId,
        Instant           createdAt,
        String            agentId,
        List<String>      connectionKeys,
        String            semanticHash,
        SemanticView      semanticView,
        ExecutionBindings executionBindings
) {
    public ExecutionContract {
        connectionKeys = List.copyOf(connectionKeys);
    }

    /** True when no business object resolved — the agent may execute nothing. */
    public boolean isEmpty() {
        return executionBindings.approvedAssets().isEmpty();
    }
}
