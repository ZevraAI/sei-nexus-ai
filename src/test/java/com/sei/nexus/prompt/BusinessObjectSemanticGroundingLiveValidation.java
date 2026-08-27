package com.sei.nexus.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.enterprise.EnterpriseMapService;
import com.sei.nexus.onboarding.OnboardingAnalysisJob;
import com.sei.nexus.onboarding.OnboardingAnalysisJobRepository;
import com.sei.nexus.onboarding.OnboardingService;
import com.sei.nexus.semantic.EntityCandidateService;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.sql.SqlSafetyService;
import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DIAGNOSTIC ONLY (opt-in, real LLM + real DB, never runs in the normal suite) — Business Object
 * Semantic Grounding Improvement, Step 10 validation. Confirms live, against the real
 * {@code tenant_retail_industry} / {@code conn-25c3ce28} / {@code retail_core} connection and
 * {@code inventory_adjustments} table used throughout this investigation, that:
 *
 * <ul>
 *   <li>source table/column comments and the UDT/enum type name are actually retrieved and appear
 *       in the rendered analysis context;</li>
 *   <li>no additional AI call is introduced (still one call for one table, one call per batch);</li>
 *   <li>Onboarding and Discover both receive the identical enriched context;</li>
 *   <li>the real semantic ANSWER — not just the prompt text — is compared with and without the
 *       new grounding, both for the real (friendly) table name and for a hypothetical unfriendly
 *       one, to honestly assess whether the grounding actually changes anything rather than just
 *       adding text.</li>
 * </ul>
 */
@SpringBootTest
class BusinessObjectSemanticGroundingLiveValidation {

    @Autowired private AzureOpenAiClient realAiClient;
    @Autowired private DynamicSqlService dynamicSqlService;
    @Autowired private EntityCandidateService entityCandidates;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EnterpriseMapRepository enterpriseMapRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private SqlSafetyService sqlSafetyService;
    @Autowired private OnboardingService onboardingService;
    @Autowired private OnboardingAnalysisJobRepository jobRepository;

    private static final String TENANT_SCHEMA = "tenant_retail_industry";
    private static final String CONNECTION_KEY = "conn-25c3ce28";
    private static final String SCHEMA_NAME = "retail_core";
    private static final String DOMAIN_KEY = "PLATFORM";
    private static final String TABLE_NAME = "inventory_adjustments";

    /** Counts real AI calls by delegating every call to the real, autowired client. */
    static class CountingAiClient extends AzureOpenAiClient {
        private final AzureOpenAiClient real;
        final AtomicInteger calls = new AtomicInteger(0);
        String lastUserMessage;

        CountingAiClient(AzureOpenAiClient real) {
            super(new ObjectMapper(), null);
            this.real = real;
        }

