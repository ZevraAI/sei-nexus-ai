package com.sei.nexus.agentmemory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conversation Memory foundation (Phase 1) — hand-rolled fake, no database, no Mockito,
 * following the repo's established repository-faking convention (see
 * {@code AgentToolRegistryTest}).
 */
class ConversationMemoryServiceTest {

    /** Records calls and simulates the real repository's idempotent-upsert / query semantics
     *  entirely in memory, so we can assert on ConversationMemoryService's behaviour without a
     *  database. */
    static class FakeRosterRepository extends ConversationRosterRepository {
        record EnsureCall(String conversationId, String entityKey, String businessName, String objectType) {}
        final List<EnsureCall> ensureCalls = new ArrayList<>();
        final List<ConversationRosterEntry> rows = new ArrayList<>();

        FakeRosterRepository() { super(null); }

        @Override
        public void ensure(String conversationId, String entityKey, String businessName, String objectType) {
            ensureCalls.add(new EnsureCall(conversationId, entityKey, businessName, objectType));
            boolean alreadyPresent = rows.stream()
                    .anyMatch(r -> r.conversationId().equals(conversationId) && r.entityKey().equals(entityKey));
            if (!alreadyPresent) {
                rows.add(new ConversationRosterEntry(conversationId, entityKey, businessName, objectType, Instant.now()));
            }
        }

        @Override
        public List<ConversationRosterEntry> findByConversation(String conversationId) {
            return rows.stream().filter(r -> r.conversationId().equals(conversationId)).toList();
        }

        @Override
        public boolean existsInConversation(String conversationId, String entityKey) {
            return rows.stream()
                    .anyMatch(r -> r.conversationId().equals(conversationId) && r.entityKey().equals(entityKey));
        }
    }

    private FakeRosterRepository repository;
    private ConversationMemoryService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRosterRepository();
        service = new ConversationMemoryService(repository);
    }

    @Test
    void listReturnsOnlyReferencesForTheGivenConversation() {
        service.registerDiscovery("conv-1", "purchase-order", "Purchase Order", "entity");
        service.registerDiscovery("conv-1", "supplier", "Supplier", "entity");
        service.registerDiscovery("conv-2", "product", "Product", "entity");

        List<ConversationRosterEntry> roster = service.list("conv-1");

        assertEquals(2, roster.size());
        assertTrue(roster.stream().anyMatch(e -> e.entityKey().equals("purchase-order")));
        assertTrue(roster.stream().anyMatch(e -> e.entityKey().equals("supplier")));
        assertTrue(roster.stream().noneMatch(e -> e.entityKey().equals("product")),
                "conversation isolation: conv-2's entity must not leak into conv-1's list");
    }

    @Test
    void listOnUnknownConversationReturnsEmptyNotAnError() {
        assertTrue(service.list("never-seen").isEmpty());
    }

    @Test
    void isMemberSucceedsForARegisteredEntity() {
        service.registerDiscovery("conv-1", "supplier", "Supplier", "entity");

        assertTrue(service.isMember("conv-1", "supplier"));
    }

    @Test
    void isMemberFailsForAnUnknownEntity() {
        service.registerDiscovery("conv-1", "supplier", "Supplier", "entity");

        assertFalse(service.isMember("conv-1", "warehouse"),
                "membership must not guess/substitute — an undiscovered entity is simply not a member");
    }

    @Test
    void isMemberIsIsolatedPerConversation() {
        service.registerDiscovery("conv-1", "supplier", "Supplier", "entity");

        assertFalse(service.isMember("conv-2", "supplier"),
                "an entity discovered in one conversation must not be visible as a member of another");
    }

    @Test
    void registerDiscoveryIsIdempotentForRepeatedDiscoveryOfTheSameEntity() {
        service.registerDiscovery("conv-1", "supplier", "Supplier", "entity");
        service.registerDiscovery("conv-1", "supplier", "Supplier", "entity");
        service.registerDiscovery("conv-1", "supplier", "Supplier", "entity");

        assertEquals(3, repository.ensureCalls.size(), "every call reaches the repository...");
        assertEquals(1, service.list("conv-1").size(), "...but the roster never duplicates the entity");
    }
}
