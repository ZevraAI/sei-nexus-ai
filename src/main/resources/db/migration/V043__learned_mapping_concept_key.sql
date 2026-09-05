-- Adds an explicit, admin-assigned concept_key to nexus_learned_mapping.
--
-- A promoted learning is NOT automatically eligible for projection into the
-- tenant's OpenAI Vector Store just because promoted = TRUE. Projection is
-- keyed off a concept (see ConceptKnowledgeMaterializationService), and a
-- learning's business_term/sql_pattern has no reliable, safe way to be
-- mapped onto a concept_key automatically — inferring it from SQL, domain,
-- or table names would silently attach team vocabulary to the wrong concept.
-- So concept_key starts NULL for every existing and future row, and is set
-- exactly one way: an admin explicitly assigning it via
-- LearnedMappingRepository#assignConceptKey (SemanticController's
-- /semantic/learnings/{mappingKey}/concept and /promote endpoints).
--
-- The one pre-existing promoted mapping ("open" -> PO status) predates this
-- column entirely. It remains promoted = TRUE in Postgres (nothing here
-- changes that), but concept_key is NULL until an admin backfills it — until
-- then, findPromotedByConceptKey() simply never returns it, so it is not
-- projected into any concept's Vector Store document.

ALTER TABLE nexus_learned_mapping ADD COLUMN IF NOT EXISTS concept_key VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_nexus_learned_mapping_concept_key
    ON nexus_learned_mapping(concept_key) WHERE concept_key IS NOT NULL;
