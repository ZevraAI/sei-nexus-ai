package com.sei.nexus.agentmemory;

import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Isolated, read-only adapter over the existing authoritative Business World
 * ({@link EnterpriseMapRepository} / {@code nexus_data_object}) — the same repository
 * {@code AgentBrain} already depends on. No second Business World is created here; this
 * class adds no new implementation, only exact-key/exact-group delegation.
 *
 * <p>Not yet registered as an Agent tool (Phase 1). This is the future backing for
 * {@code get_business_object(entity_key)} and {@code list_business_objects(group)}.
 *
 * <p>This adapter never searches the user's question, ranks entities, performs lexical or
 * semantic filtering, infers synonyms, or guesses physical table names — every lookup is
 * an exact key or an exact group (domain) match, exactly as the underlying repository
 * already behaves.
 *
 * <p><b>Listing is not discovery:</b> {@link #listBusinessObjects(String)} has no
 * Conversation Memory side effect — it never touches {@link ConversationRosterRepository}
 * or {@link ConversationMemoryService}. Only an explicit, successful single-entity
 * retrieval is eligible to become a mechanical roster registration, and that wiring is a
 * later phase's responsibility, not this adapter's.
 */
@Service
public class BusinessWorldToolAdapter {

    /** The single object_type marker used for every Business Object surfaced through this
     *  adapter — {@code nexus_data_object} rows are, uniformly, governed data objects
     *  (tables/views); no classification is invented here. */
    public static final String OBJECT_TYPE_ENTITY = "entity";

    private final EnterpriseMapRepository enterpriseMapRepository;

    public BusinessWorldToolAdapter(EnterpriseMapRepository enterpriseMapRepository) {
        this.enterpriseMapRepository = enterpriseMapRepository;
    }

    /** Exact-key retrieval of one authoritative Business Object. No search, no substitution. */
    public Optional<DataObject> getBusinessObject(String entityKey) {
        if (entityKey == null || entityKey.isBlank()) return Optional.empty();
        return enterpriseMapRepository.findDataObjectByKey(entityKey);
    }

    /**
     * Deterministic listing of Business Objects in an exact group (domain key). No relevance
     * judgment, no ranking — the existing {@code findDataObjectsByDomain} ordering (by
     * entity name) is preserved as-is. Never mutates Conversation Memory.
     */
    public List<DataObject> listBusinessObjects(String group) {
        if (group == null || group.isBlank()) return List.of();
        return enterpriseMapRepository.findDataObjectsByDomain(group);
    }
}
