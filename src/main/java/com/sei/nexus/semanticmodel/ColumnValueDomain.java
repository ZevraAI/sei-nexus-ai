package com.sei.nexus.semanticmodel;

import java.util.List;
import java.util.Map;

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
 * @param labels        optional physical-value → Business Value name (semantic meaning), empty when
 *                      no Business Value mappings exist. Presentation-only: consumed by prompt
 *                      construction, never by literal validation or execution.
 */
public record ColumnValueDomain(String table, String column, boolean authoritative,
                                List<String> values, Map<String, String> labels) {

    public ColumnValueDomain {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    /**
     * A domain with no Business Value labels — used by paths that need only membership (e.g. the
     * literal-validation scope). Backward-compatible with every existing 4-arg call site.
     */
    public ColumnValueDomain(String table, String column, boolean authoritative, List<String> values) {
        this(table, column, authoritative, values, Map.of());
    }

    /** {@code table.column} — the qualified identity of the domain-bearing column. */
    public String qualifiedColumn() {
        return table + "." + column;
    }

    /** The Business Value name for a physical value, or {@code null} when it is unmapped. */
    public String labelFor(String value) {
        return labels.get(value);
    }
}
