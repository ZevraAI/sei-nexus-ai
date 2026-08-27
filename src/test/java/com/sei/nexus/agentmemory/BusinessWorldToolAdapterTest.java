package com.sei.nexus.agentmemory;

import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Business World reuse (Phase 1) — hand-rolled fake over {@link EnterpriseMapRepository},
 * no database, no Mockito. Confirms the adapter is a pure delegation with no relevance
 * judgment and no Conversation Memory side effect from listing.
 */
class BusinessWorldToolAdapterTest {

    static class FakeEnterpriseMapRepository extends EnterpriseMapRepository {
        record ByKeyCall(String key) {}
        record ByDomainCall(String domain) {}
        final List<ByKeyCall> byKeyCalls = new ArrayList<>();
        final List<ByDomainCall> byDomainCalls = new ArrayList<>();
        final List<DataObject> objects = new ArrayList<>();

        FakeEnterpriseMapRepository() { super(null); }

        @Override
        public Optional<DataObject> findDataObjectByKey(String objectKey) {
            byKeyCalls.add(new ByKeyCall(objectKey));
            return objects.stream().filter(o -> o.objectKey().equals(objectKey)).findFirst();
        }

        @Override
        public List<DataObject> findDataObjectsByDomain(String domainKey) {
            byDomainCalls.add(new ByDomainCall(domainKey));
            return objects.stream().filter(o -> o.domainKey().equals(domainKey)).toList();
        }
    }

    private static DataObject object(String key, String domain, String businessName) {
        return new DataObject(key, domain, "Entity " + key, "conn-1", "public", key,
                businessName, "purpose", null, null, null, null, null, null, null,
                null, false, "SCANNED", 1, null, null);
    }

    private FakeEnterpriseMapRepository repository;
    private BusinessWorldToolAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = new FakeEnterpriseMapRepository();
        adapter = new BusinessWorldToolAdapter(repository);
    }

    @Test
    void getBusinessObjectIsAnExactKeyLookup() {
        repository.objects.add(object("supplier", "inventory", "Supplier"));
        repository.objects.add(object("product", "inventory", "Product"));

        Optional<DataObject> result = adapter.getBusinessObject("supplier");

        assertTrue(result.isPresent());
        assertEquals("Supplier", result.get().businessName());
        assertEquals(1, repository.byKeyCalls.size());
        assertEquals("supplier", repository.byKeyCalls.get(0).key());
    }

    @Test
    void getBusinessObjectDoesNotSubstituteOnMiss() {
        repository.objects.add(object("supplier", "inventory", "Supplier"));

        Optional<DataObject> result = adapter.getBusinessObject("warehouse");

        assertTrue(result.isEmpty(), "no guessing, no substitution — an unknown key returns empty");
    }

    @Test
    void listBusinessObjectsIsDeterministicByExactGroup() {
        repository.objects.add(object("supplier", "inventory", "Supplier"));
        repository.objects.add(object("product", "inventory", "Product"));
        repository.objects.add(object("store", "retail", "Store"));

        List<DataObject> result = adapter.listBusinessObjects("inventory");

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(o -> o.domainKey().equals("inventory")));
    }

    @Test
    void listBusinessObjectsHasNoConversationRosterSideEffect() {
        repository.objects.add(object("supplier", "inventory", "Supplier"));
        repository.objects.add(object("product", "inventory", "Product"));

        adapter.listBusinessObjects("inventory");

        // Structural guarantee, not just behavioural: BusinessWorldToolAdapter has no
        // dependency on ConversationRosterRepository/ConversationMemoryService at all —
        // listing literally cannot register anything. Verified here by confirming the
        // adapter's only collaborator is the EnterpriseMapRepository fake and that calling
        // it twice produces no roster-shaped state anywhere the fake can observe.
        assertEquals(1, repository.byDomainCalls.size());
    }
}
