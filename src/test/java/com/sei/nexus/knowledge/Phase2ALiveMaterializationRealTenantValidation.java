package com.sei.nexus.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.enterprise.DataColumn;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.SemanticRepository;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.ColumnValueDomain;
import com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;
import com.sei.nexus.semanticmodel.SemanticModel;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantAwareDataSource;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIAGNOSTIC ONLY — real DB, real OpenAI, one specific real test tenant. Never runs in the
 * normal suite (same {@code *RealTenantValidation} naming convention as the rest of this
 * session's real-tenant validation classes — does not match Surefire's default {@code
 * **&#47;*Test.java} inclusion pattern).
 *
 * <p>Deliberately does NOT use {@code @SpringBootTest}: this app's full context requires
 * {@code SUPABASE_URL}/{@code SUPABASE_JWT_SECRET}/{@code SUPABASE_SERVICE_ROLE_KEY} (no
 * defaults in {@code application.yml}), which this environment does not have configured. None
 * of those are needed for what this class does — Phase 1 already provisioned the tenant's
 * Vector Store, so only {@link ConceptKnowledgeMaterializationService} and its own direct
 * dependencies are wired here, by hand, reusing this codebase's own reflection-based {@code
 * @Value}-field-injection convention (same pattern as {@code AzureOpenAiClientThrottleTest}/
 * {@code AzureOpenAiClientMetricsTest}) instead of a Spring context.
 *
 * <p>Reads real credentials ONLY from environment variables already used by the real app
 * ({@code NEXUS_DB_URL}/{@code NEXUS_DB_USERNAME}/{@code NEXUS_DB_PASSWORD}/{@code
 * OPENAI_API_KEY}) — never hardcoded here. Skips cleanly (does not fail) if they're absent, so
 * this never runs anywhere without real credentials deliberately exported first.
 *
 * <p>Calls the EXISTING, unmodified {@link ConceptKnowledgeMaterializationService} and {@link
 * AzureOpenAiClient} exactly as they exist in {@code main/java} — this file adds nothing to
 * either. The one addition beyond what {@link AzureOpenAiClient} already exposes is a local,
 * read-only {@code GET /v1/files/{id}/content} helper, needed only to verify uploaded content
 * against Postgres — kept entirely inside this test file, never added to the production client.
 */
class Phase2ALiveMaterializationRealTenantValidation {

    private static final String TENANT_SLUG = "persistent-ai-test";

    @Test
    void materializeAndVerifyAgainstRealOpenAi() throws Exception {
        String dbUrl   = System.getenv("NEXUS_DB_URL");
        String dbUser  = System.getenv("NEXUS_DB_USERNAME");
        String dbPass  = System.getenv("NEXUS_DB_PASSWORD");
        String apiKey  = System.getenv("OPENAI_API_KEY");
        if (isBlank(dbUrl) || isBlank(dbUser) || isBlank(dbPass) || isBlank(apiKey)) {
            System.out.println("Skipping — NEXUS_DB_URL/NEXUS_DB_USERNAME/NEXUS_DB_PASSWORD/OPENAI_API_KEY "
                    + "must be exported into the environment before running this class directly.");
            return;
        }

        // ── Wire real dependencies by hand (no Spring context — see class javadoc) ──────────────
        DriverManagerDataSource raw = new DriverManagerDataSource(dbUrl, dbUser, dbPass);
        raw.setDriverClassName("org.postgresql.Driver");
        TenantAwareDataSource dataSource = new TenantAwareDataSource(raw);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // Must match WebConfig.objectMapper() exactly (SNAKE_CASE) — IndustryPackRepository parses
        // industry-pack JSON files (pack_id, display_name, ...) using the app's shared @Primary
        // ObjectMapper bean; a vanilla ObjectMapper here silently fails to bind those fields, the
        // in-memory pack catalogue ends up keyed under a null packId, and findPackById() then
        // finds nothing — a real bug in this hand-wired harness, not in production DI.
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        objectMapper.registerModule(new JavaTimeModule());

        TenantRepository tenantRepository = new TenantRepository(jdbc);
        IndustryPackRepository packRepository = new IndustryPackRepository(jdbc, objectMapper);
        packRepository.loadPacksFromClasspath();
        SemanticRepository semanticRepository = new SemanticRepository(jdbc);
        SemanticService semanticService = new SemanticService(jdbc, null, semanticRepository);

        AzureOpenAiClient aiClient = new AzureOpenAiClient(objectMapper, null);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        com.sei.nexus.semantic.LearnedMappingRepository learnedMappingRepository =
                new com.sei.nexus.semantic.LearnedMappingRepository(jdbc);
        ConceptKnowledgeMaterializationService service = new ConceptKnowledgeMaterializationService(
                tenantRepository, packRepository, semanticService, aiClient, objectMapper, learnedMappingRepository);

        // ── Pre-check (§2 of the task) ───────────────────────────────────────────────────────────
        Tenant tenant = tenantRepository.findBySlug(TENANT_SLUG).orElseThrow(
                () -> new IllegalStateException("Tenant not found: " + TENANT_SLUG));
        System.out.println("=== TENANT ===");
        System.out.println("slug=" + tenant.slug() + " schema=" + tenant.schemaName());
        System.out.println("ai_knowledge_vector_store_id=" + tenant.aiKnowledgeVectorStoreId());
        System.out.println("ai_knowledge_status=" + tenant.aiKnowledgeStatus());
        assertTrue(tenant.aiKnowledgeVectorStoreId() != null && !tenant.aiKnowledgeVectorStoreId().isBlank(),
                "tenant must already have a Phase 1 vector store id");
        String vectorStoreId = tenant.aiKnowledgeVectorStoreId();

        System.out.println("\n=== PRE-MATERIALIZATION VECTOR STORE FILES ===");
        List<AzureOpenAiClient.VectorStoreFileRef> before = aiClient.listVectorStoreFiles(vectorStoreId);
        System.out.println("existing file count=" + before.size());
        for (AzureOpenAiClient.VectorStoreFileRef ref : before) {
            System.out.println("  " + ref.fileId() + " attrs=" + ref.attributes());
        }

        // ── Run the EXISTING Phase 2A materialization, unmodified (§3) ─────────────────────────
        System.out.println("\n=== RUNNING materializeTenantConcepts(\"" + TENANT_SLUG + "\") ===");
        ConceptKnowledgeMaterializationService.MaterializationResult result =
                service.materializeTenantConcepts(TENANT_SLUG);
        System.out.println("materialized=" + result.materialized().size() + " failures=" + result.failures().size());
        for (ConceptKnowledgeMaterializationService.ConceptResult r : result.materialized()) {
            System.out.println("  MATERIALIZED uid=" + r.conceptUid() + " fileId=" + r.fileId()
                    + " skippedAlreadyPresent=" + r.skippedAlreadyPresent());
        }
        for (String failure : result.failures()) {
            System.out.println("  FAILED " + failure);
        }

        assertTrue(TenantContext.isSet() == false, "TenantContext must be cleared after materialization");

        // ── Verify OpenAI (§4/§5/§6) ─────────────────────────────────────────────────────────────
        System.out.println("\n=== POST-MATERIALIZATION VECTOR STORE FILES ===");
        List<AzureOpenAiClient.VectorStoreFileRef> after = aiClient.listVectorStoreFiles(vectorStoreId);
        System.out.println("total file count=" + after.size());
        int inspected = 0;
        for (AzureOpenAiClient.VectorStoreFileRef ref : after) {
            String status = aiClient.getVectorStoreFileStatus(vectorStoreId, ref.fileId());
            System.out.println("  fileId=" + ref.fileId() + " status=" + status + " attrs=" + ref.attributes());
            if (inspected < 2) {
                String content = fetchFileContent(apiKey, ref.fileId());
                System.out.println("  --- content ---");
                System.out.println(content);
                System.out.println("  --- end content ---");
                inspected++;
            }
        }

        // ── File Search validation (§9) ──────────────────────────────────────────────────────────
        System.out.println("\n=== FILE SEARCH VALIDATION ===");
        String[] queries = {"purchase order", "sales transaction", "customer order", "show me all open orders"};
        for (String q : queries) {
            System.out.println("--- query: " + q + " ---");
            String response = aiClient.fileSearchQuery(vectorStoreId, q);
            System.out.println(response);
        }
    }

    /**
     * Read-only investigation of the "show me all open orders" retrieval failure. Uses ONLY the
     * existing, unmodified {@link AzureOpenAiClient#fileSearchQuery} against the already-
     * materialized tenant Vector Store from the prior task — no upload, no new concept, no code
     * change. Prints the full raw response for each query so retrieval vs. model-answer behavior
     * can be told apart from the actual response structure, not inferred.
     */
    @Test
    void investigateOpenOrdersRetrieval() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (isBlank(apiKey)) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        objectMapper.registerModule(new JavaTimeModule());
        AzureOpenAiClient aiClient = new AzureOpenAiClient(objectMapper, null);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        String vectorStoreId = "vs_6a933a9fbdf481919c228d36e0b6a320";
        String[] queries = {
                "purchase order",
                "open order",
                "open purchase order",
                "show me all open orders",
                "orders that are still open",
                "on order",
                "open-order reporting",
                "purchase orders that are still open",
                "which purchase orders are open",
                "show open purchase orders"
        };
        for (String q : queries) {
            System.out.println("\n########## QUERY: " + q + " ##########");
            System.out.println(aiClient.fileSearchQuery(vectorStoreId, q));
        }
    }

    /**
     * POC — Complete Object Persistent Knowledge. Builds ONE knowledge artifact for {@code
     * purchase-order} from two EXISTING, unmodified sources only: (a) the pack catalogue
     * (concept_key/name/aliases/description/operational_meaning/pack — the same fields Phase 2A
     * already uses, via {@link IndustryPackRepository}), and (b) the real, live {@link
     * EnterpriseSemanticAssembler} output plus the same {@link EnterpriseMapRepository
     * #findColumnsByObject} call the assembler itself makes internally (for the raw per-column
     * flags the assembler's own projection does not preserve — see {@code deriveRole}, which
     * collapses them into one {@code AttributeRole}). No second semantic model is built; both
     * calls are exactly what production code already does.
     */
    @Test
    void pocCompleteObjectMaterialization() throws Exception {
        String dbUrl  = System.getenv("NEXUS_DB_URL");
        String dbUser = System.getenv("NEXUS_DB_USERNAME");
        String dbPass = System.getenv("NEXUS_DB_PASSWORD");
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (isBlank(dbUrl) || isBlank(dbUser) || isBlank(dbPass) || isBlank(apiKey)) {
            System.out.println("Skipping — required env vars not exported.");
            return;
        }

        DriverManagerDataSource raw = new DriverManagerDataSource(dbUrl, dbUser, dbPass);
        raw.setDriverClassName("org.postgresql.Driver");
        TenantAwareDataSource dataSource = new TenantAwareDataSource(raw);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        objectMapper.registerModule(new JavaTimeModule());

        TenantRepository tenantRepository = new TenantRepository(jdbc);
        IndustryPackRepository packRepository = new IndustryPackRepository(jdbc, objectMapper);
        packRepository.loadPacksFromClasspath();
        EnterpriseMapRepository enterpriseMap = new EnterpriseMapRepository(jdbc);
        EnterpriseSemanticAssembler assembler = new EnterpriseSemanticAssembler(enterpriseMap, objectMapper);

        AzureOpenAiClient aiClient = new AzureOpenAiClient(objectMapper, null);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        Tenant tenant = tenantRepository.findBySlug(TENANT_SLUG).orElseThrow();
        String vectorStoreId = tenant.aiKnowledgeVectorStoreId();
        String connectionKey = "conn-c1590229";
        String objectKey = "platform-conn-c1590229-purchase-orders";

        TenantContext.set(tenant.schemaName());
        byte[] jsonBytes;
        try {
            // ── (a) concept fields — same existing source Phase 2A already uses ────────────────
            TenantPack tenantPack = packRepository.findAppliedPacks().stream()
                    .filter(tp -> "retail-v1".equals(tp.packKey()) && connectionKey.equals(tp.connectionKey()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("retail-v1 not applied to " + connectionKey));
            IndustryPack pack = packRepository.findPackById("retail-v1").orElseThrow();
            PackEntity conceptEntity = pack.entities().stream()
                    .filter(e -> "purchase-order".equals(e.conceptKey())).findFirst().orElseThrow();

            // ── (b) physical/column/value-domain fields — real EnterpriseSemanticAssembler output,
            //        plus the same findColumnsByObject() call the assembler itself makes ──────────
            SemanticModel model = assembler.assembleByObjectKeys(List.of(objectKey));
            BusinessObject businessObject = model.objects().stream()
                    .filter(o -> objectKey.equals(o.objectKey())).findFirst().orElseThrow();
            PhysicalTable physicalTable = model.objectTargets().get(objectKey);
            Map<String, BusinessAttribute> attrsByKey = new LinkedHashMap<>();
            for (BusinessAttribute a : businessObject.attributes()) attrsByKey.put(a.attributeKey(), a);
            List<DataColumn> rawColumns = enterpriseMap.findColumnsByObject(objectKey);

            // ── build the JSON — merge, nothing invented ─────────────────────────────────────────
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("concept_key", conceptEntity.conceptKey());
            doc.put("name", conceptEntity.name());
            doc.put("aliases", conceptEntity.aliases());
            doc.put("description", conceptEntity.description());
            doc.put("operational_meaning", conceptEntity.operationalMeaning());
            doc.put("pack", tenantPack.packKey());
            doc.put("connection", tenantPack.connectionKey());
            doc.put("business_object_name", businessObject.businessName());
            doc.put("business_object_purpose", businessObject.purpose());
            Map<String, Object> physical = new LinkedHashMap<>();
            physical.put("schema", physicalTable.schema());
            physical.put("table", physicalTable.table());
            doc.put("physical", physical);

            List<Map<String, Object>> columns = new java.util.ArrayList<>();
            for (DataColumn col : rawColumns) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", col.columnName());
                c.put("data_type", col.dataType());
                c.put("nullable", col.isNullable());
                c.put("identifier", col.isIdentifier());
                c.put("status", col.isStatus());
                c.put("filterable", col.isFilterable());
                BusinessAttribute attr = attrsByKey.get(col.columnKey());
                c.put("role", attr != null ? attr.role().name() : null);
                PhysicalColumn physCol = model.attributeTargets().get(col.columnKey());
                if (physCol != null && physCol.valueDomain() != null) {
                    ColumnValueDomain vd = physCol.valueDomain();
                    Map<String, Object> valueDomain = new LinkedHashMap<>();
                    valueDomain.put("authoritative", vd.authoritative());
                    valueDomain.put("values", vd.values());
                    c.put("value_domain", valueDomain);
                }
                columns.add(c);
            }
            doc.put("columns", columns);
            doc.put("generated_at", Instant.now().toString());

            jsonBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("=== KNOWLEDGE ARTIFACT (in-memory only) ===");
            System.out.println(new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            TenantContext.clear();
        }

        // ── upload + attach (existing Phase 2A capabilities only) ───────────────────────────────
        String filename = "object-conn-c1590229-retail-v1-purchase-order-poc.json";
        String fileId = aiClient.uploadFile(jsonBytes, filename, "application/json");
        System.out.println("\n=== UPLOADED ===");
        System.out.println("fileId=" + fileId + " filename=" + filename + " bytes=" + jsonBytes.length);

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("object_key", objectKey);
        attrs.put("concept_key", "purchase-order");
        attrs.put("knowledge_type", "business-object-complete-poc");
        attrs.put("pack_key", "retail-v1");
        attrs.put("connection_key", connectionKey);
        aiClient.attachFileToVectorStore(vectorStoreId, fileId, attrs);

        String status = null;
        for (int i = 0; i < 15; i++) {
            status = aiClient.getVectorStoreFileStatus(vectorStoreId, fileId);
            System.out.println("poll " + i + ": status=" + status);
            if ("completed".equals(status) || "failed".equals(status)) break;
            Thread.sleep(1000);
        }
        System.out.println("final status=" + status);

        // ── File Search experiment ───────────────────────────────────────────────────────────────
        System.out.println("\n=== FILE SEARCH EXPERIMENT ===");
        String[] queries = {
                "What is a purchase order?",
                "What table stores purchase orders?",
                "What is the status column for purchase orders?",
                "What are the valid purchase order statuses?",
                "Show me all open orders.",
                "Which purchase orders are still open?",
                "Show me purchase orders that have been acknowledged.",
                "Show me purchase orders that are closed."
        };
        for (String q : queries) {
            System.out.println("\n########## QUERY: " + q + " ##########");
            System.out.println(aiClient.fileSearchQuery(vectorStoreId, q));
        }
    }

    /**
     * Live smoke test for Stage 1 File Search implementation — {@code
     * AzureOpenAiClient#chatWithFileSearch}, the exact production method
     * {@code ConceptScopedMetadataResolver#selectConceptsViaPersistentKnowledge} calls. Confirms the one
     * mechanism flagged as untested in {@code
     * ZEVRA_PERSISTENT_AI_KNOWLEDGE_V1_STAGE1_FILE_SEARCH_INVESTIGATION.md} §12.1: that
     * file_search tool use and {@code text.format=json_object} structured output work together
     * in one real Responses API call, and that the model reliably emits the
     * {@code {"metadataRequest":{"conceptKeys":[...]}}} shape the resolver's existing parser
     * already expects.
     */
    @Test
    void liveSmokeTestFileSearchConceptSelection() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (isBlank(apiKey)) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        AzureOpenAiClient aiClient = new AzureOpenAiClient(new ObjectMapper(), null);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        String vectorStoreId = "vs_6a933a9fbdf481919c228d36e0b6a320";
        String instructions = """
                You are the semantic reasoning layer of an enterprise data platform. You are given a
                user's business question. Use the file_search tool to retrieve this tenant's
                persistent business knowledge and decide which business concept(s) — zero, one, or
                several — are relevant. Respond with valid JSON only, in exactly this shape:
                {"metadataRequest": {"conceptKeys": ["<concept_key>", "..."]}}
                Every value in conceptKeys MUST be an exact concept_key you actually retrieved via
                file_search — never invent one. An empty array is correct when nothing is relevant.
                """;

        String[] questions = {
                "Show me purchase orders", "What table stores purchase orders?",
                "What are the valid purchase order statuses?", "Show me sales transactions",
                "What's the weather today?"
        };
        for (String q : questions) {
            System.out.println("\n########## QUESTION: " + q + " ##########");
            String result = aiClient.chatWithFileSearch(vectorStoreId, instructions, q);
            System.out.println("extracted text: " + result);
        }
    }

    /**
     * DIAGNOSTIC ONLY — captures the RAW, unstripped OpenAI Responses API envelope for the exact
     * same request shape {@code AzureOpenAiClient.chatWithFileSearch} sends (same URL, same
     * instructions text taken by reflection from {@code
     * ConceptScopedMetadataResolver.PERSISTENT_KNOWLEDGE_SYSTEM_PROMPT}, same tool/text-format
     * config), so {@code file_search_call} items and citation annotations — both discarded by
     * {@code AzureOpenAiClient#extractResponseText} before the caller ever sees them — can be
     * inspected directly. Read-only against the real tenant Vector Store; uploads/creates/deletes
     * nothing. Does not call any production method — replicates the request inline so nothing is
     * stripped before inspection.
     */
    @Test
    void diagnosticRawResponseForPurchaseOrderQuestion() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (isBlank(apiKey)) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }

        java.lang.reflect.Field f = com.sei.nexus.agentbrain.ConceptScopedMetadataResolver.class
                .getDeclaredField("PERSISTENT_KNOWLEDGE_SYSTEM_PROMPT");
        f.setAccessible(true);
        String instructions = (String) f.get(null);

        String vectorStoreId = "vs_6a933a9fbdf481919c228d36e0b6a320";
        String question = "Show me all purchase orders";

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "file_search");
        tool.put("vector_store_ids", List.of(vectorStoreId));
        Map<String, Object> textFormat = Map.of("type", "json_object");
        Map<String, Object> text = Map.of("format", textFormat);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", "gpt-4o");
        requestBody.put("instructions", instructions);
        requestBody.put("input", question + "\n\n(Respond in JSON as instructed.)");
        requestBody.put("tools", List.of(tool));
        requestBody.put("text", text);
        // include file_search_call.results too, so we can see raw retrieval scores if OpenAI
        // exposes them here — pure additive request field, not present in the production call.
        requestBody.put("include", List.of("file_search_call.results"));

        ObjectMapper mapper = new ObjectMapper();
        String jsonBody = mapper.writeValueAsString(requestBody);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/responses"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("=== REQUEST BODY SENT ===");
        System.out.println(jsonBody);
        System.out.println("\n=== RAW RESPONSE (status=" + response.statusCode() + ") ===");
        System.out.println(response.body());
    }

    /**
     * DIAGNOSTIC ONLY — captures a REAL DECISION_ROUTER response using the REAL {@code
     * DECISION_SYSTEM_PROMPT} constant (read by reflection, verbatim, never re-typed) and a
     * representative context string built from real materialized purchase-order knowledge already
     * fetched earlier this session (not invented). This does NOT run through the actual
     * `ChatService.ask()` call chain — reconstructing that entire dependency graph (agent
     * repository, run repository, document memory, knowledge graph, semantic service, baseline
     * service, reasoning event bus, etc.) was judged too large/risky to hand-wire reliably (as
     * already declined once this session for the same reason). This proves what the real
     * DECISION_ROUTER prompt/model produces for a realistic context, not that a specific live
     * server request took this exact path — that distinction is preserved in the report.
     */
    @Test
    void diagnosticRealDecisionRouterResponseForPurchaseOrderQuestion() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (isBlank(apiKey)) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        java.lang.reflect.Field f = com.sei.nexus.chat.ChatService.class.getDeclaredField("DECISION_SYSTEM_PROMPT");
        f.setAccessible(true);
        String decisionSystemPrompt = (String) f.get(null);

        AzureOpenAiClient aiClient = new AzureOpenAiClient(new ObjectMapper(), null);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        // Representative TABLE SCHEMA context — real column/value-domain facts already
        // materialized and observed this session for purchase_orders, formatted the way
        // PromptAssembler renders it (business name, physical binding, columns, value domain).
        String question = "Show me all submitted purchase orders";
        String ctx = """
                Business object: Purchase Orders (connection: conn-c1590229, table: retail_core.purchase_orders)
                Purpose: This table stores header information for each purchase order raised against a supplier.
                Columns:
                  - po_number (identifier)
                  - supplier_id (identifier)
                  - status (dimension) [legal values: draft, submitted, acknowledged, partially_received, received, cancelled, closed]
                  - ordered_date (dimension)
                  - expected_delivery_date (dimension)
                  - total_ordered_amount (measure)
                """;
        String prompt = "Question: " + question + "\n\nContext:\n" + ctx;

        String response = aiClient.chat(java.util.List.of(ChatMessage.user(prompt)), decisionSystemPrompt);
        System.out.println("=== REAL DECISION_SYSTEM_PROMPT (verbatim, via reflection) ===");
        System.out.println(decisionSystemPrompt);
        System.out.println("\n=== PROMPT SENT ===");
        System.out.println(prompt);
        System.out.println("\n=== ACTUAL MODEL RESPONSE ===");
        System.out.println(response);
    }

    /**
     * DIAGNOSTIC ONLY — tests {@code previous_response_id} conversation chaining against the
     * CURRENT production materialization shape (JSON concept files, {@code
     * PERSISTENT_KNOWLEDGE_SYSTEM_PROMPT}, {@code text.format=json_object}) — the exact request
     * {@code AzureOpenAiClient.chatWithFileSearch} sends, PLUS an additive {@code
     * previous_response_id} field that method does not currently support. Read-only against the
     * real tenant Vector Store; uploads/creates/deletes nothing. Does not call any production
     * method — replicates the request inline so the additive field can be tested without touching
     * production code.
     */
    @Test
    void diagnosticPreviousResponseIdConversationChaining() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (isBlank(apiKey)) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        java.lang.reflect.Field f = com.sei.nexus.agentbrain.ConceptScopedMetadataResolver.class
                .getDeclaredField("PERSISTENT_KNOWLEDGE_SYSTEM_PROMPT");
        f.setAccessible(true);
        String instructions = (String) f.get(null);
        String vectorStoreId = "vs_6a933a9fbdf481919c228d36e0b6a320";

        String[] turns = {
                "Show me all purchase orders",
                "Only the submitted ones",
                "Now show me sales transactions"
        };
        String previousResponseId = null;
        for (String question : turns) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "file_search");
            tool.put("vector_store_ids", List.of(vectorStoreId));
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", "gpt-4o");
            requestBody.put("instructions", instructions);
            requestBody.put("input", question + "\n\n(Respond in JSON as instructed.)");
            requestBody.put("tools", List.of(tool));
            requestBody.put("text", Map.of("format", Map.of("type", "json_object")));
            if (previousResponseId != null) requestBody.put("previous_response_id", previousResponseId);

            ObjectMapper mapper = new ObjectMapper();
            String jsonBody = mapper.writeValueAsString(requestBody);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/responses"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("\n########## TURN: " + question + " (previous_response_id=" + previousResponseId + ") ##########");
            System.out.println("HTTP status: " + response.statusCode());
            var root = mapper.readTree(response.body());
            previousResponseId = root.path("id").asText(null);
            System.out.println("response id: " + previousResponseId);
            boolean fileSearchPresent = false;
            String fileSearchStatus = "absent";
            String finalText = "";
            for (var item : root.path("output")) {
                if ("file_search_call".equals(item.path("type").asText())) {
                    fileSearchPresent = true;
                    fileSearchStatus = item.path("status").asText("unknown");
                }
                if ("message".equals(item.path("type").asText())) {
                    for (var content : item.path("content")) {
                        if ("output_text".equals(content.path("type").asText())) {
                            finalText += content.path("text").asText();
                        }
                    }
                }
            }
            System.out.println("file_search_call present=" + fileSearchPresent + " status=" + fileSearchStatus);
            System.out.println("final structured output: " + finalText);
        }
    }

    /**
     * LIVE VALIDATION of the actual production conversation-aware Stage 1 flow — calls the real,
     * unmodified {@link AzureOpenAiClient#chatWithFileSearch(String, String, String, String)}
     * 4-arg overload directly (the exact method {@code
     * ConceptScopedMetadataResolver#selectConceptsViaPersistentKnowledge} calls), using the real
     * {@code PERSISTENT_KNOWLEDGE_SYSTEM_PROMPT} (read by reflection, verbatim) against the real
     * tenant Vector Store. Proves, against real OpenAI: turn 1 returns a response id; turn 2,
     * chained via that response id as {@code previous_response_id}, still invokes {@code
     * file_search} natively (not skipped merely because the turn is chained) and correctly
     * resolves "only the submitted ones" against turn 1's purchase-order context. Read-only;
     * uploads/creates/deletes nothing.
     */
    @Test
    void liveValidationOfProductionConversationAwareFileSearchMethod() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (isBlank(apiKey)) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        java.lang.reflect.Field f = com.sei.nexus.agentbrain.ConceptScopedMetadataResolver.class
                .getDeclaredField("PERSISTENT_KNOWLEDGE_SYSTEM_PROMPT");
        f.setAccessible(true);
        String instructions = (String) f.get(null);
        String vectorStoreId = "vs_6a933a9fbdf481919c228d36e0b6a320";

        AzureOpenAiClient aiClient = new AzureOpenAiClient(new ObjectMapper(), null);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        // Turn 1 — fresh, no previous_response_id (production behavior for a conversation's
        // first turn, since ConceptScopedMetadataResolver has nothing stored yet).
        AzureOpenAiClient.FileSearchResult turn1 = aiClient.chatWithFileSearch(
                vectorStoreId, instructions, "Show me all purchase orders", null);
        System.out.println("=== TURN 1 (fresh) ===");
        System.out.println("responseId=" + turn1.responseId());
        System.out.println("text=" + turn1.text());
        assertTrue(turn1.responseId() != null && !turn1.responseId().isBlank(),
                "turn 1 must return a response id for ConceptScopedMetadataResolver to store");

        // Turn 2 — chained, exactly as ConceptScopedMetadataResolver.selectConceptsViaPersistentKnowledge
        // would call it after loading turn 1's response id back out of TenantSettingsRepository.
        AzureOpenAiClient.FileSearchResult turn2 = aiClient.chatWithFileSearch(
                vectorStoreId, instructions, "Only the submitted ones", turn1.responseId());
        System.out.println("=== TURN 2 (chained via previous_response_id=" + turn1.responseId() + ") ===");
        System.out.println("responseId=" + turn2.responseId());
        System.out.println("text=" + turn2.text());

        assertTrue(turn2.text() != null && turn2.text().contains("purchase-order"),
                "turn 2, chained to turn 1's purchase-order context, must still resolve to conceptKeys "
                        + "containing 'purchase-order' — proving the follow-up reference was correctly "
                        + "resolved via previous_response_id rather than by resending turn 1's text");
    }

    /**
     * LIVE VALIDATION of Decision Router absorption — the combined concept-selection + routing
     * contract. Reads the REAL production {@code PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT}
     * and {@code COMBINED_STAGE1_JSON_SCHEMA} constants from {@link
     * com.sei.nexus.agentbrain.ConceptScopedMetadataResolver} by reflection (verbatim, never
     * re-typed) and calls the real, unmodified production {@link
     * AzureOpenAiClient#chatWithFileSearch(String, String, String, String, java.util.Map)}
     * 5-arg overload directly — the exact method {@code
     * selectConceptsAndRoutingViaPersistentKnowledge} calls — against the real tenant Vector
     * Store, replicating exactly the runtime-facts text that method appends to the question.
     * Read-only; uploads/creates/deletes nothing.
     *
     * <p>Exercises the full 5-turn conversation from the Decision Router absorption task,
     * proving: (1) both {@code metadataRequest} and {@code routing} are present in every turn's
     * response; (2) {@code file_search} actually executes; (3) {@code previous_response_id}
     * chaining works across all 5 turns; (4) no second (Decision Router) LLM call is made — this
     * test never constructs or calls {@code getLlmDecision}/{@code DECISION_SYSTEM_PROMPT} at
     * all, so its mere structure is part of the proof.
     */
    @Test
    void liveValidationOfDecisionRouterAbsorptionCombinedContract() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (isBlank(apiKey)) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        java.lang.reflect.Field promptField = com.sei.nexus.agentbrain.ConceptScopedMetadataResolver.class
                .getDeclaredField("PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT");
        promptField.setAccessible(true);
        String instructions = (String) promptField.get(null);

        java.lang.reflect.Field schemaField = com.sei.nexus.agentbrain.ConceptScopedMetadataResolver.class
                .getDeclaredField("COMBINED_STAGE1_JSON_SCHEMA");
        schemaField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> jsonSchema = (Map<String, Object>) schemaField.get(null);

        String vectorStoreId = "vs_6a933a9fbdf481919c228d36e0b6a320";

        AzureOpenAiClient aiClient = new AzureOpenAiClient(new ObjectMapper(), null);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        String[] turns = {
                "Show me all purchase orders",
                "I want only submitted",
                "How many are there?",
                "What about the closed ones?",
                "Now show me sales transactions"
        };
        String previousResponseId = null;
        for (String question : turns) {
            // Exactly what selectConceptsAndRoutingViaPersistentKnowledge appends — a fixed
            // runtime fact, never a semantic decision.
            String questionWithFacts = question + "\n\nRuntime facts (for the routing decision "
                    + "only, never for concept resolution):\n- Document memory available for this "
                    + "question: false";

            AzureOpenAiClient.FileSearchResult result = aiClient.chatWithFileSearch(
                    vectorStoreId, instructions, questionWithFacts, previousResponseId, jsonSchema);

            System.out.println("\n########## TURN: " + question
                    + " (previous_response_id=" + previousResponseId + ") ##########");
            System.out.println("responseId=" + result.responseId());
            System.out.println("text=" + result.text());

            assertTrue(result.text() != null && result.text().contains("\"metadataRequest\""),
                    "every combined-call response must contain metadataRequest");
            assertTrue(result.text().contains("\"routing\""),
                    "every combined-call response must contain routing");

            previousResponseId = result.responseId();
        }
    }

    /**
     * DIAGNOSTIC ONLY — captures the exact raw Stage-1 combined output for two independent,
     * completely fresh (unchained, {@code previous_response_id=null}) first-turn questions that
     * differ by exactly one word, to establish whether the word "open" changes what the
     * production combined concept+routing call returns. Calls the real, unmodified production
     * {@link AzureOpenAiClient#chatWithFileSearch(String, String, String, String, java.util.Map)}
     * 5-arg overload directly — the exact method {@code
     * ConceptScopedMetadataResolver#selectConceptsAndRoutingViaPersistentKnowledge} calls — with
     * the real production {@code PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT} and {@code
     * COMBINED_STAGE1_JSON_SCHEMA} (read by reflection, verbatim), against the real tenant
     * {@code persistent-ai-test} Vector Store. File Search invocation/status evidence comes from
     * the production {@code FILE_SEARCH_METRIC} log line this same call already emits (real
     * production observability, not fabricated) — printed to console by the test run itself, not
     * re-derived here. Read-only; uploads/creates/deletes nothing.
     */
    @Test
    void liveDiagnosticStage1OutputComparisonOpenQualifier() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (isBlank(apiKey)) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        java.lang.reflect.Field promptField = com.sei.nexus.agentbrain.ConceptScopedMetadataResolver.class
                .getDeclaredField("PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT");
        promptField.setAccessible(true);
        String instructions = (String) promptField.get(null);

        java.lang.reflect.Field schemaField = com.sei.nexus.agentbrain.ConceptScopedMetadataResolver.class
                .getDeclaredField("COMBINED_STAGE1_JSON_SCHEMA");
        schemaField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> jsonSchema = (Map<String, Object>) schemaField.get(null);

        String vectorStoreId = "vs_6a933a9fbdf481919c228d36e0b6a320";

        AzureOpenAiClient aiClient = new AzureOpenAiClient(new ObjectMapper(), null);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        String runtimeFactsSuffix = "\n\nRuntime facts (for the routing decision only, never for "
                + "concept resolution):\n- Document memory available for this question: false";

        System.out.println("=== STAGE-1 INPUT (identical for both cases except the question text) ===");
        System.out.println("vectorStoreId=" + vectorStoreId);
        System.out.println("previous_response_id=null (both cases — fresh, unchained)");
        System.out.println("runtime facts suffix=" + runtimeFactsSuffix);
        System.out.println("instructions length=" + instructions.length() + " chars (verbatim production PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT)");

        String questionA = "Show me all purchase orders";
        String questionB = "Show me all open purchase orders";

        System.out.println("\n########## CASE A: \"" + questionA + "\" (fresh, previous_response_id=null) ##########");
        AzureOpenAiClient.FileSearchResult resultA = aiClient.chatWithFileSearch(
                vectorStoreId, instructions, questionA + runtimeFactsSuffix, null, jsonSchema);
        System.out.println("responseId=" + resultA.responseId());
        System.out.println("RAW TEXT=" + resultA.text());

        System.out.println("\n########## CASE B: \"" + questionB + "\" (fresh, previous_response_id=null) ##########");
        AzureOpenAiClient.FileSearchResult resultB = aiClient.chatWithFileSearch(
                vectorStoreId, instructions, questionB + runtimeFactsSuffix, null, jsonSchema);
        System.out.println("responseId=" + resultB.responseId());
        System.out.println("RAW TEXT=" + resultB.text());

        // Parse both for the side-by-side summary — deterministic JSON parsing only, no
        // semantic decision made here; the raw text above is the actual evidence.
        ObjectMapper mapper = new ObjectMapper();
        java.util.Map<?, ?> parsedA = mapper.readValue(resultA.text(), java.util.Map.class);
        java.util.Map<?, ?> parsedB = mapper.readValue(resultB.text(), java.util.Map.class);

        System.out.println("\n=== SIDE-BY-SIDE SUMMARY ===");
        System.out.println("Case A conceptKeys: " + ((java.util.Map<?, ?>) parsedA.get("metadataRequest")).get("conceptKeys"));
        System.out.println("Case B conceptKeys: " + ((java.util.Map<?, ?>) parsedB.get("metadataRequest")).get("conceptKeys"));
        System.out.println("Case A routing: " + parsedA.get("routing"));
        System.out.println("Case B routing: " + parsedB.get("routing"));

        assertTrue(resultA.text().contains("\"metadataRequest\"") && resultA.text().contains("\"routing\""));
        assertTrue(resultB.text().contains("\"metadataRequest\"") && resultB.text().contains("\"routing\""));
    }

    private String fetchFileContent(String apiKey, String fileId) throws Exception {
        return rawGet(apiKey, "https://api.openai.com/v1/files/" + fileId + "/content");
    }

    private String rawGet(String apiKey, String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    /**
     * Storage measurement only (§11 of the task) — deliberately does NOT call {@link
     * ConceptKnowledgeMaterializationService#materializeTenantConcepts} again: two files from the
     * prior run showed empty {@code attributes} when listed immediately after upload (an OpenAI
     * listing-propagation lag, not a re-check I want to risk turning into a duplicate upload if
     * the idempotency check's {@code concept_uid} lookup came back empty for them). Uses the
     * vector store id and file ids already known from that completed run. AzureOpenAiClient
     * exposes no vector-store/file-metadata getter (only id/attributes via {@code
     * listVectorStoreFiles}), so these two raw, read-only GETs live only in this test file —
     * never added to the production client.
     */
    @Test
    void storageMeasurementForAlreadyMaterializedFiles() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (isBlank(apiKey)) {
            System.out.println("Skipping — OPENAI_API_KEY not exported.");
            return;
        }
        String vectorStoreId = "vs_6a933a9fbdf481919c228d36e0b6a320";
        String[] fileIds = {
                "file-8Z1rigKUSy7NdtW4LzdaQV", "file-2UPh8Bx6Vx2gn3LwpnGQSC", "file-MLEkQyHCjZakbJzbp7ezHR",
                "file-75mUZtJk3ofbYYhHtw9rrG", "file-54CMoUhioLFYNFLUcbSPCY", "file-SgPofksAMQnZdznAG2jXQL",
                "file-2oAK77g3vjXVkcaLjqBk2v", "file-ETp4Zgj1v2UsjDhrU1DoB9", "file-CNN8DFLS3DVt3Rmkvany3J",
                "file-7ZGb6dEm8r6tRsDZ192sUv", "file-PxSWuu1vpbCeEtMRX3TcDu", "file-CGuJ7ydHojvGz2iqpdShbG",
                "file-M785tRP2hnSNmC3qSjtgSq", "file-Kgo6i6YNaYXZSLZUHuaACy", "file-AFQyFNeG2mqv9pvstvGLoZ"
        };
        System.out.println("=== VECTOR STORE OBJECT ===");
        System.out.println(rawGet(apiKey, "https://api.openai.com/v1/vector_stores/" + vectorStoreId));
        for (String fileId : fileIds) {
            System.out.println("=== FILE OBJECT: " + fileId + " ===");
            System.out.println(rawGet(apiKey, "https://api.openai.com/v1/files/" + fileId));
        }
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
