package com.sei.nexus.pack;

import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * DIAGNOSTIC ONLY (real DB, real tenant data — never runs in the normal suite, per the
 * {@code *Validation} naming convention already established this session). Global Concept
 * Resolution — Phase 1 real-tenant validation, against {@code tenant_retail_industry} /
 * {@code conn-25c3ce28} (the same real connection used throughout this session's other live
 * validations). Writes nothing — {@link GlobalConceptResolver} is read-only by construction.
 *
 * <p><b>Why a synthetic-but-representative concept catalog, not the real {@code retail-v1.json}
 * </b>: the shipped {@code retail-v1.json} pack has zero {@code groups()}/{@code concept_key}
 * data yet (that content was never authored — this task explicitly must not modify Pack JSON
 * files), and its 8 existing entities (Product, Customer, Transaction, Store, Category,
 * Inventory, Return, Supplier) are coarser than the concepts this design work has been
 * discussing (Purchase Order, Supplier Contract, Inventory Adjustment, ...) — running the
 * resolver against the real file today would trivially produce UNRESOLVED for everything and
 * prove nothing about the resolver's actual capability. This validation instead constructs an
 * in-memory catalog representative of what a populated Retail pack would eventually contain,
 * covering the real tenant's actual tables, and runs the real resolver against real
 * {@code nexus_business_entity}/{@code nexus_data_object}/{@code nexus_data_column} rows and a
 * real (live, read-only) source-database comment lookup. This substitution is reported
 * explicitly, not hidden.
 */
@SpringBootTest
class GlobalConceptResolutionRealTenantValidation {

    @Autowired private GlobalConceptResolver resolver;

    private static final String TENANT_SCHEMA = "tenant_retail_industry";
    private static final String CONNECTION_KEY = "conn-25c3ce28";

    private static PackEntity concept(String conceptKey, String name, List<String> tablePatterns,
                                       List<String> keyColumnPatterns, List<String> aliases) {
        return new PackEntity(name, aliases, tablePatterns, keyColumnPatterns,
                "desc for " + name, "meaning for " + name, conceptKey, "ACTIVE");
    }

    /** Representative of what a populated retail-v1 pack would contain — see class javadoc. */
    private static IndustryPack representativeRetailPack() {
        PackGroup procurement = new PackGroup("procurement", "Procurement", List.of(
                concept("purchase_order", "Purchase Order",
                        List.of("purchase_order", "purchase_orders", "po_header", "po_hdr"),
                        List.of("po_id", "purchase_order_id", "po_number"),
                        List.of("po", "purchase order")),
                concept("purchase_order_line", "Purchase Order Line",
                        List.of("purchase_order_line", "purchase_order_lines", "po_line", "po_detail"),
                        List.of("po_line_id", "purchase_order_line_id"),
                        List.of("po line")),
                concept("supplier", "Supplier",
                        List.of("supplier", "suppliers", "vendor", "vendors"),
                        List.of("supplier_id", "vendor_id", "supplier_code"),
                        List.of("vendor", "manufacturer")),
                concept("supplier_contract", "Supplier Contract",
                        List.of("supplier_contract", "supplier_contracts", "vendor_agreement"),
                        List.of("contract_id", "supplier_contract_id"),
                        List.of("vendor contract", "supply agreement"))));
        PackGroup inventory = new PackGroup("inventory", "Inventory", List.of(
                concept("inventory_balance", "Inventory Balance",
                        List.of("inventory_balance", "inventory_balances", "stock_position", "stock_on_hand"),
                        List.of("balance_id", "inventory_balance_id"),
                        List.of("stock level", "on hand")),
                concept("inventory_adjustment", "Inventory Adjustment",
                        List.of("inventory_adjustment", "inventory_adjustments", "inv_adj", "inv_adj_stg", "stock_correction"),
                        List.of("adjustment_id", "inventory_adjustment_id", "adjustment_number"),
                        List.of("stock adjustment", "count correction")),
                concept("inventory_adjustment_line", "Inventory Adjustment Line",
                        List.of("inventory_adjustment_line", "inventory_adjustment_lines"),
                        List.of("adjustment_line_id"),
                        List.of("adjustment detail")),
                concept("inventory_transaction", "Inventory Transaction",
                        List.of("inventory_transaction", "inventory_transactions", "stock_movement"),
                        List.of("transaction_id", "inventory_transaction_id"),
                        List.of("stock movement")),
                concept("warehouse", "Warehouse",
                        List.of("warehouse", "warehouses", "distribution_center", "dc"),
                        List.of("warehouse_id", "warehouse_code"),
                        List.of("distribution center", "depot")),
                concept("warehouse_zone", "Warehouse Zone",
                        List.of("warehouse_zone", "warehouse_zones", "bin_location"),
                        List.of("zone_id", "warehouse_zone_id"),
                        List.of("zone", "bin"))));
        PackGroup sales = new PackGroup("sales", "Sales", List.of(
                concept("sales_transaction", "Sales Transaction",
                        List.of("sales_transaction", "sales_transactions", "sale_header"),
                        List.of("transaction_id", "sales_transaction_id", "transaction_number"),
                        List.of("sale", "sales order")),
                concept("sales_transaction_line", "Sales Transaction Line",
                        List.of("sales_transaction_line", "sales_transaction_lines", "sale_line"),
                        List.of("line_id", "sales_transaction_line_id"),
                        List.of("sale line", "sales order line")),
                concept("receipt", "Receipt",
                        List.of("receipt", "receipts", "goods_receipt"),
                        List.of("receipt_id", "receipt_number"),
                        List.of("goods receipt")),
                concept("receipt_line", "Receipt Line",
                        List.of("receipt_line", "receipt_lines"),
                        List.of("receipt_line_id"),
                        List.of("receipt detail"))));
        PackGroup merchandising = new PackGroup("merchandising", "Merchandising", List.of(
                concept("product", "Product",
                        List.of("product", "products", "item", "sku"),
                        List.of("product_id", "sku_id", "item_id"),
                        List.of("sku", "item", "article")),
                concept("product_category", "Product Category",
                        List.of("product_category", "product_categories", "category", "department"),
                        List.of("category_id", "product_category_id"),
                        List.of("department", "classification")),
                concept("promotion", "Promotion",
                        List.of("promotion", "promotions", "discount_campaign"),
                        List.of("promotion_id", "promo_id"),
                        List.of("discount", "campaign")),
                concept("store", "Store",
                        List.of("store", "stores", "location", "branch"),
                        List.of("store_id", "location_id"),
                        List.of("location", "branch", "outlet")),
                concept("store_target", "Store Target",
                        List.of("store_target", "store_targets", "sales_goal"),
                        List.of("target_id", "store_target_id"),
                        List.of("sales goal", "quota")),
                concept("region", "Region",
                        List.of("region", "regions", "territory"),
                        List.of("region_id", "region_code"),
                        List.of("territory", "area")),
                concept("fiscal_period", "Fiscal Period",
                        List.of("fiscal_period", "fiscal_periods", "accounting_period"),
                        List.of("period_id", "fiscal_period_id"),
                        List.of("accounting period"))));

        return new IndustryPack("retail-v1", "RETAIL", "Retail & E-commerce", "1.1.0-representative", "desc",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null,
                List.of(procurement, inventory, sales, merchandising));
    }

