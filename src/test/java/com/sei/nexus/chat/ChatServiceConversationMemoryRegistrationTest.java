package com.sei.nexus.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentmemory.BusinessWorldToolAdapter;
import com.sei.nexus.agentmemory.ConversationMemoryService;
import com.sei.nexus.agentmemory.ConversationRosterEntry;
import com.sei.nexus.agentmemory.ConversationRosterRepository;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.runtime.ExecutionReference;
import com.sei.nexus.runtime.ExecutionReferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conversation Memory write side (Chat integration, Phase 4) —
 * {@code ChatService.registerExecutedBusinessObjects}. Same hand-rolled-fake, reflection-seam
 * convention as {@link ChatServiceConversationMemoryTest} (Phase 3).
 */
class ChatServiceConversationMemoryRegistrationTest {

    static class FakeRosterRepository extends ConversationRosterRepository {
        record EnsureCall(String conversationId, String entityKey, String businessName, String objectType) {}
        final List<EnsureCall> ensureCalls = new ArrayList<>();
        FakeRosterRepository() { super(null); }
        @Override public void ensure(String conversationId, String entityKey, String businessName, String objectType) {
            ensureCalls.add(new EnsureCall(conversationId, entityKey, businessName, objectType));
        }
        @Override public List<ConversationRosterEntry> findByConversation(String conversationId) { return List.of(); }
        @Override public boolean existsInConversation(String conversationId, String entityKey) { return false; }
    }

    static class FakeEnterpriseMapRepository extends EnterpriseMapRepository {
        final List<DataObject> objects = new ArrayList<>();
        FakeEnterpriseMapRepository() { super(null); }
        @Override public Optional<DataObject> findDataObjectByKey(String objectKey) {
            return objects.stream().filter(o -> o.objectKey().equals(objectKey)).findFirst();
        }
    }

    static class FakeExecutionReferenceRepository extends ExecutionReferenceRepository {
        Optional<ExecutionReference> toReturn = Optional.empty();
        boolean throwOnLookup = false;
        int lookupCount = 0;
        String lastConversationId;
        FakeExecutionReferenceRepository() { super(null, new ObjectMapper()); }
        @Override public Optional<ExecutionReference> findLatestByConversation(String conversationId) {
            lookupCount++;
            lastConversationId = conversationId;
            if (throwOnLookup) throw new RuntimeException("simulated repository failure");
            return toReturn;
        }
    }

    private static DataObject object(String key, String businessName) {
        return new DataObject(key, "domain", "Entity " + key, "conn-1", "public", key,
                businessName, "purpose", null, null, null, null, null, null, null,
                null, false, "SCANNED", 1, null, null);
    }

    private static ExecutionReference execRef(String executionId, String conversationId,
                                               Map<String, String> objectBindings) {
        return new ExecutionReference(executionId, null, conversationId, "run-1", "conn-1",
                Instant.now(), Instant.now(), 10, "EXECUTE_SYNC", 2, List.of("id"), "[{\"id\":1}]",
                "SELECT ...", "ctr-1", "hash", List.of(), objectBindings, Map.of(), List.of());
    }

    private FakeRosterRepository rosterRepo;
    private FakeEnterpriseMapRepository enterpriseRepo;
    private FakeExecutionReferenceRepository execRefRepo;
    private ChatService chatService;

    private static final String CONV = "conv-1";

    @BeforeEach
    void setUp() throws Exception {
        rosterRepo = new FakeRosterRepository();
        enterpriseRepo = new FakeEnterpriseMapRepository();
        execRefRepo = new FakeExecutionReferenceRepository();

        ConversationMemoryService memoryService = new ConversationMemoryService(rosterRepo);
        BusinessWorldToolAdapter businessWorldAdapter = new BusinessWorldToolAdapter(enterpriseRepo);

        chatService = new ChatService(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, new ObjectMapper(), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, execRefRepo,
                memoryService, businessWorldAdapter);
    }

    private void setFlag(boolean enabled) throws Exception {
        Field f = ChatService.class.getDeclaredField("chatMemoryEnabled");
        f.setAccessible(true);
        f.set(chatService, enabled);
    }

