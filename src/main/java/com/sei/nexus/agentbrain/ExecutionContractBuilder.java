package com.sei.nexus.agentbrain;

import com.sei.nexus.common.Keys;
import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic compiler (ADR-0003 semantic model, Phase 1B): {@link ResolvedBusinessModel} →
 * immutable {@link ExecutionContract}. It splits the model into the two planes — the
 * {@link SemanticView} (the resolved {@link BusinessObject}s, physical-free) and the
 * {@link ExecutionBindings} (object→table and attribute→column targets, keyed by semantic id,
 * plus the precomputed {@code approvedAssets}) — computes a deterministic {@code semanticHash},
 * and assigns identity.
 *
 * <p>It performs no business reasoning and accesses no repository, assembler, or SemanticService —
 * its only collaborator is the deterministic canonicalization utility.
 */
@Component
public class ExecutionContractBuilder {

    private final SqlTableReferenceExtractor sqlTables;

    public ExecutionContractBuilder(SqlTableReferenceExtractor sqlTables) {
        this.sqlTables = sqlTables;
    }

    public ExecutionContract compile(ResolvedBusinessModel model) {
        Map<String, ExecutionBindings.ExecutionTarget> objectBindings    = new LinkedHashMap<>();
        Map<String, ExecutionBindings.ExecutionTarget> attributeBindings = new LinkedHashMap<>();
        Set<ExecutionBindings.ApprovedAsset>           approvedAssets    = new LinkedHashSet<>();

        for (BusinessObject object : model.objects()) {
            PhysicalTable table = model.objectTargets().get(object.objectKey());
            if (table != null) {
                objectBindings.put(object.objectKey(), new ExecutionBindings.ExecutionTarget(
                        "SQL", table.connectionKey(), table.schema(), table.table(), null));
                // Approve only executable identifiers — the contract's canonical executable identity,
                // independent of any renderer. The schema-qualified form always resolves; the bare
                // form resolves only in the connection's default schema, so it is approved there
                // alone. A bare reference to a non-default schema is therefore never approved.
                boolean hasSchema = table.schema() != null && !table.schema().isBlank();
                if (hasSchema) {
                    approvedAssets.add(new ExecutionBindings.ApprovedAsset(
                            table.connectionKey(), sqlTables.canonical(table.schema() + "." + table.table())));
                }
                if (!hasSchema || SqlTableReferenceExtractor.isDefaultSchema(table.schema())) {
                    approvedAssets.add(new ExecutionBindings.ApprovedAsset(
                            table.connectionKey(), sqlTables.canonical(table.table())));
                }
            }
            for (BusinessAttribute attribute : object.attributes()) {
                PhysicalColumn column = model.attributeTargets().get(attribute.attributeKey());
                if (column != null) {
                    attributeBindings.put(attribute.attributeKey(), new ExecutionBindings.ExecutionTarget(
                            "SQL", column.connectionKey(), column.schema(), column.table(),
                            column.column(), column.dataType(), column.valueDomain()));
                }
            }
        }

        SemanticView      semanticView = new SemanticView(model.objects());
        ExecutionBindings bindings     = new ExecutionBindings(objectBindings, attributeBindings, approvedAssets);

        return new ExecutionContract(
                Keys.uniqueKey("ctr"),
                Instant.now(),
                model.agentId(),
                model.connectionKeys(),
                computeSemanticHash(model.agentId(), approvedAssets),
                semanticView,
                bindings);
    }

    /** Deterministic for equal inputs: a stable hash over the agent and its approved surface. */
    private String computeSemanticHash(String agentId, Set<ExecutionBindings.ApprovedAsset> assets) {
        List<String> parts = new ArrayList<>();
        for (ExecutionBindings.ApprovedAsset a : assets) {
            parts.add(a.connectionKey() + "|" + a.canonicalIdentifier());
        }
        Collections.sort(parts);
        String canonical = (agentId == null ? "" : agentId) + "::" + String.join(",", parts);
        return Integer.toHexString(canonical.hashCode());
    }
}
