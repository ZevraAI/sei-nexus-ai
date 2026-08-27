package com.sei.nexus.prompt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.EntityCandidateService;
import com.sei.nexus.sql.DynamicSqlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Multi-Table Analysis Hardening: the shared batched Business Object analysis execution
 * mechanism used by BOTH {@code OnboardingService.analyzeTableBatch()} (the Onboarding Wizard)
 * and {@code EnterpriseMapService.analyzeForOnboarding()} (Discover from DB). Neither service
 * calls the other; both call this instead.
 *
 * <p>One call to {@link #analyzeBatch} = one AI call covering up to {@code tableNames.size()}
 * tables (callers partition a larger selection into safe-sized batches before calling this —
 * see {@link BusinessObjectAnalysisContract#MAX_SELECTED_TABLES}). Every returned entry
 * conforms to {@link BusinessObjectAnalysisContract} (category/entityName/purpose/
 * vocabularySuggestions never missing), plus a {@code "columns"} key carrying the described
 * physical schema so callers never re-describe. Degrades gracefully per table: a
 * {@code describeTable} failure for one table, or a whole-batch AI failure, only stubs the
 * affected table(s) — never aborts the rest of the batch.
 *
 * <p>Deliberately excludes: any caller-specific wrapper fields (Onboarding's
 * {@code table_name}/{@code schema_name}/{@code connection_key}/{@code domain_key}/
 * {@code entity_key} snake_case wrapper vs. Discover's {@code tableName}/{@code schemaName}/
 * etc. camelCase wrapper — callers add their own on top of this method's return value) and any
 * caller-specific additional prompt field (Onboarding's {@code suggestedQuestions}, wizard-
 * bootstrap-only, passed in via {@code extraFieldSchema}/{@code extraRules} so it rides the
 * SAME single AI call rather than requiring a second one).
 */
@Component
public class BusinessObjectBatchAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(BusinessObjectBatchAnalyzer.class);

    private final AzureOpenAiClient aiClient;
    private final DynamicSqlService dynamicSqlService;
    private final EntityCandidateService entityCandidates;
    private final ObjectMapper objectMapper;
    // Connection-Scoped Industry Pack Semantic Assignment: resolving the connection's ACTIVE
    // pack (and its canonical concepts) reuses this existing repository — no Java resolver, no
    // new lookup mechanism. Nullable for the same reason as MetadataRegistrationService's
    // packRepository: existing hand-rolled test fixtures that don't care about pack-aware
    // concept resolution keep working unchanged via the 4-arg constructor below.
    private final IndustryPackRepository packRepository;

    // Bug fix (this task): two constructors with neither marked @Autowired left Spring unable
    // to pick one — it failed at real application startup with a misleading "No default
    // constructor found" (Spring's message for "ambiguous constructor autowiring", not literally
    // a missing no-arg constructor). MetadataRegistrationService's equivalent 6-arg/4-arg pair
    // already had this annotation; this one was missed when the same pattern was added here.
    @org.springframework.beans.factory.annotation.Autowired
    public BusinessObjectBatchAnalyzer(AzureOpenAiClient aiClient, DynamicSqlService dynamicSqlService,
                                        EntityCandidateService entityCandidates, ObjectMapper objectMapper,
                                        IndustryPackRepository packRepository) {
        this.aiClient = aiClient;
        this.dynamicSqlService = dynamicSqlService;
        this.entityCandidates = entityCandidates;
        this.objectMapper = objectMapper;
        this.packRepository = packRepository;
    }

    /** Backward-compatible convenience (tests): no pack lookup ⇒ no concept context is ever
     *  added to the prompt — byte-identical to this class's behavior before this feature. */
    public BusinessObjectBatchAnalyzer(AzureOpenAiClient aiClient, DynamicSqlService dynamicSqlService,
                                        EntityCandidateService entityCandidates, ObjectMapper objectMapper) {
        this(aiClient, dynamicSqlService, entityCandidates, objectMapper, null);
    }

    /**
     * Analyzes one batch of tables in a single AI call.
     *
     * @param extraFieldSchema caller-specific additional JSON field(s) to request in the same
     *                         call (e.g. Onboarding's {@code suggestedQuestions}), appended
     *                         after {@link BusinessObjectAnalysisContract#FIELD_SCHEMA}; {@code
     *                         null}/blank for a caller with no extra field (Discover).
     * @param extraRules       the rule(s) governing {@code extraFieldSchema}; same nullability.
     * @return one entry per requested table, keyed by table name; never omits a table even on
     *         total failure (degrades to {@link BusinessObjectAnalysisContract#canonicalStub}).
     */
    public Map<String, Map<String, Object>> analyzeBatch(String connectionKey, String schemaName,
            String domainKey, List<String> tableNames, String extraFieldSchema, String extraRules) {

        Map<String, List<Map<String, Object>>> columnsByTable = new LinkedHashMap<>();
        StringBuilder userMessage = new StringBuilder("Domain: ").append(domainKey).append("\n\n");
        List<EntityCandidateService.Candidate> anyCandidateSample = new ArrayList<>();

        // Connection-Scoped Industry Pack Semantic Assignment: resolved ONCE per batch call
        // (every table in a batch shares the same connection, hence the same active pack —
        // mirroring MetadataRegistrationService's identical "resolve once, not once per entity"
        // shape) rather than once per table. Empty when the connection has no ACTIVE pack, or
        // this instance has no packRepository (test fixtures) — in that case every code path
        // below that depends on `activePack` is a no-op and the prompt is byte-identical to
        // before this feature existed.
        ActivePackContext activePack = resolveActivePackContext(connectionKey);
        if (!activePack.concepts().isEmpty()) {
            userMessage.append(renderConceptCatalog(activePack));
        }

        for (String tableName : tableNames) {
            List<Map<String, Object>> columns;
            String tableComment;
            try {
                // Business Object Semantic Grounding Improvement: table/column comments and the
                // UDT/enum type name are enrichment on top of the same describeTable() call this
                // already made — see DynamicSqlService.describeTableWithComments for the graceful
                // degradation (missing/unsupported comments never fail this analysis).
                DynamicSqlService.TableDescription described =
                        dynamicSqlService.describeTableWithComments(connectionKey, schemaName, tableName);
                columns = described.columns();
                tableComment = described.tableComment();
            } catch (Exception e) {
                log.warn("describeTable failed for {}: {}", tableName, e.getMessage());
                columnsByTable.put(tableName, null);
                continue;
            }
            columnsByTable.put(tableName, columns);
            userMessage.append(buildSchemaText(schemaName, tableName, columns, tableComment));

            var candidates = entityCandidates.retrieve(domainKey, tableName);
            String candidateBlock = entityCandidates.renderPromptBlock(candidates);
            if (!candidateBlock.isBlank()) {
                userMessage.append(candidateBlock);
                if (anyCandidateSample.isEmpty()) anyCandidateSample.addAll(candidates);
            }
            userMessage.append("\n");
        }

        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        List<String> tablesToAnalyze = tableNames.stream()
                .filter(t -> columnsByTable.get(t) != null).collect(Collectors.toList());
        for (String tableName : tableNames) {
            if (columnsByTable.get(tableName) == null) {
                results.put(tableName, stub(tableName, "describe_table failed"));
            }
        }
        if (tablesToAnalyze.isEmpty()) return results;

        String extraField = (extraFieldSchema == null || extraFieldSchema.isBlank()) ? "" : ",\n" + extraFieldSchema;
        String extraRule  = (extraRules == null || extraRules.isBlank()) ? "" : extraRules + "\n";
        // Connection-Scoped Industry Pack Semantic Assignment: this is generated internally
        // (not caller-supplied like extraFieldSchema/extraRules) because it depends only on the
        // connection's own pack assignment, so it applies identically whether the caller is
        // Onboarding or Discover — no per-caller opt-in needed, no duplicated logic. Empty when
        // activePack has no concepts, so the schema/response contract is completely unchanged
        // for any connection without an assigned pack.
        String conceptField = activePack.concepts().isEmpty() ? "" : ",\n" + CONCEPT_RESOLUTION_FIELD_SCHEMA;
        String conceptRule  = activePack.concepts().isEmpty() ? "" : CONCEPT_RESOLUTION_RULES;

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
                """ + BusinessObjectAnalysisContract.FIELD_SCHEMA + extraField + conceptField + """

                    }
                  ]
                }

                Rules:
                - Return exactly one entry per table, in any order, identified by table_name.
                """ + extraRule
                + BusinessObjectAnalysisContract.RULES + "\n"
                + conceptRule
                + entityCandidates.resolutionContract(anyCandidateSample);

        try {
            String analysisJson = aiClient.chatWithJson(
                    List.of(ChatMessage.user(userMessage.toString())), systemPrompt);
            Map<String, Object> parsed = parseJson(analysisJson);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables =
                    (List<Map<String, Object>>) parsed.getOrDefault("tables", List.of());
            Map<String, Map<String, Object>> byTableName = new LinkedHashMap<>();
            for (Map<String, Object> analysis : tables) {
                String tn = String.valueOf(analysis.get("table_name"));
                byTableName.put(tn, analysis);
            }

            for (String tableName : tablesToAnalyze) {
                Map<String, Object> analysis = byTableName.get(tableName);
                if (analysis == null) {
                    log.warn("Batch response missing entry for table {}", tableName);
                    results.put(tableName, stub(tableName, "no analysis returned for this table"));
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>(analysis);
                entry.put("columns", columnsByTable.get(tableName));
                BusinessObjectAnalysisContract.applyCanonicalDefaults(entry, tableName);
                // Connection-Scoped Industry Pack Semantic Assignment: Java validates the LLM's
                // own decision against the actual candidate list it was given — it never makes
                // the decision itself (no scoring, no ranking, no fallback guess). packKey is
                // written unconditionally when a pack is active — it doesn't depend on the LLM
                // at all, it is pure connection metadata.
                applyConceptResolution(entry, activePack);
                results.put(tableName, entry);
            }
        } catch (Exception e) {
            log.warn("Batch analysis failed for {} table(s) [{}]: {}",
                    tablesToAnalyze.size(), String.join(",", tablesToAnalyze), e.getMessage());
            for (String tableName : tablesToAnalyze) {
                results.put(tableName, stub(tableName, e.getMessage()));
            }
        }
        return results;
    }

    /** Convenience overload for a caller with no additional prompt field (Discover). */
    public Map<String, Map<String, Object>> analyzeBatch(String connectionKey, String schemaName,
            String domainKey, List<String> tableNames) {
        return analyzeBatch(connectionKey, schemaName, domainKey, tableNames, null, null);
    }

    private Map<String, Object> stub(String tableName, String error) {
        Map<String, Object> entry = new LinkedHashMap<>(BusinessObjectAnalysisContract.canonicalStub(tableName));
        entry.put("error", error);
        return entry;
    }

    /**
     * Business Object Semantic Grounding Improvement: additive to the existing rendering — the
     * original "Schema: X, Table: Y" first line and per-column "name type NULL/NOT NULL" line are
     * unchanged (so existing table-name-based parsing of this text, in tests and elsewhere,
     * keeps working); a table description line is added when the source database has a table
     * comment, the enum/UDT underlying type name is surfaced instead of the generic
     * "USER-DEFINED" label, and a column description line is added when a column comment exists.
     * All three are optional — a table/column with none of this metadata renders exactly as
     * before this change.
     */
    private String buildSchemaText(String schemaName, String tableName, List<Map<String, Object>> columns,
                                    String tableComment) {
        StringBuilder sb = new StringBuilder();
        sb.append("Schema: ").append(schemaName).append(", Table: ").append(tableName).append("\n");
        if (tableComment != null && !tableComment.isBlank()) {
            // Labeled explicitly as the SOURCE database's own comment — grounding evidence for
            // the model to weigh, not a substitute for the entityName/purpose/etc. it must still
            // produce itself, and never conflated with Zevra's own generated business meaning.
            sb.append("Source DB description: ").append(tableComment).append("\n");
        }
        sb.append("Columns:\n");
        for (Map<String, Object> col : columns) {
            Object nullableVal = col.getOrDefault("is_nullable", col.get("isNullable"));
            boolean nullable = "YES".equalsIgnoreCase(String.valueOf(nullableVal))
                    || Boolean.TRUE.equals(nullableVal);
            Object dataType = col.getOrDefault("data_type", col.get("dataType"));
            Object udtName = col.getOrDefault("udt_name", col.get("udtName"));

            sb.append("  - ")
              .append(col.getOrDefault("column_name", col.get("columnName")))
              .append(" ")
              .append(dataType);
            // The generic SQL type name ("USER-DEFINED") tells the model nothing; the underlying
            // enum/UDT type name (e.g. "inventory_adjustment_type") often does — surface it
            // whenever it differs from the generic label already fetched by describeTable().
            if (udtName != null && !String.valueOf(udtName).isBlank()
                    && !String.valueOf(udtName).equalsIgnoreCase(String.valueOf(dataType))) {
                sb.append(" (enum: ").append(udtName).append(")");
            }
            sb.append(nullable ? " NULL" : " NOT NULL").append("\n");

            Object columnComment = col.get("column_comment");
            if (columnComment != null && !String.valueOf(columnComment).isBlank()) {
                sb.append("      source description: ").append(columnComment).append("\n");
            }
        }
        return sb.toString();
    }

    // ── Connection-Scoped Industry Pack Semantic Assignment ─────────────────────

    /** One canonical concept offered to the LLM as a candidate, sourced from the connection's
     *  ACTIVE Industry Pack. See {@link #extractConcepts} for where conceptKey comes from.
     *  Retail Pack V2 / Send operationalMeaning to the LLM: {@code operationalMeaning} is
     *  additive — it was previously read from {@code PackEntity} but never reached this record
     *  or the rendered prompt (a real, confirmed gap: pack authors could write it, but the LLM
     *  never saw it). Rendered by {@link #renderConceptCatalog} exactly like {@code description}
     *  — still no scoring/ranking, purely more semantic context for the LLM's own decision. */
    private record ConceptInfo(String conceptKey, String name, List<String> aliases, String description,
                                String operationalMeaning) {}

    /** The connection's active pack context for one batch call — packKey plus its offered
     *  concepts. {@link #NONE} covers "no active pack"/"no packRepository"/lookup failure —
     *  every caller below treats that as "this feature is inactive for this batch", never as
     *  an error, so the prompt/response contract is byte-identical to before this feature. */
    private record ActivePackContext(String packKey, List<ConceptInfo> concepts) {
        private static final ActivePackContext NONE = new ActivePackContext(null, List.of());
    }

    private static final String CONCEPT_RESOLUTION_FIELD_SCHEMA = """
              "conceptResolution": {"conceptKey": "<one of the concept_key values listed above, or null>", "confidence": "HIGH|MEDIUM|LOW", "reason": "one sentence"}""";

    private static final String CONCEPT_RESOLUTION_RULES = """
            - conceptResolution is optional: only include it when this table's ACTUAL business \
            meaning (from its columns, comments, and structure — never from its physical table \
            or column names alone) clearly matches one of the "Canonical Business Concepts" \
            listed above. The tenant's physical names will often NOT resemble the concept \
            names — connect them by business meaning, not name similarity. conceptKey MUST be \
            copied exactly from the listed concept_key values — never invent one. If no listed \
            concept clearly applies, set conceptKey to null; leaving it unresolved is always \
            preferable to guessing.
            """;

    private ActivePackContext resolveActivePackContext(String connectionKey) {
        if (packRepository == null || connectionKey == null || connectionKey.isBlank()) {
            return ActivePackContext.NONE;
        }
        try {
            TenantPack assignment = packRepository.findActivePackForConnection(connectionKey).orElse(null);
            if (assignment == null) return ActivePackContext.NONE;
            IndustryPack pack = packRepository.findPackById(assignment.packKey()).orElse(null);
            if (pack == null) return ActivePackContext.NONE;
            List<ConceptInfo> concepts = extractConcepts(pack);
            return concepts.isEmpty() ? ActivePackContext.NONE : new ActivePackContext(pack.packId(), concepts);
        } catch (Exception e) {
            log.debug("No active-pack concept context for connection '{}': {}", connectionKey, e.getMessage());
            return ActivePackContext.NONE;
        }
    }

    /**
     * Connection-Scoped Industry Pack Semantic Assignment — a gap, reported here per the task's
     * explicit instruction rather than silently worked around: {@code IndustryPack.groups()}
     * (the intended home for Global Concepts — a {@code PackEntity.conceptKey} within a {@code
     * PackGroup}) is populated in ZERO shipped pack JSON files today. Sourcing concepts from
     * {@code pack.entities()} instead (the flat, already-populated matching-template list) is
     * the only way this feature has real data to offer the LLM today. Each entity's {@code
     * conceptKey} is used when the pack author already set one; otherwise a plain, mechanical
     * slug of the entity's {@code name} is used as its identity (e.g. "Purchase Order" ->
     * "purchase-order") — this is a Java STRING NORMALIZATION, not a semantic decision: it does
     * not choose which concept applies to which table (the LLM still does that), it only gives
     * each already Pack-authored concept a stable key to be referenced by. When a pack is later
     * authored with real {@code groups()}/{@code conceptKey} values, those take priority
     * automatically and this fallback simply stops being exercised for it — no code change
     * needed. DEPRECATED concepts are excluded.
     */
    private List<ConceptInfo> extractConcepts(IndustryPack pack) {
        if (pack.entities() == null) return List.of();
        List<ConceptInfo> concepts = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        for (PackEntity e : pack.entities()) {
            if (e.name() == null || e.name().isBlank()) continue;
            if ("DEPRECATED".equalsIgnoreCase(e.status())) continue;
            String key = (e.conceptKey() != null && !e.conceptKey().isBlank()) ? e.conceptKey() : slugify(e.name());
            if (key.isBlank() || !seenKeys.add(key)) continue; // skip blank/duplicate keys defensively
            concepts.add(new ConceptInfo(key, e.name(),
                    e.aliases() != null ? e.aliases() : List.of(), e.description(), e.operationalMeaning()));
        }
        return concepts;
    }

    /** Plain string normalization (lowercase, non-alphanumeric -> hyphen) — never a business
     *  decision, only a fallback identity for a Pack-authored concept that has no explicit
     *  conceptKey yet. Mirrors the frontend's own slugify() used for the same purpose. */
    private static String slugify(String name) {
        String s = name.toLowerCase(java.util.Locale.ROOT).trim().replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    /** Renders the batch-wide (not per-table) canonical concept catalog once, right after the
     *  Domain line — every table in a batch shares the same connection, hence the same pack.
     *  Retail Pack V2 / Send operationalMeaning to the LLM: {@code operational_meaning} is now
     *  rendered alongside {@code description} — previously read from the Pack but never included
     *  here, so the LLM never saw it despite pack authors writing it. Purely additive context;
     *  no scoring/ranking is introduced — the LLM still makes the sole classification decision. */
    private String renderConceptCatalog(ActivePackContext activePack) {
        StringBuilder sb = new StringBuilder();
        sb.append("Industry Pack: ").append(activePack.packKey()).append("\n");
        sb.append("Canonical Business Concepts for this industry (physical table/column names in ")
          .append("this tenant's schema will often NOT match these names or spellings — use actual ")
          .append("business meaning, not name similarity, to decide if one applies):\n");
        for (ConceptInfo c : activePack.concepts()) {
            sb.append("  - concept_key: ").append(c.conceptKey()).append(" | name: ").append(c.name());
            if (!c.aliases().isEmpty()) {
                sb.append(" | aliases: ").append(String.join(", ", c.aliases()));
            }
            if (c.description() != null && !c.description().isBlank()) {
                sb.append(" | ").append(c.description());
            }
            if (c.operationalMeaning() != null && !c.operationalMeaning().isBlank()) {
                sb.append(" | operational meaning: ").append(c.operationalMeaning());
            }
            sb.append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Validates the LLM's own {@code conceptResolution} decision against the exact concept list
     * it was offered — Java never assigns a concept_key itself, it only accepts or rejects the
     * model's answer. A concept_key the model invents (not in the offered list) is dropped,
     * never persisted — "unresolved" is always the safe outcome, matching the same acceptance-
     * boundary discipline {@code entityResolution} already enforces for {@code entityKey}.
     * {@code packKey} is written whenever a pack is active, independent of whether a concept was
     * resolved — it is not an LLM decision, purely connection metadata.
     */
    private void applyConceptResolution(Map<String, Object> entry, ActivePackContext activePack) {
        if (activePack.concepts().isEmpty()) return; // no active pack for this batch — no-op
        entry.put("packKey", activePack.packKey());

        Object raw = entry.get("conceptResolution");
        if (!(raw instanceof Map<?, ?> resolution)) return;
        Object keyObj = resolution.get("conceptKey");
        if (keyObj == null) return;
        String candidateKey = String.valueOf(keyObj).trim();
        if (candidateKey.isBlank() || "null".equalsIgnoreCase(candidateKey)) return;

        boolean valid = activePack.concepts().stream().anyMatch(c -> c.conceptKey().equals(candidateKey));
        if (valid) {
            entry.put("conceptKey", candidateKey);
        } else {
            log.warn("Discarding invalid conceptKey '{}' — not offered by pack '{}'",
                    candidateKey, activePack.packKey());
        }
    }

    private Map<String, Object> parseJson(String json) {
        try {
            String extracted = extractJson(json);
            return objectMapper.readValue(extracted, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse AI response: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }
}
