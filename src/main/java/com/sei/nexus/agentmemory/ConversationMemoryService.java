package com.sei.nexus.agentmemory;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service layer for Conversation Memory. Owns ONLY conversation-scoped Business Object
 * references (the roster) — never Business World metadata itself. Business World remains
 * authoritative for anything beyond an entity's identity/label; retrieving the current
 * authoritative object for a member entity is {@code BusinessWorldToolAdapter}'s job.
 *
 * <p>Not yet wired to the Agent tool loop (Phase 1). This class establishes the reusable
 * persistence/service mechanism that a later phase will call:
 * <ul>
 *   <li>{@link #list(String)} — backs the future {@code memory.list()} capability.</li>
 *   <li>{@link #isMember(String, String)} — backs the membership-validation half of the
 *       future {@code memory.get(entity_key)} capability (the retrieval half belongs to
 *       {@code BusinessWorldToolAdapter}).</li>
 *   <li>{@link #registerDiscovery(String, String, String, String)} — the mechanical,
 *       non-semantic side effect a later phase will trigger after a successful authoritative
 *       retrieval or successful query execution. Listing is never discovery: nothing in this
 *       class calls this method automatically, and no other method in this class has any
 *       persistence side effect.</li>
 * </ul>
 *
 * <p>No method here inspects the user's question, ranks entities, or performs any
 * relevance judgment — every operation is an exact, deterministic lookup or an idempotent
 * mechanical registration.
 */
@Service
public class ConversationMemoryService {

    private final ConversationRosterRepository rosterRepository;

    public ConversationMemoryService(ConversationRosterRepository rosterRepository) {
        this.rosterRepository = rosterRepository;
    }

    /** The current conversation roster — backs {@code memory.list()}. */
    public List<ConversationRosterEntry> list(String conversationId) {
        return rosterRepository.findByConversation(conversationId);
    }

    /** Roster-membership check only — backs the validation half of {@code memory.get(entity_key)}. */
    public boolean isMember(String conversationId, String entityKey) {
        return rosterRepository.existsInConversation(conversationId, entityKey);
    }

    /**
     * Mechanical, idempotent registration — NOT a semantic decision. Intended callers (a later
     * phase): the successful path of {@code get_business_object}, and successful {@code
     * query_database} execution for each resolved Business Object binding. Never called from
     * listing operations.
     */
    public void registerDiscovery(String conversationId, String entityKey, String businessName, String objectType) {
        rosterRepository.ensure(conversationId, entityKey, businessName, objectType);
    }
}
