-- V015: Remove logistics demo entity seeds from all schemas.
--
-- V007 previously seeded lgs-* demo entities (Supplier, Purchase Order, etc.)
-- into every tenant schema on provisioning. This caused them to appear in the
-- Knowledge Graph and Semantic Layer alongside the tenant's real entities.
-- V007 no longer inserts them; this migration removes them from existing schemas.

DELETE FROM nexus_entity_relationship
WHERE source_entity_key IN (
    'lgs-supplier','lgs-purchase-order','lgs-po-line',
    'lgs-product','lgs-inventory','lgs-warehouse',
    'lgs-shipment','lgs-customer','lgs-delivery-event'
)
OR target_entity_key IN (
    'lgs-supplier','lgs-purchase-order','lgs-po-line',
    'lgs-product','lgs-inventory','lgs-warehouse',
    'lgs-shipment','lgs-customer','lgs-delivery-event'
);

DELETE FROM nexus_business_entity
WHERE entity_key IN (
    'lgs-supplier','lgs-purchase-order','lgs-po-line',
    'lgs-product','lgs-inventory','lgs-warehouse',
    'lgs-shipment','lgs-customer','lgs-delivery-event'
);
