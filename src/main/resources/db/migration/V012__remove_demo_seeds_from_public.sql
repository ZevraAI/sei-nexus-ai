-- V012: Remove V007 demo entity seeds from the public (default workspace) schema.
--
-- V007 seeds logistics demo entities into every schema. In tenant schemas this
-- is useful — it gives a working example knowledge graph. But in the public
-- schema (platform admin workspace) there is no database connection, so these
-- entities mislead users: Knowledge Graph and Semantic Layer show rich data
-- while Chat correctly says "no approved data sources".
--
-- The current_schema() = 'public' guard makes this a safe no-op when Flyway
-- runs this migration against a tenant schema during provisioning.

DO $$
BEGIN
  IF current_schema() != 'public' THEN
    RAISE NOTICE 'V012: not public schema — skipping demo seed removal';
    RETURN;
  END IF;

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

  RAISE NOTICE 'V012: removed demo entity seeds from public schema';
END $$;