    private void invoke(String conversationId) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("registerExecutedBusinessObjects", String.class);
        m.setAccessible(true);
        m.invoke(chatService, conversationId);
    }

    // ── 1. flag off ───────────────────────────────────────────────────────────

    @Test
    void featureFlagOffPerformsNoLookupAndNoRegistration() throws Exception {
        setFlag(false);

        invoke(CONV);

        assertEquals(0, execRefRepo.lookupCount, "disabled: no execution-reference lookup at all");
        assertTrue(rosterRepo.ensureCalls.isEmpty());
    }

    // ── 2. no execution reference ────────────────────────────────────────────

    @Test
    void noExecutionReferenceMeansNoRegistration() throws Exception {
        setFlag(true);
        execRefRepo.toReturn = Optional.empty();

        invoke(CONV);

        assertEquals(1, execRefRepo.lookupCount);
        assertTrue(rosterRepo.ensureCalls.isEmpty());
    }

    // ── 3. successful reference registers its object keys ───────────────────

    @Test
    void successfulExecutionReferenceRegistersItsObjectKeys() throws Exception {
        setFlag(true);
        enterpriseRepo.objects.add(object("purchase-order", "Purchase Order"));
        execRefRepo.toReturn = Optional.of(
                execRef("exec-1", CONV, Map.of("purchase-order", "public.purchase_orders")));

        invoke(CONV);

        assertEquals(1, rosterRepo.ensureCalls.size());
        FakeRosterRepository.EnsureCall call = rosterRepo.ensureCalls.get(0);
        assertEquals(CONV, call.conversationId());
        assertEquals("purchase-order", call.entityKey());
        assertEquals("Purchase Order", call.businessName());
        assertEquals(BusinessWorldToolAdapter.OBJECT_TYPE_ENTITY, call.objectType());
    }

    // ── 4. multiple object keys ──────────────────────────────────────────────

    @Test
    void multipleObjectKeysAreAllRegistered() throws Exception {
        setFlag(true);
        enterpriseRepo.objects.add(object("purchase-order", "Purchase Order"));
        enterpriseRepo.objects.add(object("supplier", "Supplier"));
        execRefRepo.toReturn = Optional.of(execRef("exec-1", CONV, Map.of(
                "purchase-order", "public.purchase_orders",
                "supplier", "public.suppliers")));

        invoke(CONV);

        assertEquals(2, rosterRepo.ensureCalls.size());
        List<String> keys = rosterRepo.ensureCalls.stream().map(FakeRosterRepository.EnsureCall::entityKey).toList();
        assertTrue(keys.contains("purchase-order"));
        assertTrue(keys.contains("supplier"));
    }

    // ── 5. unknown business object safely skipped ────────────────────────────

    @Test
    void unknownBusinessObjectIsSkippedNotInvented() throws Exception {
        setFlag(true);
        // "purchase-order" is bound by the execution reference but does NOT exist in
        // EnterpriseMap in this test — mirrors a stale/renamed object.
        execRefRepo.toReturn = Optional.of(
                execRef("exec-1", CONV, Map.of("purchase-order", "public.purchase_orders")));

        invoke(CONV);

        assertTrue(rosterRepo.ensureCalls.isEmpty(), "never register a key that couldn't be authoritatively resolved");
    }

    // ── 6. registration exception never escapes ──────────────────────────────

    @Test
    void executionReferenceLookupFailureNeverPropagates() throws Exception {
        setFlag(true);
        execRefRepo.throwOnLookup = true;

        assertDoesNotThrow(() -> invoke(CONV));
    }

    @Test
    void rosterWriteFailureNeverPropagates() throws Exception {
        setFlag(true);
        enterpriseRepo.objects.add(object("supplier", "Supplier"));
        execRefRepo.toReturn = Optional.of(execRef("exec-1", CONV, Map.of("supplier", "public.suppliers")));
        FakeRosterRepository failingRoster = new FakeRosterRepository() {
            @Override public void ensure(String conversationId, String entityKey, String businessName, String objectType) {
                throw new RuntimeException("simulated roster write failure");
            }
        };
        // Rebuild ChatService with the failing roster to isolate this failure mode.
        ConversationMemoryService failingMemoryService = new ConversationMemoryService(failingRoster);
        BusinessWorldToolAdapter adapter = new BusinessWorldToolAdapter(enterpriseRepo);
        ChatService failingChatService = new ChatService(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, new ObjectMapper(), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, execRefRepo, failingMemoryService, adapter);
        Field f = ChatService.class.getDeclaredField("chatMemoryEnabled");
        f.setAccessible(true);
        f.set(failingChatService, true);
        Method m = ChatService.class.getDeclaredMethod("registerExecutedBusinessObjects", String.class);
        m.setAccessible(true);

        assertDoesNotThrow(() -> m.invoke(failingChatService, CONV));
    }

    // ── 7. no Java-side membership pre-check (idempotency delegated) ────────

    @Test
    void noMembershipPreCheckBeforeRegistration() throws Exception {
        setFlag(true);
        enterpriseRepo.objects.add(object("supplier", "Supplier"));
        execRefRepo.toReturn = Optional.of(execRef("exec-1", CONV, Map.of("supplier", "public.suppliers")));

        invoke(CONV);
        invoke(CONV); // repeated turn touching the same object

        // Every call reaches the (idempotent, per ConversationRosterRepository.ensure's real
        // ON CONFLICT DO NOTHING semantics) repository — Java performs no pre-check of its own.
        assertEquals(2, rosterRepo.ensureCalls.size());
    }

    // ── 8. conversation ID threading ──────────────────────────────────────────

    @Test
    void conversationIdIsPassedToTheLookup() throws Exception {
        setFlag(true);
        execRefRepo.toReturn = Optional.empty();

        invoke("conv-specific-id");

        assertEquals("conv-specific-id", execRefRepo.lastConversationId);
    }

    // ── 9. business name source ───────────────────────────────────────────────

    @Test
    void businessNameComesFromBusinessWorldToolAdapterNotSql() throws Exception {
        setFlag(true);
        enterpriseRepo.objects.add(object("supplier", "Authoritative Supplier Name"));
        // The execution reference's own bound "table" string is intentionally unrelated —
        // registration must never derive the business name from it.
        execRefRepo.toReturn = Optional.of(execRef("exec-1", CONV, Map.of("supplier", "public.raw_table_xyz")));

        invoke(CONV);

        assertEquals("Authoritative Supplier Name", rosterRepo.ensureCalls.get(0).businessName());
    }

    // ── 10. object keys come from businessObjectBindings ─────────────────────

    @Test
    void objectKeysComeExclusivelyFromExecutionReferenceBindings() throws Exception {
        setFlag(true);
        enterpriseRepo.objects.add(object("store", "Store"));
        enterpriseRepo.objects.add(object("warehouse", "Warehouse")); // exists but NOT bound
        execRefRepo.toReturn = Optional.of(execRef("exec-1", CONV, Map.of("store", "public.stores")));

        invoke(CONV);

        assertEquals(1, rosterRepo.ensureCalls.size());
        assertEquals("store", rosterRepo.ensureCalls.get(0).entityKey());
    }
}
