package com.sei.nexus.agentbrain;

import java.util.List;

/**
 * Model-agnostic prompt context (ADR-0003 semantic model, Phase 1B): the subset of an
 * {@link ExecutionContract} a model needs to reason and write SQL — business objects with
 * their physical tables, and their attributes with physical columns — isolated from the
 * contract so prompt engineering never leaks into the compiled artifact.
 */
public record PromptContext(List<PromptObject> objects) {
    public PromptContext {
        objects = List.copyOf(objects);
    }

    public boolean isEmpty() {
        return objects.isEmpty();
    }

    /**
     * A business object a model may query. {@code connectionKey} and {@code schema} are the
     * canonical execution identity every SQL-generating policy needs to route and qualify a
     * query; the rendering policy decides whether to surface them.
     */
    public record PromptObject(String businessName, String connectionKey, String schema,
                               String physicalTable, String purpose, String guidance,
                               List<PromptAttribute> attributes) {
        public PromptObject {
            attributes = List.copyOf(attributes);
        }
    }

    /**
     * A business attribute. {@code dataType} is the column's physical type; {@code valueDomain}
     * its persisted legal/observed values — both {@code null} when not known.
     */
    public record PromptAttribute(String businessName, String physicalColumn, String role,
                                  String dataType,
                                  com.sei.nexus.semanticmodel.ColumnValueDomain valueDomain) {}
}
