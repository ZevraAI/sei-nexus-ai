package com.sei.nexus.semantic;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Foundation Fix #2 — regression guard for the UPSERT_ENTITY SQL text itself.
 *
 * <p>The bug this protects against: {@code ON CONFLICT (entity_key) DO UPDATE SET
 * primary_object_key = EXCLUDED.primary_object_key} unconditionally overwrites an
 * existing binding with NULL whenever a caller's request body omits
 * {@code primaryObjectKey} — exactly what the Semantic Layer entity-edit UI does on
 * every save, since its form has no field for it. Traced in production on the
 * "region" entity in a real tenant: a valid {@code primary_object_key} was silently
 * nulled with no other field change.
 *
 * <p>This test asserts the SQL constant itself preserves the existing value on
 * omission via {@code COALESCE(EXCLUDED.primary_object_key, nexus_business_entity.primary_object_key)}.
 * It runs in every {@code mvn test} with no database — it cannot verify Postgres's
 * actual COALESCE evaluation at runtime (see {@link SemanticRepositoryUpsertPreservationProbe}
 * for that), but it prevents someone from reverting the fix back to a bare overwrite
 * without a test failing.
 */
class SemanticRepositoryUpsertEntityTest {

    private static String upsertEntitySql() throws Exception {
        Field field = SemanticRepository.class.getDeclaredField("UPSERT_ENTITY");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @Test
    void primaryObjectKeyIsPreservedOnConflictViaCoalesce() throws Exception {
        String sql = upsertEntitySql();
        assertTrue(
                sql.contains("primary_object_key   = COALESCE(EXCLUDED.primary_object_key, nexus_business_entity.primary_object_key)"),
                "UPSERT_ENTITY must COALESCE primary_object_key against the existing row on conflict, "
                        + "not overwrite it with EXCLUDED.primary_object_key unconditionally — "
                        + "a bare overwrite silently nulls a valid binding whenever a partial update "
                        + "(e.g. the Semantic Layer entity-edit form) omits primaryObjectKey. Actual SQL:\n" + sql);
    }

    @Test
    void otherEditableFieldsStillOverwriteUnconditionally() throws Exception {
        // Only primary_object_key and group_label get the preservation guard. Every
        // other editable field must still take EXCLUDED's value outright — this fix
        // is scoped to fields with a documented reason to preserve on omission, not a
        // blanket "preserve everything" change.
        String sql = upsertEntitySql();
        assertTrue(sql.contains("domain_key           = EXCLUDED.domain_key"));
        assertTrue(sql.contains("entity_name          = EXCLUDED.entity_name"));
        assertTrue(sql.contains("description          = EXCLUDED.description"));
        assertTrue(sql.contains("operational_meaning  = EXCLUDED.operational_meaning"));
        assertTrue(sql.contains("investigation_hints  = EXCLUDED.investigation_hints"));
        assertTrue(sql.contains("status               = EXCLUDED.status"));
        assertTrue(sql.contains("entity_type          = EXCLUDED.entity_type"));
    }

    /**
     * Grouping Foundation Fix — the same COALESCE discipline as primary_object_key,
     * for the same reason: a partial update that omits the analyzed category (e.g. a
     * re-registration pass, or a manual Semantic Layer edit that only touches other
     * fields) must not silently erase a group_label an onboarding analysis already set.
     */
    @Test
    void groupLabelIsPreservedOnConflictViaCoalesce() throws Exception {
        String sql = upsertEntitySql();
        assertTrue(
                sql.contains("group_label          = COALESCE(EXCLUDED.group_label, nexus_business_entity.group_label)"),
                "UPSERT_ENTITY must COALESCE group_label against the existing row on conflict, "
                        + "not overwrite it with EXCLUDED.group_label unconditionally. Actual SQL:\n" + sql);
    }

    /**
     * Global Pack Foundation — same COALESCE discipline as primary_object_key/
     * group_label, for the new pack_key/concept_key reference to a canonical Global
     * Business Concept: no current caller populates these (this task adds only the
     * plumbing), but the same "an omission must never erase" rule applies once a
     * future mapping step starts setting them.
     */
    @Test
    void packKeyAndConceptKeyArePreservedOnConflictViaCoalesce() throws Exception {
        String sql = upsertEntitySql();
        assertTrue(
                sql.contains("pack_key             = COALESCE(EXCLUDED.pack_key, nexus_business_entity.pack_key)"),
                "UPSERT_ENTITY must COALESCE pack_key against the existing row on conflict. Actual SQL:\n" + sql);
        assertTrue(
                sql.contains("concept_key          = COALESCE(EXCLUDED.concept_key, nexus_business_entity.concept_key)"),
                "UPSERT_ENTITY must COALESCE concept_key against the existing row on conflict. Actual SQL:\n" + sql);
    }
}
