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

    /**
     * Physical columns known for each table this connection resolves — a purely structural
     * projection of {@link #semanticView}'s object→attribute association through {@link
     * #executionBindings}, computed here (not by the Runtime) so the Runtime's column-existence
     * gate (defense-in-depth) never reads {@link #semanticView} itself: it consumes only this
     * table→columns map, the same physical-identifier vocabulary {@link ExecutionBindings}
     * already speaks. This keeps the Runtime deterministic and unaware of business meaning,
     * exactly as {@link SemanticView}'s own javadoc requires ("the Runtime never reads it").
     *
     * @return lower-cased bare table name → lower-cased column names, restricted to objects
     *         bound to {@code connectionKey}.
     */
    public java.util.Map<String, java.util.Set<String>> columnsByTable(String connectionKey) {
        java.util.Map<String, java.util.Set<String>> byTable = new java.util.LinkedHashMap<>();
        for (com.sei.nexus.semanticmodel.BusinessObject object : semanticView.businessObjects()) {
            ExecutionBindings.ExecutionTarget objectTarget =
                    executionBindings.objectBindings().get(object.objectKey());
            if (objectTarget == null || objectTarget.table() == null) continue;
            if (connectionKey != null && !connectionKey.equals(objectTarget.connectionKey())) continue;

            String tableKey = objectTarget.table().toLowerCase(java.util.Locale.ROOT);
            java.util.Set<String> columns = byTable.computeIfAbsent(tableKey, k -> new java.util.HashSet<>());
            for (com.sei.nexus.semanticmodel.BusinessAttribute attribute : object.attributes()) {
                ExecutionBindings.ExecutionTarget attrTarget =
                        executionBindings.attributeBindings().get(attribute.attributeKey());
                if (attrTarget != null && attrTarget.column() != null) {
                    columns.add(attrTarget.column().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return byTable;
    }
}
