package com.sei.nexus.semanticmodel;

/**
 * The physical realization of a {@link BusinessAttribute} (ADR-0003 semantic model,
 * Phase 1B). Execution-plane raw material; never appears on the semantic types.
 *
 * <p>{@code dataType} is the column's physical SQL type (e.g. {@code integer}). It is grounding
 * every SQL-generating experience benefits from — type-correct predicates and casts — so it
 * travels on the execution plane rather than the business plane. {@code null} when unknown.
 */
public record PhysicalColumn(String connectionKey, String schema, String table, String column,
                             String dataType, ColumnValueDomain valueDomain) {

    /** A column whose physical data type is not carried. */
    public PhysicalColumn(String connectionKey, String schema, String table, String column) {
        this(connectionKey, schema, table, column, null, null);
    }

    /** A column with a physical data type but no value domain. */
    public PhysicalColumn(String connectionKey, String schema, String table, String column,
                          String dataType) {
        this(connectionKey, schema, table, column, dataType, null);
    }
}
