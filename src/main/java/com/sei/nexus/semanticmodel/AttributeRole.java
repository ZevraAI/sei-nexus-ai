package com.sei.nexus.semanticmodel;

/**
 * The primary analytical role of a {@link BusinessAttribute} (ADR-0003 semantic model,
 * Phase 1A). A closed, mutually-exclusive taxonomy from dimensional modelling.
 *
 * <p>Orthogonal traits (temporal, status, derived, sensitive, …) are deliberately NOT
 * introduced here; they arrive in later phases as optional facets, never as new roles.
 */
public enum AttributeRole {
    /** A key or join field (e.g. {@code id}, {@code product_id}). */
    IDENTIFIER,
    /** A categorical / groupable / filterable field (e.g. {@code status}, {@code region}). */
    DIMENSION,
    /** A quantitative, aggregatable field (e.g. {@code on_hand_qty}, {@code unit_cost}). */
    MEASURE,
    /** A descriptive field that is none of the above (e.g. a name or free-text description). */
    ATTRIBUTE
}
