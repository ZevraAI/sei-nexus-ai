-- V012: Remove V007 demo entity seeds from the public (default workspace) schema.
--
-- V007 seeds logistics demo entities into every schema. In tenant schemas this
-- is useful — it gives a working example knowledge graph. But in the public
-- schema (platform admin workspace) there is no database connection, so these
-- entities mislead users: Knowledge Graph and Semantic Layer show rich data
-- while Chat correctly says "no approved data sources".
--
-- Guard: nexus_tenant only exists in the public (platform) schema.
-- Tenant schemas (tenant_xyz etc.) do not have it, so this is a safe no-op
-- when Flyway runs this migration against a tenant schema during provisioning.

DO $$
BEGIN
  -- nexus_tenant is the platform-level tenant registry — only present in public.
  -- If it is not in the current schema we are in a tenant schema: skip.
  IF NOT EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_name   = 'nexus_tenant'
         AND table_schema = current_schema()
  ) THEN
    RAISE NOTICE 'V012: nexus_tenant not found in current schema — skipping (tenant schema)';
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
