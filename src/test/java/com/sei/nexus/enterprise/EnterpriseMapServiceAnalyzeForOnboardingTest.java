package com.sei.nexus.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.connection.NexusConnection;
import com.sei.nexus.semantic.EntityCandidateService;
import com.sei.nexus.sql.DynamicSqlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Business Object Analysis Contract convergence — Discover-from-DB side. Prior to this
 * fix, {@code EnterpriseMapService.analyzeForOnboarding()} had zero test coverage and its own,
 * independently-drifted AI contract (the incident that motivated
 * {@code BusinessObjectAnalysisContract}: it never asked for {@code category} at all, and its
 * failure stub carried only {@code tableName}/{@code error}, missing the safe defaults
 * Onboarding's equivalent stub always had). Hand-rolled fakes, no DB, no Mockito — matching
 * this repo's convention (mirrors {@code ValueDomainDiscoveryTest}'s fakes for this same class,
 * and {@code OnboardingServiceAnalysisJobTest}'s {@code FakeAiClient} pattern).
 */
class EnterpriseMapServiceAnalyzeForOnboardingTest {

    static class FakeConnectionRepository extends ConnectionRepository {
        FakeConnectionRepository() { super(null); }
        @Override public Optional<NexusConnection> findByKey(String key) {
            return Optional.of(new NexusConnection(key, "Retail DB", "POSTGRES", "",
                    "jdbc:postgresql://x/db", null, "u", "s", "", "", true,
                    null, null, null, "ACTIVE", Instant.now(), Instant.now()));
        }
    }

    static class FakeDynamicSql extends DynamicSqlService {
        /** Business Object Semantic Grounding Improvement: when set, returned verbatim instead of
         *  the default columns-only description — lets a test supply a table/column comment. */
        com.sei.nexus.sql.DynamicSqlService.TableDescription commentOverride;

        FakeDynamicSql() { super(null); }
        @Override public List<Map<String, Object>> describeTable(String c, String s, String t) {
            return List.of(Map.of("column_name", "id", "data_type", "uuid"));
        }
        @Override
        public com.sei.nexus.sql.DynamicSqlService.TableDescription describeTableWithComments(String c, String s, String t) {
            return commentOverride != null ? commentOverride : super.describeTableWithComments(c, s, t);
        }
    }

    static class FakeEntityCandidateService extends EntityCandidateService {
        FakeEntityCandidateService() { super(null); }
        @Override public List<Candidate> retrieve(String domainKey, String tableName) { return List.of(); }
    }

    /**
     * Records the system prompt used and returns a scripted, batch-shaped ({@code "tables": [...]})
     * response covering every table present in that call's schema text — matching
     * {@code BusinessObjectBatchAnalyzer}'s real request/response envelope (one AI call may now
     * cover multiple tables at once).
     */
    static class FakeAiClient extends AzureOpenAiClient {
        private static final Pattern TABLE_PATTERN = Pattern.compile("Table: (\\S+)");
        private static final ObjectMapper MAPPER = new ObjectMapper();

        String lastSystemPrompt;
        String lastUserMessage;
        final AtomicInteger callCount = new AtomicInteger(0);
        /** table_name -> raw JSON response body (a single flat object, matching Discover's contract). */
        Map<String, String> responseByTable = Map.of();
        /** Tables whose call throws (simulates a real AI-call failure). */
        List<String> failingTables = List.of();

        FakeAiClient() { super(new ObjectMapper(), null); }

        @Override
        public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
            lastSystemPrompt = systemPrompt;
            callCount.incrementAndGet();
            String userMessage = messages.get(0).content();
            lastUserMessage = userMessage;

            List<String> tablesInBatch = new java.util.ArrayList<>();
            Matcher m = TABLE_PATTERN.matcher(userMessage);
            while (m.find()) tablesInBatch.add(m.group(1));

            for (String failing : failingTables) {
                if (tablesInBatch.contains(failing)) {
                    throw new RuntimeException("simulated AI failure for " + failing);
                }
            }

            try {
                List<Map<String, Object>> tables = new java.util.ArrayList<>();
                for (String tableName : tablesInBatch) {
                    String raw = responseByTable.getOrDefault(tableName,
                            "{\"entityName\":\"Thing\",\"purpose\":\"p\"}"); // no category — exercises the default
                    Map<String, Object> entry = new LinkedHashMap<>(
                            MAPPER.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                    entry.put("table_name", tableName);
                    tables.add(entry);
                }
                return MAPPER.writeValueAsString(Map.of("tables", tables));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private FakeConnectionRepository connectionRepository;
    private FakeDynamicSql dynamicSql;
    private FakeAiClient aiClient;
    private EnterpriseMapService service;

    @BeforeEach
    void setUp() {
        connectionRepository = new FakeConnectionRepository();
        dynamicSql = new FakeDynamicSql();
        aiClient = new FakeAiClient();
        service = new EnterpriseMapService(null, connectionRepository, dynamicSql, null,
                aiClient, new ObjectMapper(), new FakeEntityCandidateService(),
                new com.sei.nexus.prompt.BusinessObjectBatchAnalyzer(aiClient, dynamicSql,
                        new FakeEntityCandidateService(), new ObjectMapper()));
    }

    private static Map<String, Object> request(String... tableNames) {
        return Map.of("domainKey", "PLATFORM", "connectionKey", "conn-1",
                "schemaName", "retail_core", "tableNames", List.of(tableNames));
    }

    @Test
    void discoveredTableCarriesTheAiGeneratedCategory() {
        aiClient.responseByTable = Map.of("suppliers",
                "{\"entityName\":\"Supplier\",\"category\":\"Procurement\",\"purpose\":\"p\"}");

        Map<String, Object> result = service.analyzeForOnboarding(request("suppliers"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertEquals("Procurement", tables.get(0).get("category"));
    }

    @Test
    void missingCategoryFromTheModelDefaultsToOtherRatherThanBeingUngrouped() {
        // FakeAiClient's default response has no "category" field at all.
        Map<String, Object> result = service.analyzeForOnboarding(request("widgets"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertEquals("Other", tables.get(0).get("category"),
                "a discovered table must never end up with no category at all");
    }

    @Test
    void aFailedTableGetsTheCanonicalFailureStubNotJustTableNameAndError() {
        aiClient.failingTables = List.of("broken_table");

        Map<String, Object> result = service.analyzeForOnboarding(request("broken_table"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        Map<String, Object> draft = tables.get(0);
        assertEquals("broken_table", draft.get("tableName"));
        assertNotNull(draft.get("error"));
        // Previously this stub carried ONLY tableName/error — now it must match Onboarding's
        // canonical stub shape, so a failed Discover table degrades identically to a failed
        // Onboarding table.
        assertEquals("Other", draft.get("category"));
        assertEquals("Broken Table", draft.get("entityName"));
        assertEquals("", draft.get("purpose"));
        assertEquals(List.of(), draft.get("vocabularySuggestions"));
    }

    @Test
    void discoverSpecificFieldsStillWork() {
        aiClient.responseByTable = Map.of("suppliers", """
                {"entityName":"Supplier","category":"Procurement","businessName":"Suppliers",
                 "identifierColumns":["id","code"],"usageGuidance":"Join to purchase_orders",
                 "relationshipHints":["supplier_id references suppliers.id"]}
                """);

        Map<String, Object> result = service.analyzeForOnboarding(request("suppliers"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        Map<String, Object> draft = tables.get(0);
        assertEquals("Suppliers", draft.get("businessName"),
                "Discover-specific fields the wizard doesn't produce must still work");
        assertEquals(List.of("id", "code"), draft.get("identifierColumns"));
        assertEquals("Join to purchase_orders", draft.get("usageGuidance"));
        assertEquals(List.of("supplier_id references suppliers.id"), draft.get("relationshipHints"));
    }

    @Test
    void multipleTablesInOneRequestCanHaveDifferentCategories() {
        aiClient.responseByTable = Map.of(
                "suppliers", "{\"entityName\":\"Supplier\",\"category\":\"Procurement\",\"purpose\":\"p\"}",
                "stores",    "{\"entityName\":\"Store\",\"category\":\"Operations\",\"purpose\":\"p\"}");

        Map<String, Object> result = service.analyzeForOnboarding(request("suppliers", "stores"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        Map<String, Object> byName1 = tables.get(0);
        Map<String, Object> byName2 = tables.get(1);
        assertEquals("Procurement", byName1.get("category"));
        assertEquals("Operations",  byName2.get("category"));
    }

    // ── Multi-Table Analysis Hardening: backend-enforced selection limit ───────
    // Same rule as Onboarding: the limit must protect the backend, not merely the UI.
    // This calls analyzeForOnboarding directly (as if a bypassed/scripted client sent an
    // over-limit request straight to the API) and confirms the backend rejects it on its own.

    @Test
    void requestingMoreThanTheMaximumSelectedTablesIsRejectedByTheBackend() {
        int max = com.sei.nexus.prompt.BusinessObjectAnalysisContract.MAX_SELECTED_TABLES;
        String[] tooMany = new String[max + 1];
        for (int i = 0; i < tooMany.length; i++) tooMany[i] = "table_" + i;

        assertThrows(com.sei.nexus.common.NexusException.class,
                () -> service.analyzeForOnboarding(request(tooMany)),
                "a Discover selection exceeding the maximum must be rejected outright by the backend");
        assertEquals(0, aiClient.callCount.get(),
                "an over-limit Discover request must be rejected before any AI call is attempted");
    }

    // ── Multi-Table Analysis Hardening: Discover batching ───────────────────────
    // Discover previously made one AI call PER table; it must now batch, exactly like
    // Onboarding — this is the actual fix this task is about.

    @Test
    void discoverDoesNotMakeOneAiCallPerTable() {
        aiClient.responseByTable = Map.of(
                "suppliers", "{\"entityName\":\"Supplier\",\"category\":\"Procurement\",\"purpose\":\"p\"}",
                "stores",    "{\"entityName\":\"Store\",\"category\":\"Operations\",\"purpose\":\"p\"}",
                "orders",    "{\"entityName\":\"Order\",\"category\":\"Transactions\",\"purpose\":\"p\"}");

        service.analyzeForOnboarding(request("suppliers", "stores", "orders"));

        // Default discoverBatchSize (matching Onboarding's default batch-size of 4) means all
        // 3 tables fit in a single batch — one AI call, not three.
        assertEquals(1, aiClient.callCount.get(),
                "3 tables at the default batch size must produce 1 AI call, not 3 — "
                        + "batching is the whole point of this fix");
    }

    // ── Business Object Semantic Grounding Improvement ──────────────────────────

    @Test
    void discoverReceivesTheSameSourceCommentEnrichedContextAsOnboarding() {
        dynamicSql.commentOverride = new com.sei.nexus.sql.DynamicSqlService.TableDescription(
                List.of(Map.of("column_name", "id", "data_type", "uuid")),
                "Records inventory quantity adjustments resulting from cycle counts and reconciliation.");

        service.analyzeForOnboarding(request("inventory_adjustments"));

        assertTrue(aiClient.lastUserMessage.contains(
                        "Source DB description: Records inventory quantity adjustments resulting from cycle counts and reconciliation."),
                "Discover must receive the same source-comment-enriched context as Onboarding — "
                        + "both delegate to the same BusinessObjectBatchAnalyzer");
    }

    // ── Cross-path parity: proves the shared contract is actually shared, not just similar ──

    @Test
    void systemPromptContainsTheExactSharedCanonicalFieldSchemaAndRules() {
        service.analyzeForOnboarding(request("suppliers"));

        assertTrue(aiClient.lastSystemPrompt.contains(
                        com.sei.nexus.prompt.BusinessObjectAnalysisContract.FIELD_SCHEMA),
                "Discover's prompt must contain the exact same canonical field schema Onboarding uses");
        assertTrue(aiClient.lastSystemPrompt.contains(
                        com.sei.nexus.prompt.BusinessObjectAnalysisContract.RULES),
                "Discover's prompt must contain the exact same canonical rules Onboarding uses");
    }
}
