package com.sei.nexus.agentbrain;

import com.sei.nexus.semanticmodel.BusinessObject;

import java.util.List;

/**
 * The business-meaning plane of an {@link ExecutionContract} (ADR-0003 semantic model,
 * Phase 1B): the canonical {@link BusinessObject}s in scope, each composed of its
 * {@link com.sei.nexus.semanticmodel.BusinessAttribute}s. Prompt-agnostic and physical-free —
 * it is the only part of the contract the prompt path reads; the Runtime never reads it.
 */
public record SemanticView(List<BusinessObject> businessObjects) {
    public SemanticView {
        businessObjects = List.copyOf(businessObjects);
    }
}
