-- Demo logistics schema for Zevra Automations demo workflows.
-- Uses the local (public) schema — accessible by DynamicSqlService via
-- the "nexus-local" connection key that maps to the application datasource.

-- ── Products ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS demo_product (
    product_id      VARCHAR(40)     PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    category        VARCHAR(80)     NOT NULL,
    unit_price      NUMERIC(12,2)   NOT NULL,
    weight_kg       NUMERIC(8,3)    NOT NULL DEFAULT 0,
    sku             VARCHAR(60)     NOT NULL UNIQUE
);

-- ── Inventory ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS demo_inventory (
    product_id      VARCHAR(40)     PRIMARY KEY REFERENCES demo_product(product_id),
    warehouse       VARCHAR(80)     NOT NULL DEFAULT 'MAIN-WH',
    quantity_on_hand INTEGER        NOT NULL DEFAULT 0,
    reorder_level   INTEGER         NOT NULL DEFAULT 10,
    last_updated    TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- ── Orders ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS demo_order (
    order_id        VARCHAR(40)     PRIMARY KEY,
    customer_name   VARCHAR(255)    NOT NULL,
    customer_email  VARCHAR(255)    NOT NULL,
    status          VARCHAR(30)     NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','PROCESSING','SHIPPED','DELIVERED','CANCELLED')),
    total_amount    NUMERIC(14,2)   NOT NULL DEFAULT 0,
    shipping_address TEXT           NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_demo_order_status ON demo_order(status);
CREATE INDEX IF NOT EXISTS idx_demo_order_email  ON demo_order(customer_email);

-- ── Order Line Items ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS demo_order_item (
    item_id         VARCHAR(40)     PRIMARY KEY,
    order_id        VARCHAR(40)     NOT NULL REFERENCES demo_order(order_id) ON DELETE CASCADE,
    product_id      VARCHAR(40)     NOT NULL REFERENCES demo_product(product_id),
    quantity        INTEGER         NOT NULL CHECK (quantity > 0),
    unit_price      NUMERIC(12,2)   NOT NULL,
    line_total      NUMERIC(14,2)   GENERATED ALWAYS AS (quantity * unit_price) STORED
);

CREATE INDEX IF NOT EXISTS idx_demo_order_item_order ON demo_order_item(order_id);

-- ── Shipments ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS demo_shipment (
    shipment_id     VARCHAR(40)     PRIMARY KEY,
    order_id        VARCHAR(40)     NOT NULL REFERENCES demo_order(order_id),
    carrier         VARCHAR(60)     NOT NULL DEFAULT 'FedEx',
    tracking_number VARCHAR(80),
    status          VARCHAR(30)     NOT NULL DEFAULT 'PREPARING'
                        CHECK (status IN ('PREPARING','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED','EXCEPTION')),
    estimated_delivery DATE,
    actual_delivery     DATE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_demo_shipment_order ON demo_shipment(order_id);

-- ── Damage Claims ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS demo_damage_claim (
    claim_id        VARCHAR(40)     PRIMARY KEY,
    order_id        VARCHAR(40)     REFERENCES demo_order(order_id),
    customer_name   VARCHAR(255)    NOT NULL,
    customer_email  VARCHAR(255)    NOT NULL,
    description     TEXT            NOT NULL,
    image_url       TEXT,                         -- URL or base64 reference
    image_base64    TEXT,                         -- stored base64 for AI analysis
    severity        VARCHAR(20)     CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    ai_assessment   TEXT,
    recommended_action VARCHAR(80)  CHECK (recommended_action IN ('REPLACE','REFUND','REPAIR','REJECT','REVIEW')),
    status          VARCHAR(30)     NOT NULL DEFAULT 'SUBMITTED'
                        CHECK (status IN ('SUBMITTED','UNDER_REVIEW','RESOLVED','REJECTED')),
    resolution_note TEXT,
    submitted_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_demo_claim_status ON demo_damage_claim(status);
CREATE INDEX IF NOT EXISTS idx_demo_claim_email  ON demo_damage_claim(customer_email);

-- ── Seed Data: Products ───────────────────────────────────────────────────────
INSERT INTO demo_product (product_id, name, category, unit_price, weight_kg, sku) VALUES
  ('PROD-001', 'Industrial Sensor Module X200', 'Electronics',   299.99, 0.450, 'ISM-X200'),
  ('PROD-002', 'Heavy-Duty Conveyor Belt 5m',   'Machinery',    1249.00, 18.500, 'HDB-5M'),
  ('PROD-003', 'Safety Helmet Pro Grade',        'Safety',        45.00, 0.320, 'SHP-PRO'),
  ('PROD-004', 'Pneumatic Actuator PA-400',      'Machinery',    389.00, 2.100, 'PA-400'),
  ('PROD-005', 'Thermal Camera TC-8000',         'Electronics', 2899.00, 0.780, 'TC-8000'),
  ('PROD-006', 'Steel Storage Rack 4-Tier',      'Storage',      199.00, 22.000, 'SSR-4T'),
  ('PROD-007', 'Vibration Dampener VD-55',       'Machinery',    129.00, 0.650, 'VD-55'),
  ('PROD-008', 'Industrial Ethernet Switch 8P',  'Electronics',  349.00, 0.900, 'IES-8P')
ON CONFLICT (product_id) DO NOTHING;

-- ── Seed Data: Inventory ──────────────────────────────────────────────────────
INSERT INTO demo_inventory (product_id, warehouse, quantity_on_hand, reorder_level) VALUES
  ('PROD-001', 'MAIN-WH',   42, 10),
  ('PROD-002', 'MAIN-WH',    8, 5),
  ('PROD-003', 'MAIN-WH',  200, 50),
  ('PROD-004', 'MAIN-WH',   15, 8),
  ('PROD-005', 'MAIN-WH',    3, 2),
  ('PROD-006', 'WEST-WH',   30, 10),
  ('PROD-007', 'MAIN-WH',   77, 20),
  ('PROD-008', 'EAST-WH',   25, 10)
ON CONFLICT (product_id) DO NOTHING;

-- ── Seed Data: Orders (mix of statuses) ───────────────────────────────────────
INSERT INTO demo_order (order_id, customer_name, customer_email, status, total_amount, shipping_address, created_at) VALUES
  ('ORD-2024-001', 'Meridian Manufacturing',  'ops@meridian.com',  'PENDING',    1799.97, '1200 Industrial Pkwy, Detroit, MI 48201', NOW() - INTERVAL '2 hours'),
  ('ORD-2024-002', 'Atlas Engineering Group', 'supply@atlas.com',  'PROCESSING', 2649.00, '500 Commerce Dr, Houston, TX 77002',    NOW() - INTERVAL '5 hours'),
  ('ORD-2024-003', 'Pinnacle Logistics',      'po@pinnacle.com',   'SHIPPED',    389.00,  '88 Harbor Blvd, Los Angeles, CA 90001', NOW() - INTERVAL '2 days'),
  ('ORD-2024-004', 'Greenfield Industries',   'orders@gfield.com', 'DELIVERED',  699.00,  '240 Tech Park Rd, Austin, TX 78701',    NOW() - INTERVAL '7 days'),
  ('ORD-2024-005', 'CoreTech Solutions',      'cto@coretech.com',  'PENDING',    5798.00, '900 Silicon Way, San Jose, CA 95110',   NOW() - INTERVAL '30 minutes')
ON CONFLICT (order_id) DO NOTHING;

-- ── Seed Data: Order Items ────────────────────────────────────────────────────
INSERT INTO demo_order_item (item_id, order_id, product_id, quantity, unit_price) VALUES
  ('ITEM-001-A', 'ORD-2024-001', 'PROD-001', 3, 299.99),
  ('ITEM-001-B', 'ORD-2024-001', 'PROD-007', 2, 129.00),
  ('ITEM-001-C', 'ORD-2024-001', 'PROD-003', 6,  45.00),
  ('ITEM-002-A', 'ORD-2024-002', 'PROD-002', 1, 1249.00),
  ('ITEM-002-B', 'ORD-2024-002', 'PROD-004', 1,  389.00),
  ('ITEM-002-C', 'ORD-2024-002', 'PROD-008', 3,  349.00),
  ('ITEM-003-A', 'ORD-2024-003', 'PROD-004', 1,  389.00),
  ('ITEM-004-A', 'ORD-2024-004', 'PROD-008', 2,  349.00),
  ('ITEM-005-A', 'ORD-2024-005', 'PROD-005', 2, 2899.00)
ON CONFLICT (item_id) DO NOTHING;

-- ── Seed Data: Shipments ──────────────────────────────────────────────────────
INSERT INTO demo_shipment (shipment_id, order_id, carrier, tracking_number, status, estimated_delivery) VALUES
  ('SHIP-003', 'ORD-2024-003', 'FedEx', '7489023401928374', 'IN_TRANSIT',        CURRENT_DATE + 1),
  ('SHIP-004', 'ORD-2024-004', 'UPS',   '1Z999AA10123456784', 'DELIVERED',       CURRENT_DATE - 1)
ON CONFLICT (shipment_id) DO NOTHING;

-- ── Seed Data: Damage Claims ──────────────────────────────────────────────────
INSERT INTO demo_damage_claim (claim_id, order_id, customer_name, customer_email, description, severity, status) VALUES
  ('CLM-001', 'ORD-2024-004', 'Greenfield Industries', 'orders@gfield.com',
   'Two of the eight Ethernet switches arrived with cracked housing. Internal components appear intact but units fail self-test. Packaging showed signs of impact damage.',
   'HIGH', 'SUBMITTED'),
  ('CLM-002', NULL, 'Westside Fabrication', 'procurement@westside.com',
   'Conveyor belt section arrived with a 30cm tear along the edge seam. Belt cannot be installed in current condition.',
   'CRITICAL', 'UNDER_REVIEW'),
  ('CLM-003', 'ORD-2024-003', 'Pinnacle Logistics', 'po@pinnacle.com',
   'Minor cosmetic scratches on the actuator body. Unit passes all functional tests.',
   'LOW', 'SUBMITTED')
ON CONFLICT (claim_id) DO NOTHING;
