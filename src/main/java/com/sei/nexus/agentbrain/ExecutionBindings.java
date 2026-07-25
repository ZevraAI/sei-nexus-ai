package com.sei.nexus.agentbrain;

import java.util.Map;
import java.util.Set;

/**
 * The execution plane of an {@link ExecutionContract} (ADR-0003 semantic model, Phase 1B):
 * semantic-reference → execution-target bindings, plus the precomputed {@code approvedAssets}
 * the runtime enforces against. Keyed by {@code objectKey}/{@code attributeKey}; bindings are
 * held here, never nested on the semantic types. Targets are SQL today ({@code kind = "SQL"});
 * the {@code kind} allows other execution strategies later. 1:1 population today; the maps are
 * structurally N:M-ready.
 */
public record ExecutionBindings(
        Map<String, ExecutionTarget> objectBindings,     // objectKey    -> table target
        Map<String, ExecutionTarget> attributeBindings,  // attributeKey -> column target
        Set<ApprovedAsset>           approvedAssets       // precomputed enforcement surface
) {
    public ExecutionBindings {
        objectBindings    = Map.copyOf(objectBindings);
        attributeBindings = Map.copyOf(attributeBindings);
        approvedAssets    = Set.copyOf(approvedAssets);
    }

    /** Pure membership check used by the runtime gate — no derivation, no interpretation. */
    public boolean isApproved(String connectionKey, String canonicalIdentifier) {
        return approvedAssets.contains(new ApprovedAsset(connectionKey, canonicalIdentifier));
    }

    /**
     * An execution target; {@code column} is null for object (table) targets. {@code dataType}
     * and {@code valueDomain} are the column's physical SQL type and its persisted legal/observed
     * values when known (column targets only) — semantic grounding projected from the Enterprise
     * Map, reusable by every execution policy; {@code null} otherwise.
     */
    public record ExecutionTarget(String kind, String connectionKey, String schema,
                                  String table, String column, String dataType,
                                  com.sei.nexus.semanticmodel.ColumnValueDomain valueDomain) {

        /** A target without a physical data type (object/table targets). */
        public ExecutionTarget(String kind, String connectionKey, String schema,
                               String table, String column) {
            this(kind, connectionKey, schema, table, column, null, null);
        }

        /** A column target with a data type but no value domain. */
        public ExecutionTarget(String kind, String connectionKey, String schema,
                               String table, String column, String dataType) {
            this(kind, connectionKey, schema, table, column, dataType, null);
        }
    }

    public record ApprovedAsset(String connectionKey, String canonicalIdentifier) {}
}
