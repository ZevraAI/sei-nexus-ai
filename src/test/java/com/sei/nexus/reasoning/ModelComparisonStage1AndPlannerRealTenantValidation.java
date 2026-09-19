package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sei.nexus.agentbrain.AgentBrain;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.ExecutionContractBuilder;
import com.sei.nexus.agentbrain.PromptAssembler;
import com.sei.nexus.agentbrain.PromptContext;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.ResolvedBusinessModel;
import com.sei.nexus.agentbrain.ConceptScopedMetadataResolver;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.onboarding.TenantSettingsRepository;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.semantic.BusinessLanguageResolver;
import com.sei.nexus.semantic.SemanticRepository;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantAwareDataSource;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import com.sei.nexus.usage.UsageService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * PHASE 2 — MODEL EVALUATION (gpt-4o vs gpt-4.1), real tenant {@code persistent-ai-test}, real
 * connection {@code conn-c1590229}, real OpenAI. DIAGNOSTIC ONLY — same {@code
 * *RealTenantValidation} naming convention, excluded from Surefire's default run, makes NO
 * production code changes and writes nothing (Stage 1's own {@code previous_response_id}
 * persistence is skipped here: a fresh, never-reused {@code conversationId} is used per
 * question/model combination, so no tenant setting is read or written).
 *
 * <p>Adapted directly from {@link OpenPurchaseOrdersPlannerRealTenantValidation} (same real
 * wiring: {@link AgentBrain} → {@link ConceptScopedMetadataResolver} (Stage 1, combined concept +
 * routing) → {@link ExecutionContractBuilder} → {@link PromptContextBuilder} → {@link
 * PromptAssembler} → {@link ReasoningPlanner}), with the model as the ONLY experimental variable:
 * every other input (question, connection, domain, schema construction) is identical between the
 * two runs. Covers historical failure modes #1 ("show me all open orders"), #4 (learned "open
 * purchase orders" business knowledge — "open" is a learned/enum status concept in this tenant,
 * not a literal column value), and #5 (concept-scoped metadata narrowing — Stage 1's resolved
 * conceptKeys are what narrow the schema Planner receives).
 */
class ModelComparisonStage1AndPlannerRealTenantValidation {

    private static final String TENANT_SLUG = "persistent-ai-test";
    private static final String CONNECTION_KEY = "conn-c1590229";
    private static final List<String> MODELS = List.of("gpt-4o", "gpt-4.1");

    /** Captures every usage row locally instead of writing to the real DB. */
    static class RecordingUsageService extends UsageService {
        record Call(String model, int promptTokens, int completionTokens, int cachedTokens, String callType) {}
        final List<Call> calls = new ArrayList<>();
        RecordingUsageService() { super(null); }
        @Override
        public void record(String model, int promptTokens, int completionTokens, int cachedTokens, String callType) {
            calls.add(new Call(model, promptTokens, completionTokens, cachedTokens, callType));
        }
    }

