package com.sei.nexus.semanticmodel;

import java.util.List;

/**
 * The value domain of a business column: the persisted set of values {@code table.column} may
 * hold, with the authority of that knowledge.
 *
 * <p>This is a <b>semantic model</b> fact, not a validation artefact. It states what a column
 * <em>means</em> — which values are legal for it and how complete that knowledge is — and is
 * produced by the Semantic Foundation from discovered/curated metadata. Downstream components
 * consume it: the literal validator gates planned SQL against it, and prompt construction offers
 * its values as constrained choices. Because it is meaning rather than mechanism, it lives with
 * the canonical semantic model so that business reasoning never has to depend on a downstream
 * validation or execution component to express it.
 *
 * <p>Distinct from {@code com.sei.nexus.enterprise.ValueDomain}, which is the persisted storage
 * row (connection/schema scoped, values as JSON). This is the resolved, column-bound projection
 * used at question time.
 *
 * @param table         the physical table the column belongs to
 * @param column        the physical column name
 * @param authoritative {@code true} when the domain is complete by construction (an {@code ENUM}
 *                      declaration) and may gate hard; {@code false} when observed/sampled and
 *                      therefore advisory only
 * @param values        the legal (or observed) values
 */
public record ColumnValueDomain(String table, String column, boolean authoritative,
                                List<String> values) {

    /** {@code table.column} — the qualified identity of the domain-bearing column. */
    public String qualifiedColumn() {
        return table + "." + column;
    }
}
