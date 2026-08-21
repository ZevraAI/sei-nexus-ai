package com.sei.nexus.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The additive {@link ExecutionReferenceRepository#findByExecutionId(String)} lookup
 * (Conversation Memory foundation, Phase 1). Does not change {@code save()} or
 * {@code findLatestByConversation()} — see {@code AgentToolRegistryTest} for the existing
 * pattern of faking this repository over a {@code null} JdbcTemplate.
 *
 * <p>As with every other query method on this repository, exact-lookup SQL correctness and
 * tenant-schema isolation are not verifiable without a live database under this repo's
 * no-DB test convention (no repository in this codebase carries a direct SQL-level test).
 * This test covers the null-guard logic that runs before any JDBC call.
 */
class ExecutionReferenceRepositoryFindByExecutionIdTest {

    private final ExecutionReferenceRepository repository =
            new ExecutionReferenceRepository(null, new ObjectMapper());

    @Test
    void blankExecutionIdReturnsEmptyWithoutTouchingJdbc() {
        assertTrue(repository.findByExecutionId(null).isEmpty());
        assertTrue(repository.findByExecutionId("").isEmpty());
        assertTrue(repository.findByExecutionId("   ").isEmpty());
    }
}
