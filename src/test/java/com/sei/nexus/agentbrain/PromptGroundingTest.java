package com.sei.nexus.agentbrain;

import com.sei.nexus.semanticmodel.AttributeRole;
import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0003 semantic model, Phase 1B — end-to-end grounding: a compiled ExecutionContract
 * through {@link PromptContextBuilder} → {@link PromptAssembler} presents business attributes
 * with their exact physical columns, so the model never has to invent a column.
 */
class PromptGroundingTest {

    private final ExecutionContractBuilder builder =
            new ExecutionContractBuilder(new SqlTableReferenceExtractor());
    private final PromptContextBuilder promptContextBuilder = new PromptContextBuilder();
    private final PromptAssembler       promptAssembler     = new PromptAssembler();

    @Test
    void groundsBusinessAttributesWithTheirPhysicalColumns() {
        BusinessObject inventory = new BusinessObject("obj-inv", "Inventory Balance", "",
                List.of(new BusinessAttribute("c-onhand", "On Hand Quantity", AttributeRole.MEASURE)),
                List.of());
        BusinessObject product = new BusinessObject("obj-prod", "Product", "",
                List.of(new BusinessAttribute("c-pname", "Product Name", AttributeRole.ATTRIBUTE)),
                List.of());

        ResolvedBusinessModel model = new ResolvedBusinessModel(
                "agent-1", List.of("conn-1"), "show me inventory balances of all the products",
                List.of(inventory, product),
                Map.of("obj-inv",  new PhysicalTable("conn-1", "retail_core", "inventory_balances"),
                       "obj-prod", new PhysicalTable("conn-1", "retail_core", "products")),
                Map.of("c-onhand", new PhysicalColumn("conn-1", "retail_core", "inventory_balances", "on_hand_qty"),
                       "c-pname",  new PhysicalColumn("conn-1", "retail_core", "products", "product_name")));

        String grounding = promptAssembler.assemble(promptContextBuilder.build(builder.compile(model)));

        // Inventory Balance exposes On Hand Quantity as the literal column `on_hand_qty`
        // retail_core is a non-default schema, so the table is qualified for executability even
        // under the compact policy (a bare reference would not resolve).
        assertTrue(grounding.contains("TABLE `retail_core.inventory_balances`"), grounding);
        assertTrue(grounding.contains("`on_hand_qty` (measure)"), grounding);
        assertTrue(grounding.contains("On Hand Quantity"), grounding);
        // Product exposes Product Name as the literal column `product_name`
        assertTrue(grounding.contains("TABLE `retail_core.products`"), grounding);
        assertTrue(grounding.contains("`product_name` (attribute)"), grounding);
        assertTrue(grounding.contains("Product Name"), grounding);
        // the invented column is never presented as an approved (backticked) identifier
        // (the fidelity rules mention quantity_on_hand only as an unbackticked negative example)
        assertFalse(grounding.contains("`quantity_on_hand`"), grounding);
    }

    @Test
    void emptyContractGroundsToNoObjects() {
        ExecutionContract contract = builder.compile(
                new ResolvedBusinessModel("a", List.of("c"), "q", List.of(), Map.of(), Map.of()));
        assertTrue(promptAssembler.assemble(promptContextBuilder.build(contract)).contains("NONE"));
    }
}
