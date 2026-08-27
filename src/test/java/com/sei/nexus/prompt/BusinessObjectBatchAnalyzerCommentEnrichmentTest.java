package com.sei.nexus.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.semantic.EntityCandidateService;
import com.sei.nexus.sql.DynamicSqlService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Business Object Semantic Grounding Improvement — table/column comments and the UDT/enum type
 * name are additive to {@link BusinessObjectBatchAnalyzer}'s rendered schema text, and both
 * {@code OnboardingService} and {@code EnterpriseMapService} pick this up automatically since
 * both delegate to this one shared analyzer (see {@code AnalysisPathParityTest} for that
 * structural guarantee). Hand-rolled fakes, no DB, no Mockito — this project's convention.
 */
class BusinessObjectBatchAnalyzerCommentEnrichmentTest {

    static class FakeEntityCandidateService extends EntityCandidateService {
        FakeEntityCandidateService() { super(null); }
        @Override public List<Candidate> retrieve(String domainKey, String tableName) { return List.of(); }
    }

    /** Captures the prompt instead of calling a real model. */
    static class CapturingAiClient extends AzureOpenAiClient {
        String lastUserMessage;
        CapturingAiClient() { super(new ObjectMapper(), null); }
        @Override
        public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
            lastUserMessage = messages.get(0).content();
            return "{\"tables\":[{\"table_name\":\"t\",\"category\":\"Other\"}]}";
        }
    }

    /** Returns a scripted {@link DynamicSqlService.TableDescription} instead of hitting a real DB. */
    static class FakeDynamicSqlService extends DynamicSqlService {
        DynamicSqlService.TableDescription scripted;
        boolean overrideComments = true;

        FakeDynamicSqlService() { super(null); }

        @Override
        public List<Map<String, Object>> describeTable(String connectionKey, String schemaName, String tableName) {
            return scripted != null ? scripted.columns() : List.of();
        }

        @Override
        public DynamicSqlService.TableDescription describeTableWithComments(String connectionKey, String schemaName, String tableName) {
            if (!overrideComments) {
                // Exercise the REAL base-class fallback path (no connectionRepository wired) —
                // proves comment retrieval degrades gracefully rather than failing the analysis.
                return super.describeTableWithComments(connectionKey, schemaName, tableName);
            }
            return scripted;
        }
    }

    private static Map<String, Object> col(String name, String dataType, String nullable, String udtName) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("column_name", name);
        c.put("data_type", dataType);
        c.put("is_nullable", nullable);
        c.put("udt_name", udtName);
        return c;
    }

    private CapturingAiClient aiClient;
    private FakeDynamicSqlService dynamicSql;
    private BusinessObjectBatchAnalyzer analyzer;

    private void build() {
        aiClient = new CapturingAiClient();
        dynamicSql = new FakeDynamicSqlService();
        analyzer = new BusinessObjectBatchAnalyzer(aiClient, dynamicSql, new FakeEntityCandidateService(), new ObjectMapper());
    }

    @Test
    void tableCommentAppearsInTheRenderedContext() {
        build();
        Map<String, Object> idCol = col("id", "uuid", "NO", "uuid");
        dynamicSql.scripted = new DynamicSqlService.TableDescription(List.of(idCol),
                "Records inventory quantity adjustments resulting from cycle counts, damage, and reconciliation.");

        analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("inventory_adjustments"));

        assertTrue(aiClient.lastUserMessage.contains(
                        "Source DB description: Records inventory quantity adjustments resulting from cycle counts, damage, and reconciliation."),
                "the table's source DB comment must appear, clearly labeled, in the rendered context");
    }

    @Test
    void columnCommentAppearsAlongsideThatColumn() {
        build();
        Map<String, Object> statusCol = col("status", "USER-DEFINED", "NO", "inventory_adjustment_status");
        statusCol.put("column_comment", "Lifecycle state of the adjustment: draft, submitted, approved, posted.");
        dynamicSql.scripted = new DynamicSqlService.TableDescription(List.of(statusCol), null);

        analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("inventory_adjustments"));

        assertTrue(aiClient.lastUserMessage.contains(
                        "source description: Lifecycle state of the adjustment: draft, submitted, approved, posted."),
                "the column's source DB comment must appear alongside that column");
    }

    @Test
    void missingCommentsRenderExactlyAsBeforeThisChange() {
        build();
        dynamicSql.scripted = new DynamicSqlService.TableDescription(
                List.of(col("id", "uuid", "NO", "uuid")), null);

        analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("orders"));

        assertFalse(aiClient.lastUserMessage.contains("Source DB description"),
                "no table comment was supplied — no description line should appear");
        assertFalse(aiClient.lastUserMessage.contains("source description"),
                "no column comment was supplied — no description line should appear");
        assertTrue(aiClient.lastUserMessage.contains("Schema: retail_core, Table: orders"),
                "the original schema/table line must be unchanged");
        assertTrue(aiClient.lastUserMessage.contains("  - id uuid NOT NULL"),
                "a column with no enum/UDT distinction must render exactly as before this change");
    }

    @Test
    void udtEnumTypeNameReplacesTheGenericUserDefinedLabel() {
        build();
        dynamicSql.scripted = new DynamicSqlService.TableDescription(
                List.of(col("adjustment_type", "USER-DEFINED", "NO", "inventory_adjustment_type")), null);

        analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("inventory_adjustments"));

        assertTrue(aiClient.lastUserMessage.contains("adjustment_type USER-DEFINED (enum: inventory_adjustment_type) NOT NULL"),
                "the underlying enum/UDT type name must be surfaced instead of just the generic label");
    }

    @Test
    void udtNameIsOmittedWhenAbsentOrIdenticalToTheGenericType() {
        build();
        // A plain, non-enum column: udt_name ("varchar") differs from data_type only trivially —
        // real describeTable() output for a varchar column has udt_name="varchar", data_type=
        // "character varying"; the point here is a column with NO udt_name at all.
        Map<String, Object> plain = col("notes", "text", "YES", null);
        dynamicSql.scripted = new DynamicSqlService.TableDescription(List.of(plain), null);

        analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("inventory_adjustments"));

        assertFalse(aiClient.lastUserMessage.contains("(enum:"),
                "no udt_name was supplied — no enum annotation should appear");
        assertTrue(aiClient.lastUserMessage.contains("  - notes text NULL"),
                "the column line must render exactly as before this change");
    }

    @Test
    void commentRetrievalFailureDegradesGracefullyRatherThanFailingTheAnalysis() {
        build();
        dynamicSql.overrideComments = false; // exercise the real base-class fallback path
        dynamicSql.scripted = new DynamicSqlService.TableDescription(
                List.of(col("id", "uuid", "NO", "uuid")), null); // used by describeTable() override

        Map<String, Map<String, Object>> result =
                analyzer.analyzeBatch("conn-1", "retail_core", "PLATFORM", List.of("orders"));

        assertNotNull(result.get("orders"), "the table must still be analyzed even when comment retrieval fails");
        assertEquals("Other", result.get("orders").get("category"),
                "the analysis must proceed normally — comments are enrichment, never a dependency");
        assertFalse(aiClient.lastUserMessage.contains("Source DB description"),
                "a failed comment lookup must silently omit the description, not surface an error");
    }
}
