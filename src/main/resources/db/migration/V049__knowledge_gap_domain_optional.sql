-- V049: A knowledge gap does not always belong to a domain yet.
--
-- User knowledge proposals (/knowledge), source requests and thumbs-down feedback are recorded
-- before anyone has decided which domain they concern, so domain_key is null until a reviewer
-- assigns one. The NOT NULL constraint made every such save fail. The foreign key stays: when a
-- domain is set it must exist.
ALTER TABLE nexus_knowledge_gap ALTER COLUMN domain_key DROP NOT NULL;
