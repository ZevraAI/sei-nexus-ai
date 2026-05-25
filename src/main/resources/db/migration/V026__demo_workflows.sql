-- flyway:placeholderReplacement=false
-- Demo automation workflows seeded into the public (platform) schema.
-- These are platform-level demos visible to all tenants until tenants build their own.

INSERT INTO nexus_automation_workflow
  (id, tenant_schema, name, description, slug, status, trigger_type, graph, created_by, created_at, updated_at)
VALUES (
  'wf-demo-order-fulfillment',
  'public',
  'Order Fulfillment',
  'Looks up an incoming order, checks inventory, reasons on fulfillment readiness, and returns a structured decision with next steps.',
  'order-fulfillment',
  'ACTIVE',
  'WEBHOOK',
  '{
    "nodes": [
      {
        "id": "n1",
        "type": "TRIGGER",
        "position": {"x": 340, "y": 40},
        "data": {"label": "Order Received", "triggerType": "WEBHOOK"}
      },
      {
        "id": "n2",
        "type": "DB_QUERY",
        "position": {"x": 340, "y": 160},
        "data": {
          "label": "Fetch Order Details",
          "connectionKey": "nexus-local",
          "sql": "SELECT o.order_id, o.customer_name, o.customer_email, o.status, o.total_amount, o.shipping_address, o.created_at FROM demo_order o WHERE o.order_id = ''{{trigger.order_id}}'' LIMIT 1",
          "maxRows": 1
        }
      },
      {
        "id": "n3",
        "type": "DB_QUERY",
        "position": {"x": 340, "y": 300},
        "data": {
          "label": "Fetch Order Items + Inventory",
          "connectionKey": "nexus-local",
          "sql": "SELECT oi.product_id, p.name AS product_name, oi.quantity AS ordered_qty, inv.quantity_on_hand AS stock_qty, (inv.quantity_on_hand >= oi.quantity) AS in_stock FROM demo_order_item oi JOIN demo_product p ON p.product_id = oi.product_id JOIN demo_inventory inv ON inv.product_id = oi.product_id WHERE oi.order_id = ''{{trigger.order_id}}''",
          "maxRows": 50
        }
      },
      {
        "id": "n4",
        "type": "CONDITION",
        "position": {"x": 340, "y": 440},
        "data": {
          "label": "Order Exists?",
          "leftValue": "{{nodes.n2[0].order_id}}",
          "operator": "isNotEmpty",
          "rightValue": ""
        }
      },
      {
        "id": "n5",
        "type": "AI_REASON",
        "position": {"x": 160, "y": 590},
        "data": {
          "label": "AI Fulfillment Analysis",
          "systemPrompt": "You are a logistics operations AI. Analyse order data and inventory levels, then provide a concise fulfillment recommendation.",
          "userPrompt": "Order: {{nodes.n2[0].order_id}} for {{nodes.n2[0].customer_name}} (status: {{nodes.n2[0].status}}, total: ${{nodes.n2[0].total_amount}}). Inventory check complete. Provide a fulfillment recommendation in 2-3 sentences covering: can we fulfill, any stock issues, and recommended action.",
          "outputFormat": "text"
        }
      },
      {
        "id": "n6",
        "type": "RESPONSE",
        "position": {"x": 160, "y": 740},
        "data": {
          "label": "Fulfillment Decision",
          "fields": [
            {"key": "order_id",        "value": "{{trigger.order_id}}"},
            {"key": "customer",        "value": "{{nodes.n2[0].customer_name}}"},
            {"key": "order_status",    "value": "{{nodes.n2[0].status}}"},
            {"key": "total_amount",    "value": "{{nodes.n2[0].total_amount}}"},
            {"key": "recommendation",  "value": "{{nodes.n5}}"}
          ]
        }
      },
      {
        "id": "n7",
        "type": "RESPONSE",
        "position": {"x": 520, "y": 590},
        "data": {
          "label": "Order Not Found",
          "fields": [
            {"key": "error",    "value": "Order not found"},
            {"key": "order_id", "value": "{{trigger.order_id}}"}
          ]
        }
      }
    ],
    "edges": [
      {"id": "e1-2", "source": "n1", "target": "n2", "sourceHandle": null, "targetHandle": null},
      {"id": "e2-3", "source": "n2", "target": "n3", "sourceHandle": null, "targetHandle": null},
      {"id": "e3-4", "source": "n3", "target": "n4", "sourceHandle": null, "targetHandle": null},
      {"id": "e4-5", "source": "n4", "target": "n5", "sourceHandle": "true",  "targetHandle": null},
      {"id": "e4-7", "source": "n4", "target": "n7", "sourceHandle": "false", "targetHandle": null},
      {"id": "e5-6", "source": "n5", "target": "n6", "sourceHandle": null, "targetHandle": null}
    ]
  }'::jsonb,
  'system',
  NOW(),
  NOW()
)
ON CONFLICT (id) DO NOTHING;

