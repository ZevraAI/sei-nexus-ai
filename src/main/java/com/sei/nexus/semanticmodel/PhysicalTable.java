package com.sei.nexus.semanticmodel;

/**
 * The physical realization of a {@link BusinessObject} (ADR-0003 semantic model, Phase 1B).
 * Execution-plane raw material produced by the assembler and compiled into the contract's
 * ExecutionBindings; it never appears on the semantic types.
 */
public record PhysicalTable(String connectionKey, String schema, String table) {}
