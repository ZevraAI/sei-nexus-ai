package com.sei.nexus.agentmemory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConversationRosterRepository} wraps {@code JdbcTemplate} directly, exactly like
 * every other repository in this codebase (see {@code ExecutionReferenceRepository},
 * {@code EnterpriseMapRepository}, {@code KnowledgeGraphRepository}) — none of which carry
 * a direct unit test, since actual SQL correctness (idempotent upsert, unique constraint,
 * tenant-schema routing) is not verifiable without a live database under this repo's
 * no-DB test convention. This test therefore covers only the null-guard logic that runs
 * BEFORE any JDBC call — constructed with a {@code null} JdbcTemplate to prove those
 * branches never reach the database.
 *
 * <p>Idempotent-upsert behaviour, unique-constraint enforcement, and tenant isolation for
 * this table are exercised indirectly via {@link ConversationMemoryServiceTest}'s
 * {@code FakeRosterRepository} (service-level contract) and are otherwise deferred to the
 * live-tenant verification step called for in the design review, consistent with how
 * {@code findLatestByConversation} and every sibling repository method in this codebase
 * has always been verified.
 */
class ConversationRosterRepositoryTest {

    private final ConversationRosterRepository repository = new ConversationRosterRepository(null);

    @Test
    void ensureWithBlankConversationIdIsANoOpAndNeverTouchesJdbc() {
        assertDoesNotThrow(() -> repository.ensure(" ", "supplier", "Supplier", "entity"));
    }

    @Test
    void ensureWithBlankEntityKeyIsANoOpAndNeverTouchesJdbc() {
        assertDoesNotThrow(() -> repository.ensure("conv-1", "", "Supplier", "entity"));
    }

    @Test
    void findByConversationWithBlankConversationIdReturnsEmptyWithoutJdbc() {
        assertTrue(repository.findByConversation(null).isEmpty());
        assertTrue(repository.findByConversation("").isEmpty());
    }

    @Test
    void existsInConversationWithBlankArgsReturnsFalseWithoutJdbc() {
        assertFalse(repository.existsInConversation(null, "supplier"));
        assertFalse(repository.existsInConversation("conv-1", null));
    }
}
