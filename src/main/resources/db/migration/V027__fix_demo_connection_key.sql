-- Fix the DB_QUERY connectionKey in demo automation workflows.
-- The initial seed used "nexus-local"; the platform convention is "local-db".
-- Replace the string value inside the graph JSONB via a text-level substitution.

UPDATE nexus_automation_workflow
   SET graph       = REPLACE(graph::text, '"nexus-local"', '"local-db"')::jsonb,
       updated_at  = NOW()
 WHERE id IN ('wf-demo-order-fulfillment', 'wf-demo-damage-claim')
   AND graph::text LIKE '%nexus-local%';