    @Test
    void resolveRealRetailTenantData() {
        TenantContext.set(TENANT_SCHEMA);
        try {
            resolveAndReport();
        } finally {
            TenantContext.clear();
        }
    }

    private void resolveAndReport() {
        List<BusinessObjectResolution> results = resolver.resolveAgainstPack(CONNECTION_KEY, representativeRetailPack());

        int clear = 0, ambiguous = 0, conflicting = 0, unresolved = 0;
        System.out.println("\n================ GLOBAL CONCEPT RESOLUTION — REAL TENANT VALIDATION ================");
        System.out.printf("%-28s %-28s %-12s%n", "entity", "table", "outcome");
        System.out.println("-".repeat(72));
        for (BusinessObjectResolution r : results) {
            System.out.printf("%-28s %-28s %-12s%n", r.entityName(), r.tableName(), r.outcome());
            switch (r.outcome()) {
                case CLEAR -> clear++;
                case AMBIGUOUS -> ambiguous++;
                case CONFLICTING -> conflicting++;
                case UNRESOLVED -> unresolved++;
            }
            if (r.outcome() != ResolutionOutcome.CLEAR) {
                for (ConceptCandidate c : r.candidates()) {
                    System.out.println("    candidate: " + c.conceptKey() + " (" + c.overallStrength() + ")");
                    for (ConceptEvidence e : c.evidence()) {
                        System.out.println("        - [" + e.strength() + "] " + e.signal() + ": " + e.detail());
                    }
                }
                if (r.candidates().isEmpty()) {
                    System.out.println("    (no candidate reached even WEAK)");
                }
            } else {
                ConceptCandidate c = r.candidates().get(0);
                System.out.println("    -> " + c.conceptKey() + " (" + c.overallStrength() + ")");
                for (ConceptEvidence e : c.evidence()) {
                    System.out.println("        - [" + e.strength() + "] " + e.signal() + ": " + e.detail());
                }
            }
        }
        System.out.println("-".repeat(72));
        System.out.println("Total: " + results.size()
                + " | CLEAR=" + clear + " AMBIGUOUS=" + ambiguous
                + " CONFLICTING=" + conflicting + " UNRESOLVED=" + unresolved);
        System.out.println("======================================================================================\n");
    }
}
