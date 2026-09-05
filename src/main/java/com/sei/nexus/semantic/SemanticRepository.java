package com.sei.nexus.semantic;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class SemanticRepository {

    // Foundation Fix #2: primary_object_key uses COALESCE on conflict, not a
    // bare EXCLUDED overwrite. A partial update (e.g. the Semantic Layer
    // entity-edit form, which has no primary_object_key field) omits the key
    // from its request body, so SemanticService.createOrUpdateEntity passes
    // null through here. Without the COALESCE, that null would silently wipe
    // out a previously-correct binding on every such save — this is the exact
    // corruption traced on the "region" entity in a real tenant. There is no
    // code path anywhere that relies on omitted-primaryObjectKey meaning
    // "explicitly unbind" (verified by search before this change), so
    // preserving the existing value on omission is safe.
    private static final String UPSERT_ENTITY = """
            INSERT INTO nexus_business_entity
                (entity_key, domain_key, entity_name, description, primary_object_key,
                 operational_meaning, investigation_hints, status, created_by, created_at, updated_at,
                 entity_type, group_label, pack_key, concept_key)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (entity_key) DO UPDATE SET
                domain_key           = EXCLUDED.domain_key,
                entity_name          = EXCLUDED.entity_name,
                description          = EXCLUDED.description,
                primary_object_key   = COALESCE(EXCLUDED.primary_object_key, nexus_business_entity.primary_object_key),
                operational_meaning  = EXCLUDED.operational_meaning,
                investigation_hints  = EXCLUDED.investigation_hints,
                status               = EXCLUDED.status,
                updated_at           = NOW(),
                entity_type          = EXCLUDED.entity_type,
                -- Grouping Foundation Fix: same COALESCE discipline as primary_object_key
                -- above (Foundation Fix #2) — a partial update that omits the analyzed
                -- category (e.g. a manual Semantic Layer edit with no group field) must
                -- never silently erase a group_label an onboarding analysis already set.
                group_label          = COALESCE(EXCLUDED.group_label, nexus_business_entity.group_label),
                -- Global Pack Foundation: identical COALESCE discipline — nothing in this
                -- codebase populates these yet (no automatic mapping in this task), but an
                -- omitted value must never erase a reference a future mapping step set.
                pack_key             = COALESCE(EXCLUDED.pack_key, nexus_business_entity.pack_key),
                concept_key          = COALESCE(EXCLUDED.concept_key, nexus_business_entity.concept_key)
            """;

    private static final String FIND_ENTITY_BY_KEY = """
            SELECT entity_key, domain_key, entity_name, description, primary_object_key,
                   operational_meaning, investigation_hints, status, created_by, created_at, updated_at,
                   entity_type, group_label, pack_key, concept_key
              FROM nexus_business_entity
             WHERE entity_key = ?
            """;

    private static final String FIND_ENTITIES_BY_DOMAIN = """
            SELECT entity_key, domain_key, entity_name, description, primary_object_key,
                   operational_meaning, investigation_hints, status, created_by, created_at, updated_at,
                   entity_type, group_label, pack_key, concept_key
              FROM nexus_business_entity
             WHERE domain_key = ? AND status != 'ARCHIVED'
             ORDER BY entity_name
            """;

    // PRO-22 tier 0 — the deterministic binding lookup. When duplicates share a
    // binding (pre-existing data), the OLDEST row wins: it is the original,
    // curated concept; later rows are the drift duplicates.
    private static final String FIND_ACTIVE_BY_PRIMARY_OBJECT = """
            SELECT entity_key, domain_key, entity_name, description, primary_object_key,
                   operational_meaning, investigation_hints, status, created_by, created_at, updated_at,
                   entity_type, group_label, pack_key, concept_key
              FROM nexus_business_entity
             WHERE primary_object_key = ? AND status = 'ACTIVE'
             ORDER BY created_at ASC
             LIMIT 1
            """;

    // PRO-22 tier 1 — bounded candidate retrieval. Matching is SQL-side against
    // normalized tokens (never load-all-then-filter): entity name equality,
    // separator-free entity-key equality, or ACTIVE vocabulary term equality.
    private static final String FIND_CANDIDATE_ENTITIES = """
            SELECT DISTINCT be.entity_key, be.entity_name, be.description,
                   be.primary_object_key, o.table_name AS bound_table
              FROM nexus_business_entity be
              LEFT JOIN nexus_operational_vocabulary v
                     ON v.entity_key = be.entity_key AND v.status = 'ACTIVE'
              LEFT JOIN nexus_data_object o
                     ON o.object_key = be.primary_object_key
             WHERE be.domain_key = ? AND be.status = 'ACTIVE'
               AND (lower(be.entity_name)            = ANY(?::text[])
                    OR replace(be.entity_key, '-', '') = ANY(?::text[])
                    OR lower(v.term)                  = ANY(?::text[]))
             ORDER BY be.entity_name
             LIMIT ?
            """;

    private static final String ARCHIVE_ENTITY = """
            UPDATE nexus_business_entity SET status = 'ARCHIVED', updated_at = NOW()
             WHERE entity_key = ?
            """;

    // Fix Remove Pack State + Pack Vocabulary Duplication: clears ONLY the Pack-specific
    // semantic association (pack_key, concept_key) — every other column, including status,
    // is untouched, so the Business Entity itself is never archived/deleted by Remove Pack.
    // Connection-scoped via the existing primary_object_key -> nexus_data_object.connection_key
    // relationship (no new connection-mapping column) — combined with the pack_key match, this
    // can only ever touch entities that are BOTH bound to this connection's own physical objects
    // AND currently associated with the pack being removed, never a sibling connection's rows.
    private static final String CLEAR_PACK_ASSOCIATION_FOR_CONNECTION = """
            UPDATE nexus_business_entity
               SET pack_key = NULL, concept_key = NULL, updated_at = NOW()
             WHERE pack_key = ?
               AND primary_object_key IN (
                   SELECT object_key FROM nexus_data_object WHERE connection_key = ?
               )
            """;

    // Make Apply Pack Perform LLM Concept Classification: status-only-style update touching
    // ONLY concept_key — never entity_name/description/etc. (unlike UPSERT_ENTITY, this never
    // risks clobbering unrelated fields with a partial body). Deliberately allows writing NULL:
    // an object the LLM genuinely could not resolve against this pack must be able to clear a
    // stale concept_key from a previous classification pass, not silently keep it.
    private static final String SET_CONCEPT_KEY = """
            UPDATE nexus_business_entity SET concept_key = ?, updated_at = NOW()
             WHERE entity_key = ?
            """;

    // Fix Apply Pack Association Regression: the counterpart of
    // CLEAR_PACK_ASSOCIATION_FOR_CONNECTION above — sets ONLY pack_key on every EXISTING Business
    // Entity bound to this connection's physical objects. Never touches concept_key (that remains
    // exclusively the LLM's, via BusinessObjectBatchAnalyzer/MetadataRegistrationService), never
    // creates a row (a plain UPDATE cannot match a nonexistent one), and never touches entity_key,
    // primary_object_key, status, or any other business metadata.
    private static final String ASSOCIATE_PACK_KEY_FOR_CONNECTION = """
            UPDATE nexus_business_entity
               SET pack_key = ?, updated_at = NOW()
             WHERE primary_object_key IN (
                   SELECT object_key FROM nexus_data_object WHERE connection_key = ?
               )
            """;

    private static final String FIND_TERM_BY_KEY = """
            SELECT term_key, domain_key, entity_key, term, definition, sql_equivalent,
                   examples, status, created_at, updated_at
              FROM nexus_operational_vocabulary
             WHERE term_key = ?
            """;

    // Industry Pack Removal Lifecycle: status-only, exactly like ARCHIVE_ENTITY above — touches
    // no other column, so it can never corrupt a term's domain_key/definition/etc. the way a
    // full UPSERT_TERM re-submission would if the caller didn't have every original field on
    // hand (Remove Pack does not).
    private static final String DEACTIVATE_TERM = """
            UPDATE nexus_operational_vocabulary SET status = 'INACTIVE', updated_at = NOW()
             WHERE term_key = ?
            """;

    private static final String INSERT_LIFECYCLE_STATE = """
            INSERT INTO nexus_entity_lifecycle_state
                (state_key, entity_key, state_name, state_code, meaning,
                 is_terminal, is_exception, normal_sequence, next_states, detection_rule, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (state_key) DO UPDATE SET
                state_name       = EXCLUDED.state_name,
                state_code       = EXCLUDED.state_code,
                meaning          = EXCLUDED.meaning,
                is_terminal      = EXCLUDED.is_terminal,
                is_exception     = EXCLUDED.is_exception,
                normal_sequence  = EXCLUDED.normal_sequence,
                next_states      = EXCLUDED.next_states,
                detection_rule   = EXCLUDED.detection_rule
            """;

    private static final String FIND_LIFECYCLE_BY_ENTITY = """
            SELECT state_key, entity_key, state_name, state_code, meaning,
                   is_terminal, is_exception, normal_sequence, next_states, detection_rule, created_at
              FROM nexus_entity_lifecycle_state
             WHERE entity_key = ?
             ORDER BY COALESCE(normal_sequence, 9999), state_name
            """;

    private static final String INSERT_RELATIONSHIP = """
            INSERT INTO nexus_entity_relationship
                (relationship_key, source_entity_key, target_entity_key, relationship_type,
                 source_column, target_column, join_guidance, cross_system, identity_resolution, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (relationship_key) DO UPDATE SET
                source_entity_key  = EXCLUDED.source_entity_key,
                target_entity_key  = EXCLUDED.target_entity_key,
                relationship_type  = EXCLUDED.relationship_type,
                source_column      = EXCLUDED.source_column,
                target_column      = EXCLUDED.target_column,
                join_guidance      = EXCLUDED.join_guidance,
                cross_system       = EXCLUDED.cross_system,
                identity_resolution = EXCLUDED.identity_resolution
            """;

    private static final String FIND_RELATIONSHIPS_BY_ENTITY = """
            SELECT relationship_key, source_entity_key, target_entity_key, relationship_type,
                   source_column, target_column, join_guidance, cross_system, identity_resolution, created_at
              FROM nexus_entity_relationship
             WHERE source_entity_key = ? OR target_entity_key = ?
             ORDER BY created_at
            """;

    private static final String UPSERT_TERM = """
            INSERT INTO nexus_operational_vocabulary
                (term_key, domain_key, entity_key, term, definition, sql_equivalent,
                 examples, status, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (term_key) DO UPDATE SET
                domain_key    = EXCLUDED.domain_key,
                entity_key    = EXCLUDED.entity_key,
                term          = EXCLUDED.term,
                definition    = EXCLUDED.definition,
                sql_equivalent = EXCLUDED.sql_equivalent,
                examples      = EXCLUDED.examples,
                status        = EXCLUDED.status,
                updated_at    = NOW()
            """;

    private static final String FIND_TERMS_BY_DOMAIN = """
            SELECT term_key, domain_key, entity_key, term, definition, sql_equivalent,
                   examples, status, created_at, updated_at
              FROM nexus_operational_vocabulary
             WHERE domain_key = ? AND status = 'ACTIVE'
             ORDER BY term
            """;

    private static final String FIND_TERMS_BY_ENTITY = """
            SELECT term_key, domain_key, entity_key, term, definition, sql_equivalent,
                   examples, status, created_at, updated_at
              FROM nexus_operational_vocabulary
             WHERE entity_key = ? AND status = 'ACTIVE'
             ORDER BY term
            """;

    private static final String INSERT_MAPPING = """
            INSERT INTO nexus_entity_data_mapping
                (mapping_key, entity_key, object_key, field_mappings, identity_columns, is_primary, created_at)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT (mapping_key) DO UPDATE SET
                entity_key      = EXCLUDED.entity_key,
                object_key      = EXCLUDED.object_key,
                field_mappings  = EXCLUDED.field_mappings,
                identity_columns = EXCLUDED.identity_columns,
                is_primary      = EXCLUDED.is_primary
            """;

    private static final String FIND_MAPPINGS_BY_ENTITY = """
            SELECT mapping_key, entity_key, object_key, field_mappings, identity_columns, is_primary, created_at
              FROM nexus_entity_data_mapping
             WHERE entity_key = ?
             ORDER BY is_primary DESC, created_at
            """;

    private final JdbcTemplate jdbc;

    public SemanticRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // BusinessEntity
    // -------------------------------------------------------------------------

    public void saveEntity(BusinessEntity e) {
        jdbc.update(UPSERT_ENTITY,
            e.entityKey(), e.domainKey(), e.entityName(), e.description(), e.primaryObjectKey(),
            e.operationalMeaning(), e.investigationHints(), e.status(), e.createdBy(),
            toTimestamp(e.createdAt() != null ? e.createdAt() : Instant.now()),
            toTimestamp(e.updatedAt() != null ? e.updatedAt() : Instant.now()),
            e.entityType(), e.groupLabel(), e.packKey(), e.conceptKey());
    }

    public Optional<BusinessEntity> findEntityByKey(String key) {
        List<BusinessEntity> rows = jdbc.query(FIND_ENTITY_BY_KEY, entityMapper(), key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<BusinessEntity> findEntitiesByDomain(String domainKey) {
        return jdbc.query(FIND_ENTITIES_BY_DOMAIN, entityMapper(), domainKey);
    }

    /** PRO-22 tier 0: the ACTIVE entity already bound to this data object, oldest first. */
    public Optional<BusinessEntity> findActiveByPrimaryObjectKey(String objectKey) {
        List<BusinessEntity> rows = jdbc.query(FIND_ACTIVE_BY_PRIMARY_OBJECT, entityMapper(), objectKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Make Apply Pack Perform LLM Concept Classification: persists the LLM's own validated
     * {@code conceptResolution} decision (from {@code BusinessObjectBatchAnalyzer}) onto an
     * EXISTING entity — {@code conceptKey} may be {@code null} (the LLM found no confident
     * match). Never touches any other column.
     */
    public void setConceptKey(String entityKey, String conceptKey) {
        jdbc.update(SET_CONCEPT_KEY, conceptKey, entityKey);
    }

    /** A tier-1 selection candidate: identity + name + physical grounding, nothing more. */
    public record EntityCandidate(String entityKey, String entityName, String description,
                                  String primaryObjectKey, String boundTable) {}

    /** PRO-22 tier 1: bounded, SQL-side candidate retrieval by normalized match tokens. */
    public List<EntityCandidate> findCandidateEntities(String domainKey, List<String> tokens, int limit) {
        if (tokens == null || tokens.isEmpty()) return List.of();
        String[] tokenArray = tokens.toArray(new String[0]);
        return jdbc.query(FIND_CANDIDATE_ENTITIES,
                ps -> {
                    java.sql.Array arr = ps.getConnection().createArrayOf("text", tokenArray);
                    ps.setString(1, domainKey);
                    ps.setArray(2, arr);
                    ps.setArray(3, arr);
                    ps.setArray(4, arr);
                    ps.setInt(5, limit);
                },
                (rs, rowNum) -> new EntityCandidate(
                        rs.getString("entity_key"),
                        rs.getString("entity_name"),
                        rs.getString("description"),
                        rs.getString("primary_object_key"),
                        rs.getString("bound_table")));
    }

    public void archiveEntity(String entityKey) {
        jdbc.update(ARCHIVE_ENTITY, entityKey);
    }

    /**
     * Fix Remove Pack State + Pack Vocabulary Duplication: clears {@code pack_key}/{@code
     * concept_key} — nothing else — on every Business Entity that is (a) currently associated
     * with {@code packKey} AND (b) bound (via {@code primary_object_key}) to a physical object
     * belonging to {@code connectionKey}. Returns the number of rows affected. The entity row
     * itself, its status, and every other field are untouched — this is not an archive/delete.
     */
    public int clearPackAssociationForConnection(String packKey, String connectionKey) {
        return jdbc.update(CLEAR_PACK_ASSOCIATION_FOR_CONNECTION, packKey, connectionKey);
    }

    /**
     * Fix Apply Pack Association Regression: stamps {@code pack_key} — and only {@code
     * pack_key} — onto every EXISTING Business Entity bound to {@code connectionKey}'s physical
     * objects. Returns the number of rows affected. Creates nothing; a table with no Business
     * Entity yet is simply not matched by the WHERE clause and is left for Discover/Onboarding
     * to register later, at which point {@code MetadataRegistrationService} picks up the same
     * active pack independently.
     */
    public int associatePackKeyForConnection(String packKey, String connectionKey) {
        return jdbc.update(ASSOCIATE_PACK_KEY_FOR_CONNECTION, packKey, connectionKey);
    }

    // Concept-Scoped Metadata Narrowing (upstream Agent Brain context reduction): the Stage 1
    // "tenant concept catalog" candidate set — every DISTINCT, non-null concept_key already
    // assigned (by the LLM, via Apply Pack classification or Discover/Onboarding — never by
    // this query) to an ACTIVE Business Entity bound to this connection's physical objects.
    // Reuses the exact same primary_object_key -> nexus_data_object.connection_key join already
    // established by CLEAR_PACK_ASSOCIATION_FOR_CONNECTION/ASSOCIATE_PACK_KEY_FOR_CONNECTION —
    // no new relationship, no new column. concept_key IS NOT NULL excludes entities the LLM has
    // never classified (or classified as "no confident match") — they are simply not yet part of
    // any tenant concept catalog, never guessed at here.
    private static final String FIND_DISTINCT_CONCEPT_KEYS_FOR_CONNECTION = """
            SELECT DISTINCT concept_key
              FROM nexus_business_entity
             WHERE status = 'ACTIVE'
               AND concept_key IS NOT NULL
               AND primary_object_key IN (
                   SELECT object_key FROM nexus_data_object WHERE connection_key = ?
               )
             ORDER BY concept_key
            """;

    public List<String> findDistinctConceptKeysForConnection(String connectionKey) {
        return jdbc.queryForList(FIND_DISTINCT_CONCEPT_KEYS_FOR_CONNECTION, String.class, connectionKey);
    }

    // Concept-Scoped Metadata Narrowing, Stage 2: the physical Business Entities bound to this
    // connection whose concept_key is one of the LLM's already-validated Stage 1 selections (see
    // BusinessObjectBatchAnalyzer#applyConceptResolution for the identical acceptance-boundary
    // discipline this selection was validated under before ever reaching this query). Purely a
    // retrieval — this method makes no relevance/ranking decision; it returns EVERY matching
    // entity (a concept may legitimately bind to more than one physical object, e.g. two separate
    // "sales-transaction" tables), never picks one arbitrarily.
    public List<BusinessEntity> findEntitiesByConnectionAndConcepts(String connectionKey, List<String> conceptKeys) {
        if (conceptKeys == null || conceptKeys.isEmpty()) return List.of();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < conceptKeys.size(); i++) placeholders.append(i == 0 ? "?" : ", ?");
        String sql = "SELECT entity_key, domain_key, entity_name, description, primary_object_key, "
                + "operational_meaning, investigation_hints, status, created_by, created_at, updated_at, "
                + "entity_type, group_label, pack_key, concept_key "
                + "FROM nexus_business_entity "
                + "WHERE status = 'ACTIVE' AND concept_key IN (" + placeholders + ") "
                + "AND primary_object_key IN (SELECT object_key FROM nexus_data_object WHERE connection_key = ?) "
                + "ORDER BY entity_name";
        List<Object> params = new ArrayList<>(conceptKeys);
        params.add(connectionKey);
        return jdbc.query(sql, entityMapper(), params.toArray());
    }

    // AI Knowledge Vector Store sync watermark: the cheap, Postgres-only half of "what changed
    // since the last successful synchronization" (see ConceptKnowledgeSynchronizationService /
    // ConceptKnowledgeMaterializationService#findChangedConceptEntities). Deliberately the same
    // ACTIVE + concept_key IS NOT NULL + connection-scoping shape as
    // findDistinctConceptKeysForConnection/findEntitiesByConnectionAndConcepts above (the same
    // authoritative catalog those already narrow to) — this just adds a timestamp filter instead
    // of a concept-key filter. No OpenAI/Vector Store call involved.
    private static final String FIND_ENTITIES_CHANGED_AFTER_FOR_CONNECTION = """
            SELECT entity_key, domain_key, entity_name, description, primary_object_key,
                   operational_meaning, investigation_hints, status, created_by, created_at, updated_at,
                   entity_type, group_label, pack_key, concept_key
              FROM nexus_business_entity
             WHERE status = 'ACTIVE'
               AND concept_key IS NOT NULL
               AND primary_object_key IN (
                   SELECT object_key FROM nexus_data_object WHERE connection_key = ?
               )
               AND (created_at > ? OR updated_at > ?)
             ORDER BY updated_at DESC
            """;

    public List<BusinessEntity> findEntitiesChangedAfterForConnection(String connectionKey, Instant since) {
        Timestamp watermark = toTimestamp(since);
        return jdbc.query(FIND_ENTITIES_CHANGED_AFTER_FOR_CONNECTION, entityMapper(),
                connectionKey, watermark, watermark);
    }

    // Downstream Context Boundary for Concept-Scoped Metadata Narrowing: retrieves the ACTIVE
    // Business Entities bound to an already Stage-2-resolved set of physical object keys —
    // purely a retrieval keyed on primary_object_key membership, no domain_key, no ranking, no
    // interpretation of the question. Callers use this ONLY when AgentBrain's own Stage 1/2
    // concept-scoped resolution actually produced the object keys being passed in (see
    // ResolvedBusinessModel#conceptScoped()) — this method makes no such determination itself.
    public List<BusinessEntity> findEntitiesByObjectKeys(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) return List.of();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < objectKeys.size(); i++) placeholders.append(i == 0 ? "?" : ", ?");
        String sql = "SELECT entity_key, domain_key, entity_name, description, primary_object_key, "
                + "operational_meaning, investigation_hints, status, created_by, created_at, updated_at, "
                + "entity_type, group_label, pack_key, concept_key "
                + "FROM nexus_business_entity "
                + "WHERE status = 'ACTIVE' AND primary_object_key IN (" + placeholders + ") "
                + "ORDER BY entity_name";
        return jdbc.query(sql, entityMapper(), objectKeys.toArray());
    }

    // -------------------------------------------------------------------------
    // EntityLifecycleState
    // -------------------------------------------------------------------------

    public void saveLifecycleState(EntityLifecycleState s) {
        jdbc.update(INSERT_LIFECYCLE_STATE,
            s.stateKey(), s.entityKey(), s.stateName(), s.stateCode(), s.meaning(),
            s.isTerminal(), s.isException(), s.normalSequence(), s.nextStates(), s.detectionRule(),
            toTimestamp(s.createdAt() != null ? s.createdAt() : Instant.now()));
    }

    public List<EntityLifecycleState> findLifecycleByEntity(String entityKey) {
        return jdbc.query(FIND_LIFECYCLE_BY_ENTITY, lifecycleMapper(), entityKey);
    }

    // -------------------------------------------------------------------------
    // EntityRelationship
    // -------------------------------------------------------------------------

    public void saveRelationship(EntityRelationship r) {
        jdbc.update(INSERT_RELATIONSHIP,
            r.relationshipKey(), r.sourceEntityKey(), r.targetEntityKey(), r.relationshipType(),
            r.sourceColumn(), r.targetColumn(), r.joinGuidance(), r.crossSystem(),
            r.identityResolution(),
            toTimestamp(r.createdAt() != null ? r.createdAt() : Instant.now()));
    }

    public List<EntityRelationship> findRelationshipsByEntity(String entityKey) {
        return jdbc.query(FIND_RELATIONSHIPS_BY_ENTITY, relationshipMapper(), entityKey, entityKey);
    }

    // -------------------------------------------------------------------------
    // OperationalVocabulary
    // -------------------------------------------------------------------------

    public void saveTerm(OperationalVocabulary t) {
        jdbc.update(UPSERT_TERM,
            t.termKey(), t.domainKey(), t.entityKey(), t.term(), t.definition(),
            t.sqlEquivalent(), t.examples(), t.status(),
            toTimestamp(t.createdAt() != null ? t.createdAt() : Instant.now()),
            toTimestamp(t.updatedAt() != null ? t.updatedAt() : Instant.now()));
    }

    public List<OperationalVocabulary> findTermsByDomain(String domainKey) {
        return jdbc.query(FIND_TERMS_BY_DOMAIN, termMapper(), domainKey);
    }

    /** Fix Remove Pack State + Pack Vocabulary Duplication: lookup used to make Apply Pack's
     *  idempotent reuse/reactivation of existing Pack vocabulary explicit and observable (rather
     *  than relying solely on the UPSERT's ON CONFLICT to be silently correct). */
    public Optional<OperationalVocabulary> findTermByKey(String termKey) {
        List<OperationalVocabulary> rows = jdbc.query(FIND_TERM_BY_KEY, termMapper(), termKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Industry Pack Removal Lifecycle: status-only update, mirroring {@link #archiveEntity}
     *  exactly — a no-op if {@code termKey} does not exist, which callers that only ever compute
     *  a deterministic key (never look the row up first) rely on. */
    public void deactivateTerm(String termKey) {
        jdbc.update(DEACTIVATE_TERM, termKey);
    }

    public List<OperationalVocabulary> findTermsByEntity(String entityKey) {
        return jdbc.query(FIND_TERMS_BY_ENTITY, termMapper(), entityKey);
    }

    // -------------------------------------------------------------------------
    // EntityDataMapping
    // -------------------------------------------------------------------------

    public void saveMapping(EntityDataMapping m) {
        jdbc.update(INSERT_MAPPING,
            m.mappingKey(), m.entityKey(), m.objectKey(), m.fieldMappings(),
            m.identityColumns(), m.isPrimary(),
            toTimestamp(m.createdAt() != null ? m.createdAt() : Instant.now()));
    }

    public List<EntityDataMapping> findMappingsByEntity(String entityKey) {
        return jdbc.query(FIND_MAPPINGS_BY_ENTITY, mappingMapper(), entityKey);
    }

    // -------------------------------------------------------------------------
    // Context builder
    // -------------------------------------------------------------------------

    public String buildEntityContext(List<String> domainKeys) {
        if (domainKeys == null || domainKeys.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();

        for (String domainKey : domainKeys) {
            List<BusinessEntity> entities = findEntitiesByDomain(domainKey);

            for (BusinessEntity entity : entities) {
                sb.append("=== Business Entity: ").append(entity.entityName())
                  .append(" (domain: ").append(domainKey).append(") ===\n");

                if (entity.description() != null && !entity.description().isBlank()) {
                    sb.append("Description: ").append(entity.description()).append("\n");
                }
                if (entity.operationalMeaning() != null && !entity.operationalMeaning().isBlank()) {
                    sb.append("Operational Meaning: ").append(entity.operationalMeaning()).append("\n");
                }
                if (entity.investigationHints() != null && !entity.investigationHints().isBlank()) {
                    sb.append("Investigation Hints: ").append(entity.investigationHints()).append("\n");
                }

                // Lifecycle states
                List<EntityLifecycleState> states = findLifecycleByEntity(entity.entityKey());
                if (!states.isEmpty()) {
                    sb.append("Lifecycle States:\n");
                    for (EntityLifecycleState state : states) {
                        sb.append("  - ").append(state.stateName())
                          .append(" (code: ").append(state.stateCode()).append("): ")
                          .append(state.meaning() != null ? state.meaning() : "");
                        List<String> flags = new ArrayList<>();
                        if (state.isTerminal()) flags.add("TERMINAL");
                        if (state.isException()) flags.add("EXCEPTION");
                        if (state.normalSequence() != null) flags.add("NORMAL");
                        if (!flags.isEmpty()) {
                            sb.append(" [").append(String.join(", ", flags)).append("]");
                        }
                        sb.append("\n");
                    }
                }

                // Relationships
                List<EntityRelationship> rels = findRelationshipsByEntity(entity.entityKey());
                if (!rels.isEmpty()) {
                    sb.append("Relationships:\n");
                    for (EntityRelationship rel : rels) {
                        sb.append("  - ").append(entity.entityName())
                          .append(" ").append(rel.relationshipType()).append(" ")
                          .append(rel.targetEntityKey());
                        if (rel.sourceColumn() != null && rel.targetColumn() != null) {
                            sb.append(" (join: ").append(rel.sourceColumn())
                              .append(" = ").append(rel.targetColumn()).append(")");
                        }
                        if (rel.crossSystem()) sb.append(" [CROSS_SYSTEM]");
                        sb.append("\n");
                    }
                }

                // Vocabulary
                List<OperationalVocabulary> terms = findTermsByEntity(entity.entityKey());
                if (!terms.isEmpty()) {
                    sb.append("Vocabulary:\n");
                    for (OperationalVocabulary term : terms) {
                        sb.append("  - \"").append(term.term()).append("\": ")
                          .append(term.definition() != null ? term.definition() : "").append("\n");
                        if (term.sqlEquivalent() != null && !term.sqlEquivalent().isBlank()) {
                            sb.append("    SQL: ").append(term.sqlEquivalent()).append("\n");
                        }
                    }
                }

                sb.append("\n");
            }
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Row mappers
    // -------------------------------------------------------------------------

    private RowMapper<BusinessEntity> entityMapper() {
        return (rs, rowNum) -> new BusinessEntity(
            rs.getString("entity_key"),
            rs.getString("domain_key"),
            rs.getString("entity_name"),
            rs.getString("description"),
            rs.getString("primary_object_key"),
            rs.getString("operational_meaning"),
            rs.getString("investigation_hints"),
            rs.getString("status"),
            rs.getString("created_by"),
            toInstant(rs, "created_at"),
            toInstant(rs, "updated_at"),
            rs.getString("entity_type"),
            rs.getString("group_label"),
            rs.getString("pack_key"),
            rs.getString("concept_key"));
    }

    private RowMapper<EntityLifecycleState> lifecycleMapper() {
        return (rs, rowNum) -> {
            Integer normalSequence = rs.getInt("normal_sequence");
            if (rs.wasNull()) normalSequence = null;
            return new EntityLifecycleState(
                rs.getString("state_key"),
                rs.getString("entity_key"),
                rs.getString("state_name"),
                rs.getString("state_code"),
                rs.getString("meaning"),
                rs.getBoolean("is_terminal"),
                rs.getBoolean("is_exception"),
                normalSequence,
                rs.getString("next_states"),
                rs.getString("detection_rule"),
                toInstant(rs, "created_at"));
        };
    }

    private RowMapper<EntityRelationship> relationshipMapper() {
        return (rs, rowNum) -> new EntityRelationship(
            rs.getString("relationship_key"),
            rs.getString("source_entity_key"),
            rs.getString("target_entity_key"),
            rs.getString("relationship_type"),
            rs.getString("source_column"),
            rs.getString("target_column"),
            rs.getString("join_guidance"),
            rs.getBoolean("cross_system"),
            rs.getString("identity_resolution"),
            toInstant(rs, "created_at"));
    }

    private RowMapper<OperationalVocabulary> termMapper() {
        return (rs, rowNum) -> new OperationalVocabulary(
            rs.getString("term_key"),
            rs.getString("domain_key"),
            rs.getString("entity_key"),
            rs.getString("term"),
            rs.getString("definition"),
            rs.getString("sql_equivalent"),
            rs.getString("examples"),
            rs.getString("status"),
            toInstant(rs, "created_at"),
            toInstant(rs, "updated_at"));
    }

    private RowMapper<EntityDataMapping> mappingMapper() {
        return (rs, rowNum) -> new EntityDataMapping(
            rs.getString("mapping_key"),
            rs.getString("entity_key"),
            rs.getString("object_key"),
            rs.getString("field_mappings"),
            rs.getString("identity_columns"),
            rs.getBoolean("is_primary"),
            toInstant(rs, "created_at"));
    }

    private Instant toInstant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }
}
