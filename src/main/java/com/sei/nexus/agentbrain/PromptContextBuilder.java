package com.sei.nexus.agentbrain;

import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
}
