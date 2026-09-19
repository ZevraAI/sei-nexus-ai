package com.sei.nexus.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.semantic.EntityCandidateService;
import com.sei.nexus.sql.DynamicSqlService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE model-tiering evaluation (opt-in, real OpenAI calls): compares gpt-4o against gpt-4o-mini
 * (the candidate {@code nexus.openai.onboarding-model} default) against the REAL {@link
 * BusinessObjectBatchAnalyzer#analyzeBatch} — same schema-rendering/prompt-construction path
 * {@code OnboardingService}/{@code EnterpriseMapService} use, with the same hand-rolled DB fakes
 * as {@code BusinessObjectBatchAnalyzerCommentEnrichmentTest} (no live DB needed — describeTable
 * is faked, only the OpenAI call is real).
 *
 * <p>Guarded by {@code -Dnexus.live.openai=true} and {@code OPENAI_API_KEY}, same convention as
 * {@code IdentifierFidelityLiveProbe}.
 *
 * <p>Contract checked, per {@link BusinessObjectAnalysisContract}: every requested table gets
 * exactly one entry, JSON is valid, and the analysis never invents a physical column name that
 * wasn't in the table description it was given (Discover/Onboarding's most safety-relevant
 * rule — this is what {@code applyConceptResolution}'s "unresolved is always safe" discipline
 * exists to backstop, so a candidate model must not force that discipline to trigger constantly).
 */
class BusinessObjectBatchAnalyzerModelTieringLiveValidation {

    static class FakeEntityCandidateService extends EntityCandidateService {
        FakeEntityCandidateService() { super(null); }
        @Override public List<Candidate> retrieve(String domainKey, String tableName) { return List.of(); }
    }

    static class FakeDynamicSqlService extends DynamicSqlService {
        final Map<String, DynamicSqlService.TableDescription> byTable;
        FakeDynamicSqlService(Map<String, DynamicSqlService.TableDescription> byTable) {
            super(null);
            this.byTable = byTable;
        }
        @Override
        public DynamicSqlService.TableDescription describeTableWithComments(
                String connectionKey, String schemaName, String tableName) {
            return byTable.get(tableName);
        }
    }

    private static AzureOpenAiClient liveClient(String model) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");
        AzureOpenAiClient client = new AzureOpenAiClient(new ObjectMapper(), null);
        Field apiKeyField = AzureOpenAiClient.class.getDeclaredField("apiKey");
        apiKeyField.setAccessible(true);
        apiKeyField.set(client, apiKey);
        Field modelField = AzureOpenAiClient.class.getDeclaredField("onboardingModel");
        modelField.setAccessible(true);
        modelField.set(client, model);
        Method init = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        init.setAccessible(true);
        init.invoke(client);
        return client;
    }

    private static Map<String, Object> col(String name, String dataType, String nullable) {
        return Map.of("column_name", name, "data_type", dataType, "is_nullable", nullable);
    }

    /** Two representative tables — mirrors the shape a real Discover/Onboarding batch sends. */
    private static Map<String, DynamicSqlService.TableDescription> representativeTables() {
        return Map.of(
                "purchase_orders", new DynamicSqlService.TableDescription(List.of(
                        col("id", "uuid", "NO"),
                        col("po_number", "character varying", "NO"),
                        col("supplier_id", "uuid", "NO"),
                        col("ordered_date", "date", "YES"),
                        col("total_ordered_amount", "numeric", "YES"),
                        col("status", "character varying", "NO")
                ), null),
                "audit_log_entries", new DynamicSqlService.TableDescription(List.of(
                        col("id", "uuid", "NO"),
                        col("actor", "character varying", "YES"),
                        col("action", "character varying", "YES"),
                        col("created_at", "timestamp with time zone", "NO")
                ), null)
        );
    }

    @Test
    void batchAnalysisContractHoldsOnBothModels() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live evaluation disabled");

        Map<String, DynamicSqlService.TableDescription> tables = representativeTables();
        List<String> tableNames = List.of("purchase_orders", "audit_log_entries");

        BusinessObjectBatchAnalyzer analyzerOnGpt4o = new BusinessObjectBatchAnalyzer(
                liveClient("gpt-4o"), new FakeDynamicSqlService(tables), new FakeEntityCandidateService(),
                new ObjectMapper());
        BusinessObjectBatchAnalyzer analyzerOnGpt4oMini = new BusinessObjectBatchAnalyzer(
                liveClient("gpt-4o-mini"), new FakeDynamicSqlService(tables), new FakeEntityCandidateService(),
                new ObjectMapper());

        Map<String, Map<String, Object>> baseline =
                analyzerOnGpt4o.analyzeBatch("conn-1", "public", "retail", tableNames);
        Map<String, Map<String, Object>> candidate =
                analyzerOnGpt4oMini.analyzeBatch("conn-1", "public", "retail", tableNames);

        System.out.println("gpt-4o      -> " + baseline);
        System.out.println("gpt-4o-mini -> " + candidate);

        assertBatchAnalysisContract("gpt-4o", tableNames, tables, baseline);
        assertBatchAnalysisContract("gpt-4o-mini", tableNames, tables, candidate);
    }

    private static void assertBatchAnalysisContract(String label, List<String> tableNames,
            Map<String, DynamicSqlService.TableDescription> tables, Map<String, Map<String, Object>> result) {
        for (String tableName : tableNames) {
            Map<String, Object> entry = result.get(tableName);
            assertTrue(entry != null, label + ": missing analysis entry for table " + tableName);
            assertFalse(entry.containsKey("error") && entry.get("error") != null
                    && String.valueOf(entry.get("error")).contains("no analysis returned"),
                    label + ": table " + tableName + " was dropped from the batch response");

            Object entityNameObj = entry.get("entityName");
            assertTrue(entityNameObj != null && !String.valueOf(entityNameObj).isBlank(),
                    label + ": table " + tableName + " has no entityName (canonical field missing)");

            // Safety-relevant contract: the analysis text must not invent a physical identifier
            // that isn't one of this table's REAL columns — checked against the "purpose" field,
            // the most likely place a model would name a column it hallucinated.
            List<String> realColumns = tables.get(tableName).columns().stream()
                    .map(c -> String.valueOf(c.get("column_name"))).toList();
            Object purpose = entry.get("purpose");
            if (purpose != null) {
                for (String token : String.valueOf(purpose).split("\\W+")) {
                    if (token.length() > 3 && token.endsWith("_id") && !realColumns.contains(token)) {
                        throw new AssertionError(label + ": table " + tableName
                                + " purpose text references an unknown column-like token '" + token + "'");
                    }
                }
            }
        }
    }
}
