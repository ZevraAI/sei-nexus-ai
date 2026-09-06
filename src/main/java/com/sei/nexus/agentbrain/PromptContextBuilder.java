package com.sei.nexus.agentbrain;

import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Derives a model-agnostic {@link PromptContext} from a compiled {@link ExecutionContract}
 * (ADR-0003 semantic model, Phase 1B). Deterministic; performs no business reasoning. It reads
 * the {@link SemanticView} for meaning (objects, attributes, roles) and the
 * {@link ExecutionBindings} for the physical table/column the model must name in SQL — exposing
 * only what a model needs and keeping the contract prompt-agnostic.
 */
@Component
public class PromptContextBuilder {

    public PromptContext build(ExecutionContract contract) {
        List<PromptContext.PromptObject> objects = new ArrayList<>();
        for (BusinessObject object : contract.semanticView().businessObjects()) {
            ExecutionBindings.ExecutionTarget objectTarget =
                    contract.executionBindings().objectBindings().get(object.objectKey());
            String physicalTable = objectTarget != null ? objectTarget.table() : "";
            String connectionKey = objectTarget != null ? objectTarget.connectionKey() : "";
            String schema        = objectTarget != null ? objectTarget.schema() : "";

            List<PromptContext.PromptAttribute> attributes = new ArrayList<>();
            for (BusinessAttribute attribute : object.attributes()) {
                ExecutionBindings.ExecutionTarget attrTarget =
                        contract.executionBindings().attributeBindings().get(attribute.attributeKey());
                String physicalColumn = attrTarget != null ? attrTarget.column() : "";
                String dataType       = attrTarget != null ? attrTarget.dataType() : null;
                com.sei.nexus.semanticmodel.ColumnValueDomain valueDomain =
                        attrTarget != null ? attrTarget.valueDomain() : null;
                attributes.add(new PromptContext.PromptAttribute(
                        attribute.businessName(),
                        physicalColumn,
                        attribute.role().name().toLowerCase(Locale.ROOT),
                        dataType,
                        valueDomain));
            }
            objects.add(new PromptContext.PromptObject(
                    object.businessName(), connectionKey, schema, physicalTable,
                    object.purpose(), object.guidance(), attributes));
        }
        return new PromptContext(objects);
    }

    /**
     * Derives a single {@link PromptContext.PromptObject} directly from semantic + physical raw
     * material — the same transformation {@link #build(ExecutionContract)} performs per object,
     * exposed standalone for a {@link BusinessObject} that has not (yet) been compiled into a
     * request's {@link ExecutionContract}. Used by the Missing-Column Metadata Request fallback
     * (see {@code ColumnMetadataRequestHandler}) to render an object's metadata straight from
     * {@code EnterpriseSemanticAssembler}'s output, without first requiring that object to be
     * part of the current request's resolved/narrowed scope.
     *
     * @param table            the object's physical table, or {@code null} when unknown (an
     *                         empty physical table results, matching {@link #build}'s own
     *                         behavior for an unbound object).
     * @param attributeTargets the same {@code attributeKey -> PhysicalColumn} map the object's
     *                         attributes were assembled with (e.g. {@code SemanticModel
     *                         .attributeTargets()}).
     */
    public PromptContext.PromptObject buildObject(BusinessObject object, PhysicalTable table,
                                                   Map<String, PhysicalColumn> attributeTargets) {
        String physicalTable = table != null ? table.table() : "";
        String connectionKey = table != null ? table.connectionKey() : "";
        String schema        = table != null ? table.schema() : "";

        List<PromptContext.PromptAttribute> attributes = new ArrayList<>();
        for (BusinessAttribute attribute : object.attributes()) {
            PhysicalColumn column = attributeTargets.get(attribute.attributeKey());
            String physicalColumn = column != null ? column.column() : "";
            String dataType       = column != null ? column.dataType() : null;
            com.sei.nexus.semanticmodel.ColumnValueDomain valueDomain =
                    column != null ? column.valueDomain() : null;
            attributes.add(new PromptContext.PromptAttribute(
                    attribute.businessName(), physicalColumn,
                    attribute.role().name().toLowerCase(Locale.ROOT), dataType, valueDomain));
        }
        return new PromptContext.PromptObject(
                object.businessName(), connectionKey, schema, physicalTable,
                object.purpose(), object.guidance(), attributes);
    }
}