        @Override
        public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
            calls.incrementAndGet();
            lastUserMessage = messages.get(0).content();
            return real.chatWithJson(messages, systemPrompt);
        }
    }

    /** Wraps the real DynamicSqlService but strips comments (real columns/udt untouched) — isolates
     *  the "with vs without comments" variable using the same real data either way. */
    static class CommentStrippingDynamicSqlService extends DynamicSqlService {
        private final DynamicSqlService real;
        CommentStrippingDynamicSqlService(DynamicSqlService real) { super(null); this.real = real; }

        @Override
        public List<Map<String, Object>> describeTable(String c, String s, String t) {
            return real.describeTable(c, s, t);
        }

        @Override
        public TableDescription describeTableWithComments(String c, String s, String t) {
            return new TableDescription(real.describeTable(c, s, t), null);
        }
    }

    @Test
    void commentsAndUdtAppearLiveWithNoExtraAiCallAndBothPathsMatch() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live probe disabled");
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");

        TenantContext.set(TENANT_SCHEMA);
        try {
            // ── Confirm retrieval + rendering + call count, via Discover's real path ──────────
            CountingAiClient counting = new CountingAiClient(realAiClient);
            BusinessObjectBatchAnalyzer enrichedAnalyzer = new BusinessObjectBatchAnalyzer(
                    counting, dynamicSqlService, entityCandidates, objectMapper);
            EnterpriseMapService discoverService = new EnterpriseMapService(
                    enterpriseMapRepository, connectionRepository, dynamicSqlService, sqlSafetyService,
                    counting, objectMapper, entityCandidates, enrichedAnalyzer);

            Map<String, Object> result = discoverService.analyzeForOnboarding(Map.of(
                    "domainKey", DOMAIN_KEY, "connectionKey", CONNECTION_KEY,
                    "schemaName", SCHEMA_NAME, "tableNames", List.of(TABLE_NAME)));

            System.out.println("\n================ LIVE RENDERED CONTEXT (Discover, enriched) ================");
            System.out.println(counting.lastUserMessage);
            System.out.println("==============================================================================\n");

            assertEquals(1, counting.calls.get(), "one table must still be exactly one AI call — no extra call introduced");
            assertTrue(counting.lastUserMessage.contains("Source DB description:"),
                    "the real table comment must appear in the live rendered context");
            assertTrue(counting.lastUserMessage.contains("(enum:"),
                    "at least one real enum/UDT type name must appear (this table has several enum columns)");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> drafts = (List<Map<String, Object>>) result.get("tables");
            System.out.println("Discover result: category=" + drafts.get(0).get("category")
                    + " entityName=" + drafts.get(0).get("entityName"));

            // ── Confirm Onboarding gets the identical enriched context (same shared analyzer) ──
            CountingAiClient countingOnboarding = new CountingAiClient(realAiClient);
            var onboardingAnalyzer = new BusinessObjectBatchAnalyzer(
                    countingOnboarding, dynamicSqlService, entityCandidates, objectMapper);
            var onboardingResult = onboardingAnalyzer.analyzeBatch(
                    CONNECTION_KEY, SCHEMA_NAME, DOMAIN_KEY, List.of(TABLE_NAME));

            assertEquals(1, countingOnboarding.calls.get(), "Onboarding's shared analyzer call must also stay at 1");
            assertTrue(countingOnboarding.lastUserMessage.contains("Source DB description:"),
                    "Onboarding must receive the identical source comment Discover received");
            assertEquals(counting.lastUserMessage.replaceAll("\\s+", " "),
                    countingOnboarding.lastUserMessage.replaceAll("\\s+", " "),
                    "Onboarding and Discover must render byte-for-byte the same enriched schema text "
                            + "for the same table (both delegate to the same BusinessObjectBatchAnalyzer)");
            System.out.println("Onboarding result: category=" + onboardingResult.get(TABLE_NAME).get("category")
                    + " entityName=" + onboardingResult.get(TABLE_NAME).get("entityName"));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void realSemanticComparisonWithAndWithoutCommentsForTheFriendlyAndUnfriendlyName() {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live probe disabled");
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");

        TenantContext.set(TENANT_SCHEMA);
        try {
            DynamicSqlService stripped = new CommentStrippingDynamicSqlService(dynamicSqlService);

            System.out.println("\n########## A: real name, WITH comments ##########");
            var withComments = runOnce(dynamicSqlService, TABLE_NAME);
            print(withComments);

            System.out.println("\n########## B: real name, WITHOUT comments ##########");
            var withoutComments = runOnce(stripped, TABLE_NAME);
            print(withoutComments);

            System.out.println("\n########## C: renamed 'inv_adj_stg', WITH comments (real columns/comments, name swapped) ##########");
            var renamedWithComments = runOnceWithDisplayName(dynamicSqlService, TABLE_NAME, "inv_adj_stg");
            print(renamedWithComments);

            System.out.println("\n########## D: renamed 'inv_adj_stg', WITHOUT comments ##########");
            var renamedWithoutComments = runOnceWithDisplayName(stripped, TABLE_NAME, "inv_adj_stg");
            print(renamedWithoutComments);

            System.out.println("\n---- OBSERVED RESULT (report honestly, not assumed) ----");
            System.out.println("A category=" + withComments.get("category") + " entityName=" + withComments.get("entityName"));
            System.out.println("B category=" + withoutComments.get("category") + " entityName=" + withoutComments.get("entityName"));
            System.out.println("C category=" + renamedWithComments.get("category") + " entityName=" + renamedWithComments.get("entityName"));
            System.out.println("D category=" + renamedWithoutComments.get("category") + " entityName=" + renamedWithoutComments.get("entityName"));

            // Only assert what this task actually needs verified as still-working; the semantic
            // A/B DIFFERENCE itself is reported narratively above, not asserted — a single live
            // call's wording is not something to hard-assert on.
            assertNotNull(withComments.get("category"));
            assertNotNull(withoutComments.get("category"));
            assertNotNull(renamedWithComments.get("category"));
            assertNotNull(renamedWithoutComments.get("category"));
        } finally {
            TenantContext.clear();
        }
    }

    private Map<String, Object> runOnce(DynamicSqlService sql, String tableName) {
        var analyzer = new BusinessObjectBatchAnalyzer(realAiClient, sql, entityCandidates, objectMapper);
        return analyzer.analyzeBatch(CONNECTION_KEY, SCHEMA_NAME, DOMAIN_KEY, List.of(tableName)).get(tableName);
    }

    /** Fetches real columns/comments under {@code realTableName} but tells the model the table is
     *  named {@code displayName} — the exact controlled test the investigation asked for: same
     *  underlying metadata, less-informative physical name. */
    private Map<String, Object> runOnceWithDisplayName(DynamicSqlService sql, String realTableName, String displayName) {
        DynamicSqlService.TableDescription described = sql.describeTableWithComments(CONNECTION_KEY, SCHEMA_NAME, realTableName);

        StringBuilder userMessage = new StringBuilder("Domain: ").append(DOMAIN_KEY).append("\n\n");
        userMessage.append("Schema: ").append(SCHEMA_NAME).append(", Table: ").append(displayName).append("\n");
        if (described.tableComment() != null && !described.tableComment().isBlank()) {
            userMessage.append("Source DB description: ").append(described.tableComment()).append("\n");
        }
        userMessage.append("Columns:\n");
        for (Map<String, Object> col : described.columns()) {
            Object nullableVal = col.getOrDefault("is_nullable", col.get("isNullable"));
            boolean nullable = "YES".equalsIgnoreCase(String.valueOf(nullableVal));
            Object dataType = col.getOrDefault("data_type", col.get("dataType"));
            Object udtName = col.getOrDefault("udt_name", col.get("udtName"));
            userMessage.append("  - ").append(col.getOrDefault("column_name", col.get("columnName"))).append(" ").append(dataType);
            if (udtName != null && !String.valueOf(udtName).isBlank()
                    && !String.valueOf(udtName).equalsIgnoreCase(String.valueOf(dataType))) {
                userMessage.append(" (enum: ").append(udtName).append(")");
            }
            userMessage.append(nullable ? " NULL" : " NOT NULL").append("\n");
            Object comment = col.get("column_comment");
            if (comment != null && !String.valueOf(comment).isBlank()) {
                userMessage.append("      source description: ").append(comment).append("\n");
            }
        }

        String systemPrompt = """
                You are an enterprise data analyst onboarding a new database into an
                operational intelligence platform. Analyse EACH of the table schemas
                given below and respond with valid JSON only — no prose, no markdown fences.

                Required JSON structure — exactly one entry per table listed, using its
                exact table_name:
                {
                  "tables": [
                    {
                      "table_name": "<exact table name as given>",
                """ + BusinessObjectAnalysisContract.FIELD_SCHEMA + """

                    }
                  ]
                }

                Rules:
                - Return exactly one entry per table, in any order, identified by table_name.
                """ + BusinessObjectAnalysisContract.RULES;

        String raw = realAiClient.chatWithJson(List.of(ChatMessage.user(userMessage.toString())), systemPrompt);
        try {
            String extracted = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
            Map<String, Object> parsed = objectMapper.readValue(extracted, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables = (List<Map<String, Object>>) parsed.getOrDefault("tables", List.of());
            for (Map<String, Object> t : tables) {
                if (displayName.equals(t.get("table_name"))) return t;
            }
            return tables.isEmpty() ? Map.of() : tables.get(0);
        } catch (Exception e) {
            return Map.of("category", "PARSE_ERROR", "entityName", e.getMessage());
        }
    }

    private void print(Map<String, Object> analysis) {
        System.out.println("  category=" + analysis.get("category")
                + " entityName=" + analysis.get("entityName")
                + " purpose=" + analysis.get("purpose"));
    }
}