    @Test
    void compareModelsAcrossOpenPurchaseOrdersScenarios() throws Exception {
        String dbUrl  = System.getenv("NEXUS_DB_URL");
        String dbUser = System.getenv("NEXUS_DB_USERNAME");
        String dbPass = System.getenv("NEXUS_DB_PASSWORD");
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (isBlank(dbUrl) || isBlank(dbUser) || isBlank(dbPass) || isBlank(apiKey)) {
            System.out.println("Skipping — NEXUS_DB_URL/NEXUS_DB_USERNAME/NEXUS_DB_PASSWORD/OPENAI_API_KEY required.");
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
        SemanticRepository semanticRepository = new SemanticRepository(jdbc);
        SemanticService semanticService = new SemanticService(jdbc, null, semanticRepository);
        TenantSettingsRepository tenantSettingsRepository = new TenantSettingsRepository(jdbc);
        EnterpriseMapRepository enterpriseMapRepository = new EnterpriseMapRepository(jdbc);
        ConnectionRepository connectionRepository = new ConnectionRepository(jdbc);

        Tenant tenant = tenantRepository.findBySlug(TENANT_SLUG).orElseThrow();
        String schema = tenant.schemaName();

        String[] questions = {
                "Show me all purchase orders",
                "Show me all submitted purchase orders",
                "Show me all open purchase orders"
        };

        System.out.println("\n########## PHASE 2 MODEL COMPARISON — Stage 1 + Planner (real tenant) ##########");

        for (String model : MODELS) {
            System.out.println("\n=================== MODEL: " + model + " ===================");

            RecordingUsageService usage = new RecordingUsageService();
            AzureOpenAiClient aiClient = new AzureOpenAiClient(objectMapper, usage);
            setField(aiClient, "apiKey", apiKey);
            setField(aiClient, "chatModel", model);
            setField(aiClient, "maxConcurrentCalls", 6);
            Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
            initThrottle.setAccessible(true);
            initThrottle.invoke(aiClient);

            ConceptScopedMetadataResolver conceptResolver = new ConceptScopedMetadataResolver(
                    packRepository, semanticService, aiClient, objectMapper, tenantSettingsRepository, tenantRepository);
            EnterpriseSemanticAssembler assembler = new EnterpriseSemanticAssembler(enterpriseMapRepository);
            BusinessLanguageResolver blr = new BusinessLanguageResolver(semanticRepository, enterpriseMapRepository, objectMapper);
            AgentBrain agentBrain = new AgentBrain(assembler, blr, conceptResolver);

            ExecutionContractBuilder executionContractBuilder = new ExecutionContractBuilder(new SqlTableReferenceExtractor());
            PromptContextBuilder promptContextBuilder = new PromptContextBuilder();
            PromptAssembler promptAssembler = new PromptAssembler();
            ReasoningPlanner planner = new ReasoningPlanner(aiClient, objectMapper);

            try {
                TenantContext.set(schema);
                List<String> connKeys = List.of(CONNECTION_KEY);
                List<String> domainKeys = List.of("PLATFORM");

                int turn = 0;
                for (String question : questions) {
                    turn++;
                    String conversationId = "conv-eval-" + model.replace('.', '_') + "-" + turn;
                    long t0 = System.nanoTime();

                    ResolvedBusinessModel resolvedModel = agentBrain.resolve(
                            "data-analyst", connKeys, domainKeys, question, conversationId, false);

                    System.out.println("\n--- \"" + question + "\" [" + model + "] ---");
                    System.out.println("conceptScoped=" + resolvedModel.conceptScoped());
                    System.out.println("objects=" + resolvedModel.objects().stream().map(o -> o.objectKey()).toList());
                    System.out.println("routingDecision=" + resolvedModel.routingDecision());
                    System.out.println("resolutions=" + resolvedModel.resolution().resolutions());

                    ExecutionContract contract = executionContractBuilder.compile(resolvedModel);
                    PromptContext promptContext = promptContextBuilder.build(contract);

                    String resolutionsBlock = resolvedModel.resolution() != null && !resolvedModel.resolution().isEmpty()
                            ? resolvedModel.resolution().renderPromptBlock() : "";
                    String tableSchemaBlock;
                    if (!promptContext.isEmpty()) {
                        tableSchemaBlock = promptAssembler.assemble(promptContext,
                                new PromptAssembler.RenderOptions(true, true, true, 6000));
                    } else {
                        tableSchemaBlock = "=== TABLE SCHEMA ===\n"
                                + "NO LIVE DATA SOURCES CONFIGURED. Do NOT generate SQL or use QUERY_LIVE_DATA.\n"
                                + "No memory documents either — use KNOWLEDGE_GAP.\n\n";
                    }
                    String schemaCtx = resolutionsBlock + "\n" + tableSchemaBlock;

                    ReasoningPlanner.StepPlan plan = planner.nextStep(question, schemaCtx, new EvidenceStore());
                    long latencyMs = (System.nanoTime() - t0) / 1_000_000;

                    if (plan == null) {
                        System.out.println("PLANNER OUTPUT: null (done/no further queries)  latencyMs=" + latencyMs);
                    } else if (plan.isClarification()) {
                        System.out.println("PLANNER OUTPUT: CLARIFICATION — " + plan.clarificationQuestion()
                                + "  latencyMs=" + latencyMs);
                    } else {
                        System.out.println("PLANNER OUTPUT: connectionKey=" + plan.connectionKey()
                                + " sql=" + plan.sql() + " objectKeys=" + plan.objectKeys()
                                + "  latencyMs=" + latencyMs);
                        var conn = connectionRepository.findByKeyOrName(plan.connectionKey());
                        System.out.println("  connectionRepository present=" + conn.isPresent());
                    }
                }
            } finally {
                TenantContext.clear();
            }

            System.out.println("\n--- USAGE (" + model + ") ---");
            for (var call : usage.calls) {
                System.out.println("  callType=" + call.callType() + " model=" + call.model()
                        + " promptTokens=" + call.promptTokens() + " cachedTokens=" + call.cachedTokens()
                        + " completionTokens=" + call.completionTokens());
            }
        }
    }

    /**
     * PHASE 3 — Stage 1 smoke test proving it still uses gpt-4o (via {@code chatModel}, untouched
     * by the Phase 3 migration) and still invokes real File Search, running the same integrated
     * pipeline with the REAL deployed mixed configuration (Stage 1 on {@code chatModel}=gpt-4o,
     * Planner on {@code plannerModel}=gpt-4.1) so both halves of the intended matrix are exercised
     * together in one real, minimal-cost run (a single question, not the full 3-question sweep).
     */
    @org.junit.jupiter.api.Test
    void stage1SmokeTestUnderDeployedMixedConfiguration() throws Exception {
        String dbUrl  = System.getenv("NEXUS_DB_URL");
        String dbUser = System.getenv("NEXUS_DB_USERNAME");
        String dbPass = System.getenv("NEXUS_DB_PASSWORD");
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (isBlank(dbUrl) || isBlank(dbUser) || isBlank(dbPass) || isBlank(apiKey)) {
            System.out.println("Skipping — NEXUS_DB_URL/NEXUS_DB_USERNAME/NEXUS_DB_PASSWORD/OPENAI_API_KEY required.");
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
        SemanticRepository semanticRepository = new SemanticRepository(jdbc);
        SemanticService semanticService = new SemanticService(jdbc, null, semanticRepository);
        TenantSettingsRepository tenantSettingsRepository = new TenantSettingsRepository(jdbc);
        EnterpriseMapRepository enterpriseMapRepository = new EnterpriseMapRepository(jdbc);

        Tenant tenant = tenantRepository.findBySlug(TENANT_SLUG).orElseThrow();

        RecordingUsageService usage = new RecordingUsageService();
        AzureOpenAiClient aiClient = new AzureOpenAiClient(objectMapper, usage);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
        setField(aiClient, "plannerModel", "gpt-4.1");
        setField(aiClient, "maxConcurrentCalls", 6);
        Method initThrottle = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        initThrottle.setAccessible(true);
        initThrottle.invoke(aiClient);

        ConceptScopedMetadataResolver conceptResolver = new ConceptScopedMetadataResolver(
                packRepository, semanticService, aiClient, objectMapper, tenantSettingsRepository, tenantRepository);
        EnterpriseSemanticAssembler assembler = new EnterpriseSemanticAssembler(enterpriseMapRepository);
        BusinessLanguageResolver blr = new BusinessLanguageResolver(semanticRepository, enterpriseMapRepository, objectMapper);
        AgentBrain agentBrain = new AgentBrain(assembler, blr, conceptResolver);
        ExecutionContractBuilder executionContractBuilder = new ExecutionContractBuilder(new SqlTableReferenceExtractor());
        PromptContextBuilder promptContextBuilder = new PromptContextBuilder();
        PromptAssembler promptAssembler = new PromptAssembler();
        ReasoningPlanner planner = new ReasoningPlanner(aiClient, objectMapper);

        System.out.println("\n########## PHASE 3 — STAGE 1 SMOKE TEST (deployed mixed configuration) ##########");
        try {
            TenantContext.set(tenant.schemaName());
            String question = "Show me all purchase orders";
            ResolvedBusinessModel model = agentBrain.resolve("data-analyst", List.of(CONNECTION_KEY),
                    List.of("PLATFORM"), question, "conv-phase3-smoke-1", false);

            System.out.println("objects=" + model.objects().stream().map(o -> o.objectKey()).toList());
            System.out.println("routingDecision=" + model.routingDecision());

            ExecutionContract contract = executionContractBuilder.compile(model);
            PromptContext promptContext = promptContextBuilder.build(contract);
            String resolutionsBlock = model.resolution() != null && !model.resolution().isEmpty()
                    ? model.resolution().renderPromptBlock() : "";
            String tableSchemaBlock = !promptContext.isEmpty()
                    ? promptAssembler.assemble(promptContext, new PromptAssembler.RenderOptions(true, true, true, 6000))
                    : "=== TABLE SCHEMA ===\nNO LIVE DATA SOURCES CONFIGURED.\n";
            String schemaCtx = resolutionsBlock + "\n" + tableSchemaBlock;

            ReasoningPlanner.StepPlan plan = planner.nextStep(question, schemaCtx, new EvidenceStore());
            System.out.println("PLANNER OUTPUT: " + (plan == null ? "null" : plan.sql()));
        } finally {
            TenantContext.clear();
        }

        System.out.println("\n--- USAGE (deployed mixed config) ---");
        for (var call : usage.calls) {
            System.out.println("  callType=" + call.callType() + " model=" + call.model()
                    + " promptTokens=" + call.promptTokens() + " cachedTokens=" + call.cachedTokens()
                    + " completionTokens=" + call.completionTokens());
            if ("STAGE1_FILE_SEARCH_CONCEPT_AND_ROUTING".equals(call.callType())) {
                org.junit.jupiter.api.Assertions.assertEquals("gpt-4o", call.model(),
                        "Stage 1 must remain on gpt-4o — explicitly out of scope for the Phase 3 migration");
            }
            if ("PLANNER".equals(call.callType())) {
                org.junit.jupiter.api.Assertions.assertEquals("gpt-4.1", call.model(),
                        "Planner must use gpt-4.1 under the deployed Phase 3 configuration");
            }
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
