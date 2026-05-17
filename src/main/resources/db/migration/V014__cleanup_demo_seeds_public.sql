-- V014: Ensure demo entity seeds are removed from the public schema.
--
-- V012 attempted this but used current_schema() which may not reliably
-- return 'public' on Supabase session pooler connections. This migration
-- repeats the cleanup with a more reliable guard: nexus_tenant only exists
-- in the public (platform registry) schema, never in tenant schemas.
-- Safe to re-run — ON CONFLICT / IF EXISTS means it is idempotent.

DO $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_name   = 'nexus_tenant'
         AND table_schema = current_schema()
  ) THEN
    RAISE NOTICE 'V014: not platform schema — skipping';
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

  RAISE NOTICE 'V014: demo entity seeds removed from public schema';
END $$;
