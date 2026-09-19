package com.sei.nexus.semantic;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concept-Key Semantic Anchor design — {@link LearnedMappingRepository#findPromotedByConceptKeys}.
 * Hand-rolled fake, no real database: {@link JdbcTemplate#query(String, RowMapper, Object...)} is
 * overridden to capture the exact SQL/args issued and return a scripted result, so this test
 * verifies the repository's own query-construction logic in isolation, matching this project's
 * no-Mockito/no-DB unit-test convention.
 *
 * <p>What this deliberately proves: the lookup is EXACT concept_key equality only — no LIKE, no
 * fuzzy/partial matching, no ranking beyond the pre-existing confidence/use_count ordering, and no
 * selection among results. Java never chooses one mapping over another here.
 */
class LearnedMappingRepositoryConceptKeyTest {

    static class CapturingJdbcTemplate extends JdbcTemplate {
        String lastSql;
        Object[] lastArgs;
        final List<LearnedMapping> scriptedRows = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            lastSql = sql;
            lastArgs = args;
            return (List<T>) scriptedRows;
        }
    }

    private static LearnedMapping mapping(String key, String term, String conceptKey) {
        Instant now = Instant.now();
        return new LearnedMapping(key, "domain", term, "status IN (...)", "run-1", "QUERY_SUCCESS",
                0.9, 12, now, true, now, now, conceptKey);
    }

    @Test
    void nullConceptKeysNeverQueriesTheDatabase() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        LearnedMappingRepository repo = new LearnedMappingRepository(jdbc);

        assertTrue(repo.findPromotedByConceptKeys(null).isEmpty());
        assertNull(jdbc.lastSql, "no query should ever be issued for a null concept-key set");
    }

    @Test
    void emptyConceptKeysNeverQueriesTheDatabase() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        LearnedMappingRepository repo = new LearnedMappingRepository(jdbc);

        assertTrue(repo.findPromotedByConceptKeys(List.of()).isEmpty());
        assertNull(jdbc.lastSql);
    }

    @Test
    void queriesExactConceptKeyEqualityOnly() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        jdbc.scriptedRows.add(mapping("m1", "open orders", "purchase-order"));
        LearnedMappingRepository repo = new LearnedMappingRepository(jdbc);

        List<LearnedMapping> result = repo.findPromotedByConceptKeys(List.of("purchase-order", "inventory-balance"));

        assertEquals(1, result.size());
        assertTrue(jdbc.lastSql.contains("promoted = TRUE"));
        assertTrue(jdbc.lastSql.contains("concept_key IN"));
        assertFalse(jdbc.lastSql.toUpperCase().contains("LIKE"),
                "must be exact equality, never a LIKE/fuzzy/partial match");
        assertArrayEquals(new Object[] {"purchase-order", "inventory-balance"}, jdbc.lastArgs);
    }

    @Test
    void multipleMappingsForTheSameConceptAreAllReturnedWithNoneChosenByJava() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        jdbc.scriptedRows.add(mapping("m1", "open orders", "purchase-order"));
        jdbc.scriptedRows.add(mapping("m2", "urgent orders", "purchase-order"));
        jdbc.scriptedRows.add(mapping("m3", "late orders", "purchase-order"));
        LearnedMappingRepository repo = new LearnedMappingRepository(jdbc);

        List<LearnedMapping> result = repo.findPromotedByConceptKeys(List.of("purchase-order"));

        assertEquals(3, result.size(), "every promoted mapping under the concept must be returned — "
                + "the repository never selects, ranks beyond confidence/use_count, or filters semantically");
    }

    @Test
    void differentConceptMappingsAreExcludedByTheSqlItself() {
        // This test only proves the WHERE clause targets concept_key equality — whether a mapping
        // for a different concept is actually excluded is enforced by Postgres, not Java; the
        // scripted rows here stand in for "what Postgres would have already filtered out."
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        LearnedMappingRepository repo = new LearnedMappingRepository(jdbc);

        repo.findPromotedByConceptKeys(List.of("purchase-order"));

        assertTrue(jdbc.lastSql.matches("(?s).*WHERE promoted = TRUE AND concept_key IN \\(\\?\\).*"),
                "exactly one placeholder for one requested concept key — no OR/LIKE broadening");
    }

    @Test
    void blankAndDuplicateConceptKeysAreFilteredBeforeQuerying() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        LearnedMappingRepository repo = new LearnedMappingRepository(jdbc);

        repo.findPromotedByConceptKeys(Arrays.asList("purchase-order", "", null, "purchase-order"));

        assertArrayEquals(new Object[] {"purchase-order"}, jdbc.lastArgs,
                "blank/null/duplicate entries are dropped before querying — deterministic cleanup, not semantic filtering");
    }
}