-- ──────────────────────────────────────────────────────────────────────────────

INSERT INTO nexus_automation_workflow
  (id, tenant_schema, name, description, slug, status, trigger_type, graph, created_by, created_at, updated_at)
VALUES (
  'wf-demo-damage-claim',
  'public',
  'Damage Claim Processor',
  'Receives a damage claim, fetches claim details, optionally analyses an image via the vision model, scores severity with AI, and returns a structured resolution recommendation.',
  'damage-claim-processor',
  'ACTIVE',
  'WEBHOOK',
  '{
    "nodes": [
      {
        "id": "n1",
        "type": "TRIGGER",
        "position": {"x": 340, "y": 40},
        "data": {"label": "Claim Submitted", "triggerType": "WEBHOOK"}
      },
      {
        "id": "n2",
        "type": "DB_QUERY",
        "position": {"x": 340, "y": 160},
        "data": {
          "label": "Fetch Claim Record",
          "connectionKey": "nexus-local",
          "sql": "SELECT claim_id, customer_name, customer_email, description, severity, status, image_base64 FROM demo_damage_claim WHERE claim_id = ''{{trigger.claim_id}}'' LIMIT 1",
          "maxRows": 1
        }
      },
      {
        "id": "n3",
        "type": "CONDITION",
        "position": {"x": 340, "y": 300},
        "data": {
          "label": "Has Image?",
          "leftValue": "{{nodes.n2[0].image_base64}}",
          "operator": "isNotEmpty",
          "rightValue": ""
        }
      },
      {
        "id": "n4",
        "type": "IMAGE_ANALYSE",
        "position": {"x": 160, "y": 450},
        "data": {
          "label": "Analyse Damage Image",
          "question": "Assess the damage visible in this image. Describe: 1) what is damaged, 2) severity (Low/Medium/High/Critical), 3) whether the item appears repairable or needs replacement.",
          "imageRef": "{{nodes.n2[0].image_base64}}",
          "mimeType": "image/jpeg"
        }
      },
      {
        "id": "n5",
        "type": "AI_REASON",
        "position": {"x": 340, "y": 590},
        "data": {
          "label": "AI Severity & Recommendation",
          "systemPrompt": "You are a claims processing AI. Analyse damage claim data and produce a structured JSON assessment.",
          "userPrompt": "Claim {{nodes.n2[0].claim_id}} from {{nodes.n2[0].customer_name}}. Description: {{nodes.n2[0].description}}. Image analysis: {{nodes.n4}}. Return JSON with fields: severity (LOW/MEDIUM/HIGH/CRITICAL), recommended_action (REPLACE/REFUND/REPAIR/REJECT/REVIEW), confidence (0-100), reasoning (string).",
          "outputFormat": "json"
        }
      },
      {
        "id": "n6",
        "type": "RESPONSE",
        "position": {"x": 340, "y": 740},
        "data": {
          "label": "Claim Assessment",
          "fields": [
            {"key": "claim_id",            "value": "{{trigger.claim_id}}"},
            {"key": "customer",            "value": "{{nodes.n2[0].customer_name}}"},
            {"key": "severity",            "value": "{{nodes.n5.severity}}"},
            {"key": "recommended_action",  "value": "{{nodes.n5.recommended_action}}"},
            {"key": "confidence",          "value": "{{nodes.n5.confidence}}"},
            {"key": "reasoning",           "value": "{{nodes.n5.reasoning}}"},
            {"key": "image_analysis",      "value": "{{nodes.n4}}"}
          ]
        }
      }
    ],
    "edges": [
      {"id": "e1-2", "source": "n1", "target": "n2", "sourceHandle": null, "targetHandle": null},
      {"id": "e2-3", "source": "n2", "target": "n3", "sourceHandle": null, "targetHandle": null},
      {"id": "e3-4", "source": "n3", "target": "n4", "sourceHandle": "true",  "targetHandle": null},
      {"id": "e3-5", "source": "n3", "target": "n5", "sourceHandle": "false", "targetHandle": null},
      {"id": "e4-5", "source": "n4", "target": "n5", "sourceHandle": null, "targetHandle": null},
      {"id": "e5-6", "source": "n5", "target": "n6", "sourceHandle": null, "targetHandle": null}
    ]
  }'::jsonb,
  'system',
  NOW(),
  NOW()
)
ON CONFLICT (id) DO NOTHING;
