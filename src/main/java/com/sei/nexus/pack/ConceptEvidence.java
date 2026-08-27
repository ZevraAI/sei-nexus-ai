package com.sei.nexus.pack;

/**
 * Global Concept Resolution (Phase 1, read-only) — one explainable piece of evidence for
 * or against a candidate concept. {@code signal} names the evidence type (e.g.
 * "table_pattern", "key_column_pattern", "identifier_role", "source_table_comment",
 * "source_column_comment", "udt_enum", "alias", "entity_description", "group_label");
 * {@code detail} is a human-readable explanation of exactly what matched.
 */
public record ConceptEvidence(String signal, String detail, EvidenceStrength strength) {}
