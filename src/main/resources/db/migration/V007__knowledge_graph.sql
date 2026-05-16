-- V007: Knowledge Graph
-- Enriches nexus_business_entity and nexus_entity_relationship with graph
-- metadata, then seeds the full logistics knowledge graph.

-- ── 1. Enrich entity table ────────────────────────────────────────────────────
ALTER TABLE nexus_business_entity
    ADD COLUMN IF NOT EXISTS node_type   VARCHAR(32)  NOT NULL DEFAULT 'ENTITY',
    ADD COLUMN IF NOT EXISTS color       VARCHAR(16),
    ADD COLUMN IF NOT EXISTS group_label VARCHAR(64);

-- ── 2. Enrich relationship table ─────────────────────────────────────────────
ALTER TABLE nexus_entity_relationship
    ADD COLUMN IF NOT EXISTS cardinality   VARCHAR(8),
    ADD COLUMN IF NOT EXISTS bidirectional BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS join_sql      TEXT,
    ADD COLUMN IF NOT EXISTS edge_color    VARCHAR(16);

-- ── 3. Ensure PLATFORM domain exists (self-contained for fresh installs) ────────
INSERT INTO nexus_domain (domain_key, name, description, owner_team, status)
VALUES ('PLATFORM', 'Zevra Platform', 'Default platform domain', 'Platform Team', 'ACTIVE')
ON CONFLICT (domain_key) DO NOTHING;

-- ── 4. Seed logistics knowledge-graph nodes ───────────────────────────────────
INSERT INTO nexus_business_entity
    (entity_key, domain_key, entity_name, description, node_type, color, group_label,
     primary_object_key, operational_meaning, investigation_hints,
     status, created_by, created_at, updated_at)
VALUES

('lgs-supplier', 'PLATFORM', 'Supplier',
 'A vendor or manufacturer who supplies goods via purchase orders.',
 'ENTITY', '#0C5847', 'Procurement',
 'platform-local-postgres-lgs-supplier',
 'Companies that provide goods to warehouses. Key attributes: rating (1-5), lead_time_days, status.',
 'To find underperforming suppliers: JOIN lgs_purchase_order ON supplier_id WHERE status=''OVERDUE'' GROUP BY supplier_id.',
 'ACTIVE', 'system', NOW(), NOW()),

('lgs-purchase-order', 'PLATFORM', 'Purchase Order',
 'An order placed with a supplier to procure goods.',
 'TRANSACTION', '#1D6E75', 'Procurement',
 'platform-local-postgres-lgs-purchase-order',
 'Tracks procurement lifecycle. status values: OPEN, IN_TRANSIT, RECEIVED, OVERDUE. OVERDUE = expected_at passed, received_at is null.',
 'Overdue count by supplier: SELECT supplier_id, COUNT(*) FROM lgs_purchase_order WHERE status=''OVERDUE'' GROUP BY supplier_id.',
 'ACTIVE', 'system', NOW(), NOW()),

('lgs-po-line', 'PLATFORM', 'PO Line Item',
 'An individual product line within a purchase order.',
 'DETAIL', '#2563EB', 'Procurement',
 'platform-local-postgres-lgs-purchase-order-line',
 'Itemised breakdown of a PO. qty_received < qty_ordered indicates a shortfall or partial delivery.',
 'Unfulfilled items: SELECT * FROM lgs_purchase_order_line WHERE qty_received < qty_ordered.',
 'ACTIVE', 'system', NOW(), NOW()),

('lgs-product', 'PLATFORM', 'Product',
 'A product SKU in the catalogue with cost, weight, and reorder thresholds.',
 'REFERENCE', '#7C3AED', 'Inventory',
 'platform-local-postgres-lgs-product',
 'Defines products: sku (unique), category, unit_cost, unit_weight_kg, reorder_point. Below reorder_point = low stock.',
 'Low-stock products: SELECT p.sku, p.reorder_point, i.qty_on_hand FROM lgs_product p JOIN lgs_inventory i ON i.product_id = p.product_id WHERE i.qty_on_hand < p.reorder_point.',
 'ACTIVE', 'system', NOW(), NOW()),

('lgs-inventory', 'PLATFORM', 'Inventory',
 'Current stock level per product per warehouse.',
 'METRIC', '#D97706', 'Inventory',
 'platform-local-postgres-lgs-inventory',
 'qty_on_hand = total physical stock. qty_reserved = committed to outbound orders. Available = qty_on_hand - qty_reserved.',
 'Critical low-stock: compare qty_on_hand to product.reorder_point joined on product_id.',
 'ACTIVE', 'system', NOW(), NOW()),

('lgs-warehouse', 'PLATFORM', 'Warehouse',
 'A physical distribution centre identified by a regional code.',
 'REFERENCE', '#059669', 'Logistics',
 'platform-local-postgres-lgs-warehouse',
 'Warehouses: WH-EAST (Newark), WH-WEST (Los Angeles), WH-EURO (Rotterdam), WH-APAC (Singapore). Each holds inventory and dispatches shipments.',
 'Filter by warehouse_code to analyse regional performance.',
 'ACTIVE', 'system', NOW(), NOW()),

