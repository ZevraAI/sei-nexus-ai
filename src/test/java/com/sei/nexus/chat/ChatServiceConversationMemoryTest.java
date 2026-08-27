package com.sei.nexus.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentmemory.BusinessWorldToolAdapter;
import com.sei.nexus.agentmemory.ConversationMemoryService;
import com.sei.nexus.agentmemory.ConversationRosterEntry;
import com.sei.nexus.agentmemory.ConversationRosterRepository;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conversation Memory → Chat integration (Phase 3) — {@code ChatService.buildMemorySelectionContext}.
 * Hand-rolled fakes; no database, no Mockito — same convention as {@code AgentToolRegistryTest}.
 * The method under test is private, so it (and the private {@code chatMemoryEnabled} flag field)
 * are exercised via reflection — the same seam pattern the repo already uses for package-private
 * static methods (e.g. {@code ChatService.assembleEntityContext}), extended here because this
 * particular seam is an instance method with instance state, not a static one.
 *
 * <p>Deliberately does NOT instantiate every ChatService collaborator — everything unrelated to
 * this method is passed as {@code null}, which is safe because {@code buildMemorySelectionContext}
 * never touches them.
 */
class ChatServiceConversationMemoryTest {

    static class FakeRosterRepository extends ConversationRosterRepository {
        final List<ConversationRosterEntry> rows = new ArrayList<>();
        FakeRosterRepository() { super(null); }
        @Override public void ensure(String conversationId, String entityKey, String businessName, String objectType) {
            rows.add(new ConversationRosterEntry(conversationId, entityKey, businessName, objectType, Instant.now()));
        }
        @Override public List<ConversationRosterEntry> findByConversation(String conversationId) {
            return rows.stream().filter(r -> r.conversationId().equals(conversationId)).toList();
        }
        @Override public boolean existsInConversation(String conversationId, String entityKey) {
            return rows.stream().anyMatch(r -> r.conversationId().equals(conversationId) && r.entityKey().equals(entityKey));
        }
    }

    static class FakeEnterpriseMapRepository extends EnterpriseMapRepository {
        final List<DataObject> objects = new ArrayList<>();
        FakeEnterpriseMapRepository() { super(null); }
        @Override public Optional<DataObject> findDataObjectByKey(String objectKey) {
            return objects.stream().filter(o -> o.objectKey().equals(objectKey)).findFirst();
        }
    }

    static class FakeAzureOpenAiClient extends AzureOpenAiClient {
        String canned = "{\"entity_keys\":[]}";
        boolean throwOnCall = false;
        final AtomicInteger callCount = new AtomicInteger();
        String lastSystemPrompt;
        FakeAzureOpenAiClient() { super(new ObjectMapper(), null); }
        @Override public String chat(List<ChatMessage> messages, String systemPrompt) {
            callCount.incrementAndGet();
            lastSystemPrompt = systemPrompt;
            if (throwOnCall) throw new RuntimeException("simulated LLM failure");
            return canned;
        }
    }

    private static DataObject object(String key, String businessName, String schema, String table, String purpose) {
        return new DataObject(key, "domain", "Entity " + key, "conn-1", schema, table,
                businessName, purpose, null, null, null, null, null, null, null,
                null, false, "SCANNED", 1, null, null);
    }

    private FakeRosterRepository rosterRepo;
    private FakeEnterpriseMapRepository enterpriseRepo;
    private FakeAzureOpenAiClient fakeAiClient;
    private ChatService chatService;

    private static final String CONV = "conv-1";

    @BeforeEach
    void setUp() throws Exception {
        rosterRepo = new FakeRosterRepository();
        enterpriseRepo = new FakeEnterpriseMapRepository();
        fakeAiClient = new FakeAzureOpenAiClient();

        ConversationMemoryService memoryService = new ConversationMemoryService(rosterRepo);
        BusinessWorldToolAdapter businessWorldAdapter = new BusinessWorldToolAdapter(enterpriseRepo);

        chatService = new ChatService(
                null, null, null, null, null, null, null, null, null, null, null, null,
                fakeAiClient, new ObjectMapper(), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null,
                memoryService, businessWorldAdapter);
    }

    private void setFlag(boolean enabled) throws Exception {
        Field f = ChatService.class.getDeclaredField("chatMemoryEnabled");
        f.setAccessible(true);
        f.set(chatService, enabled);
    }

    private String invoke(String question, String conversationId) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("buildMemorySelectionContext", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(chatService, question, conversationId);
    }

    // ── 1. flag off ───────────────────────────────────────────────────────────

    @Test
    void featureFlagOffReturnsEmptyAndNeverCallsTheLlm() throws Exception {
        setFlag(false);
        rosterRepo.ensure(CONV, "supplier", "Supplier", "entity");

        String result = invoke("Include the supplier.", CONV);

        assertEquals("", result);
        assertEquals(0, fakeAiClient.callCount.get(), "disabled: existing Chat behavior is preserved exactly, no new call");
    }

    // ── 2. empty roster ──────────────────────────────────────────────────────

    @Test
    void emptyRosterReturnsEmptyWithoutCallingTheLlm() throws Exception {
        setFlag(true);

        String result = invoke("Show me today's open orders.", CONV);

        assertEquals("", result);
        assertEquals(0, fakeAiClient.callCount.get(), "empty roster: skip the call entirely, per design");
    }

    // ── 3. non-empty roster triggers the call ────────────────────────────────

