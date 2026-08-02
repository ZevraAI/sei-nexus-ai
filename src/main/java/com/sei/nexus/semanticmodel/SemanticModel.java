package com.sei.nexus.semanticmodel;

import java.util.List;
import java.util.Map;

/**
 * The assembler's output (ADR-0003 semantic model, Phase 1B): both planes as raw material.
 *
 * <p>{@code objects} is the semantic plane (AgentBrain reasons over this). {@code objectTargets}
 * and {@code attributeTargets} are the execution-plane raw material, keyed by {@code objectKey}
 * and {@code attributeKey}, which the ExecutionContractBuilder compiles into ExecutionBindings.
 * The semantic types themselves carry no physical detail.
 */
public record SemanticModel(
        List<BusinessObject>        objects,
        Map<String, PhysicalTable>  objectTargets,     // objectKey    -> table
        Map<String, PhysicalColumn> attributeTargets   // attributeKey -> column
) {
    public SemanticModel {
        objects          = List.copyOf(objects);
        objectTargets    = Map.copyOf(objectTargets);
        attributeTargets = Map.copyOf(attributeTargets);
    }
}