('lgs-shipment', 'PLATFORM', 'Shipment',
 'An outbound delivery to a customer tracked from dispatch to delivery.',
 'TRANSACTION', '#DC2626', 'Logistics',
 'platform-local-postgres-lgs-shipment',
 'status values: PENDING, SHIPPED, IN_TRANSIT, DELIVERED, DELAYED. DELAYED = promised_at passed, delivered_at is null. priority: STANDARD, EXPRESS, ECONOMY.',
 'Delayed shipments: SELECT * FROM lgs_shipment WHERE status=''DELAYED'' ORDER BY promised_at.',
 'ACTIVE', 'system', NOW(), NOW()),

('lgs-customer', 'PLATFORM', 'Customer',
 'An external customer who receives shipments.',
 'ENTITY', '#DB2777', 'Sales',
 'platform-local-postgres-lgs-customer',
 'Customer master: customer_code (unique), name, region, country, credit_limit, status.',
 'High-risk customers: join lgs_shipment on customer_id, count DELAYED status, sort descending.',
 'ACTIVE', 'system', NOW(), NOW()),

('lgs-delivery-event', 'PLATFORM', 'Delivery Event',
 'A tracking milestone on a shipment (pickup, transit, exception, delivery).',
 'EVENT', '#9D174D', 'Logistics',
 'platform-local-postgres-lgs-shipment',
 'event_type values: PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION. Each EXCEPTION event signals a problem.',
 'Problem shipments: SELECT shipment_id, COUNT(*) FROM lgs_delivery_event WHERE event_type=''EXCEPTION'' GROUP BY shipment_id.',
 'ACTIVE', 'system', NOW(), NOW())

ON CONFLICT (entity_key) DO UPDATE SET
    entity_name         = EXCLUDED.entity_name,
    description         = EXCLUDED.description,
    node_type           = EXCLUDED.node_type,
    color               = EXCLUDED.color,
    group_label         = EXCLUDED.group_label,
    primary_object_key  = EXCLUDED.primary_object_key,
    operational_meaning = EXCLUDED.operational_meaning,
    investigation_hints = EXCLUDED.investigation_hints,
    updated_at          = NOW();

-- ── 4. Seed logistics knowledge-graph edges ───────────────────────────────────
INSERT INTO nexus_entity_relationship
    (relationship_key, source_entity_key, target_entity_key,
     relationship_type, source_column, target_column,
     join_guidance, cross_system, cardinality, bidirectional, edge_color, created_at)
VALUES

('rel-supplier-po', 'lgs-supplier', 'lgs-purchase-order',
 'HAS_MANY', 'supplier_id', 'supplier_id',
 'JOIN lgs_purchase_order po ON po.supplier_id = s.supplier_id',
 FALSE, '1:N', FALSE, '#0C5847', NOW()),

('rel-po-poline', 'lgs-purchase-order', 'lgs-po-line',
 'HAS_MANY', 'po_id', 'po_id',
 'JOIN lgs_purchase_order_line pol ON pol.po_id = po.po_id',
 FALSE, '1:N', FALSE, '#1D6E75', NOW()),

('rel-poline-product', 'lgs-po-line', 'lgs-product',
 'REFERENCES', 'product_id', 'product_id',
 'JOIN lgs_product p ON p.product_id = pol.product_id',
 FALSE, 'N:1', FALSE, '#2563EB', NOW()),

('rel-product-inventory', 'lgs-product', 'lgs-inventory',
 'HAS_MANY', 'product_id', 'product_id',
 'JOIN lgs_inventory i ON i.product_id = p.product_id',
 FALSE, '1:N', FALSE, '#7C3AED', NOW()),

('rel-inventory-warehouse', 'lgs-inventory', 'lgs-warehouse',
 'REFERENCES', 'warehouse_id', 'warehouse_id',
 'JOIN lgs_warehouse w ON w.warehouse_id = i.warehouse_id',
 FALSE, 'N:1', FALSE, '#059669', NOW()),

('rel-warehouse-shipment', 'lgs-warehouse', 'lgs-shipment',
 'HAS_MANY', 'warehouse_id', 'warehouse_id',
 'JOIN lgs_shipment sh ON sh.warehouse_id = w.warehouse_id',
 FALSE, '1:N', FALSE, '#DC2626', NOW()),

('rel-shipment-customer', 'lgs-shipment', 'lgs-customer',
 'REFERENCES', 'customer_id', 'customer_id',
 'JOIN lgs_customer c ON c.customer_id = sh.customer_id',
 FALSE, 'N:1', FALSE, '#DB2777', NOW()),

('rel-shipment-event', 'lgs-shipment', 'lgs-delivery-event',
 'HAS_MANY', 'shipment_id', 'shipment_id',
 'JOIN lgs_delivery_event de ON de.shipment_id = sh.shipment_id',
 FALSE, '1:N', FALSE, '#9D174D', NOW())

ON CONFLICT (relationship_key) DO UPDATE SET
    relationship_type = EXCLUDED.relationship_type,
    source_column     = EXCLUDED.source_column,
    target_column     = EXCLUDED.target_column,
    join_guidance     = EXCLUDED.join_guidance,
    cardinality       = EXCLUDED.cardinality,
    bidirectional     = EXCLUDED.bidirectional,
    edge_color        = EXCLUDED.edge_color;
