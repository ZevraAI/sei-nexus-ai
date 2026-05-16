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

    private static final String UPSERT_ENTITY = """
            INSERT INTO nexus_business_entity
                (entity_key, domain_key, entity_name, description, primary_object_key,
                 operational_meaning, investigation_hints, status, created_by, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (entity_key) DO UPDATE SET
                domain_key           = EXCLUDED.domain_key,
                entity_name          = EXCLUDED.entity_name,
                description          = EXCLUDED.description,
                primary_object_key   = EXCLUDED.primary_object_key,
                operational_meaning  = EXCLUDED.operational_meaning,
                investigation_hints  = EXCLUDED.investigation_hints,
                status               = EXCLUDED.status,
                updated_at           = NOW()
            """;

    private static final String FIND_ENTITY_BY_KEY = """
            SELECT entity_key, domain_key, entity_name, description, primary_object_key,
                   operational_meaning, investigation_hints, status, created_by, created_at, updated_at
              FROM nexus_business_entity
             WHERE entity_key = ?
            """;

    private static final String FIND_ENTITIES_BY_DOMAIN = """
            SELECT entity_key, domain_key, entity_name, description, primary_object_key,
                   operational_meaning, investigation_hints, status, created_by, created_at, updated_at
              FROM nexus_business_entity
             WHERE domain_key = ? AND status != 'ARCHIVED'
             ORDER BY entity_name
            """;

    private static final String ARCHIVE_ENTITY = """
            UPDATE nexus_business_entity SET status = 'ARCHIVED', updated_at = NOW()
             WHERE entity_key = ?
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
            toTimestamp(e.updatedAt() != null ? e.updatedAt() : Instant.now()));
    }

    public Optional<BusinessEntity> findEntityByKey(String key) {
        List<BusinessEntity> rows = jdbc.query(FIND_ENTITY_BY_KEY, entityMapper(), key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<BusinessEntity> findEntitiesByDomain(String domainKey) {
        return jdbc.query(FIND_ENTITIES_BY_DOMAIN, entityMapper(), domainKey);
    }

    public void archiveEntity(String entityKey) {
        jdbc.update(ARCHIVE_ENTITY, entityKey);
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
            toInstant(rs, "updated_at"));
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