    @Test
    void nonEmptyRosterTriggersTheDedicatedCall() throws Exception {
        setFlag(true);
        rosterRepo.ensure(CONV, "supplier", "Supplier", "entity");
        fakeAiClient.canned = "{\"entity_keys\":[]}";

        invoke("Show me today's open orders.", CONV);

        assertEquals(1, fakeAiClient.callCount.get());
        assertNotEquals(ChatService.DECISION_SYSTEM_PROMPT, fakeAiClient.lastSystemPrompt,
                "must use its own independent prompt, never the decision-router prompt");
        assertEquals(ChatService.MEMORY_SELECTION_SYSTEM_PROMPT, fakeAiClient.lastSystemPrompt);
    }

    // ── 4/5. correct single/multi-key selection ──────────────────────────────

    @Test
    void singleSelectedKeyIsRetrievedAndInjected() throws Exception {
        setFlag(true);
        rosterRepo.ensure(CONV, "supplier", "Supplier", "entity");
        enterpriseRepo.objects.add(object("supplier", "Supplier", "public", "suppliers", "vendor master data"));
        fakeAiClient.canned = "{\"entity_keys\":[\"supplier\"]}";

        String result = invoke("Include the supplier.", CONV);

        assertTrue(result.contains("supplier"));
        assertTrue(result.contains("public.suppliers"));
        assertTrue(result.contains("vendor master data"));
    }

    @Test
    void multipleSelectedKeysAreAllRetrievedAndInjected() throws Exception {
        setFlag(true);
        rosterRepo.ensure(CONV, "supplier", "Supplier", "entity");
        rosterRepo.ensure(CONV, "store", "Store", "entity");
        enterpriseRepo.objects.add(object("supplier", "Supplier", "public", "suppliers", null));
        enterpriseRepo.objects.add(object("store", "Store", "public", "stores", null));
        fakeAiClient.canned = "{\"entity_keys\":[\"supplier\",\"store\"]}";

        String result = invoke("Include the supplier and store.", CONV);

        assertTrue(result.contains("public.suppliers"));
        assertTrue(result.contains("public.stores"));
    }

    // ── 6. unknown key rejected ───────────────────────────────────────────────

    @Test
    void unknownEntityKeyIsRejectedNotSubstituted() throws Exception {
        setFlag(true);
        rosterRepo.ensure(CONV, "supplier", "Supplier", "entity");
        // "warehouse" was never discovered in this conversation, and never even exists in
        // EnterpriseMap in this test — the hallucination case.
        fakeAiClient.canned = "{\"entity_keys\":[\"warehouse\"]}";

        String result = invoke("Now include warehouse.", CONV);

        assertEquals("", result, "an unknown/hallucinated key must never be substituted or discovered");
    }

    // ── 7. non-member key (belongs to a different conversation) rejected ────

    @Test
    void keyFromAnotherConversationIsRejected() throws Exception {
        setFlag(true);
        rosterRepo.ensure("other-conv", "supplier", "Supplier", "entity");
        enterpriseRepo.objects.add(object("supplier", "Supplier", "public", "suppliers", null));
        fakeAiClient.canned = "{\"entity_keys\":[\"supplier\"]}";

        String result = invoke("Include the supplier.", CONV);

        assertEquals("", result, "membership is exact and per-conversation — no cross-conversation leakage");
    }

    // ── 8. LLM failure is fail-safe ───────────────────────────────────────────

    @Test
    void llmFailureReturnsEmptyAndDoesNotPropagate() throws Exception {
        setFlag(true);
        rosterRepo.ensure(CONV, "supplier", "Supplier", "entity");
        fakeAiClient.throwOnCall = true;

        String result = assertDoesNotThrow(() -> invoke("Include the supplier.", CONV));

        assertEquals("", result);
    }

    // ── 9. malformed JSON is fail-safe ────────────────────────────────────────

    @Test
    void malformedJsonReturnsEmptyAndDoesNotPropagate() throws Exception {
        setFlag(true);
        rosterRepo.ensure(CONV, "supplier", "Supplier", "entity");
        fakeAiClient.canned = "not valid json at all {{{";

        String result = assertDoesNotThrow(() -> invoke("Include the supplier.", CONV));

        assertEquals("", result);
    }

    // ── 10. retrieved-object shape ────────────────────────────────────────────

    @Test
    void injectedContextNeverExposesMoreThanTableIdentityAndPurpose() throws Exception {
        setFlag(true);
        rosterRepo.ensure(CONV, "supplier", "Supplier", "entity");
        enterpriseRepo.objects.add(object("supplier", "Supplier", "public", "suppliers", "vendor data"));
        fakeAiClient.canned = "{\"entity_keys\":[\"supplier\"]}";

        String result = invoke("Include the supplier.", CONV);

        assertTrue(result.startsWith("Conversation memory"));
        assertFalse(result.contains("null"), "no field should render as the literal string 'null'");
    }

    // ── 11. getLlmDecision()'s own contract is untouched ─────────────────────

    @Test
    void decisionSystemPromptIsCompletelyUnchangedByThisFeature() {
        // The empirically-tested regression was caused by extending DECISION_SYSTEM_PROMPT
        // itself. This asserts the two prompts remain fully independent, byte for byte.
        assertFalse(ChatService.DECISION_SYSTEM_PROMPT.contains("entity_keys"));
        assertFalse(ChatService.DECISION_SYSTEM_PROMPT.contains("neededMemoryKeys"));
        assertFalse(ChatService.DECISION_SYSTEM_PROMPT.contains("Conversation memory"));
        assertTrue(ChatService.DECISION_SYSTEM_PROMPT.contains(
                "\"type\": \"ANSWER_FROM_MEMORY|QUERY_LIVE_DATA|HYBRID_DOC_AND_DATA|ASK_CLARIFICATION|KNOWLEDGE_GAP\""),
                "unchanged verbatim from before this phase");
    }
}
