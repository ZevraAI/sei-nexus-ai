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
import com.sei.nexus.agentbrain.ConceptScopedMetadataResolver;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantAwareDataSource;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * DIAGNOSTIC ONLY — real DB, real OpenAI, real tenant {@code persistent-ai-test}. Excluded from
 * Surefire's default {@code **&#47;*Test.java} run (same {@code *RealTenantValidation} naming
 * convention as this session's other real-tenant diagnostics).
 *
 * <p>Hand-wires the REAL, unmodified production classes — {@link BusinessLanguageResolver},
 * {@link ConceptScopedMetadataResolver} (the real combined Persistent Knowledge Stage 1),
 * {@link AgentBrain}, {@link ExecutionContractBuilder}, {@link PromptContextBuilder}, {@link
 * PromptAssembler}, {@link ReasoningPlanner} — against the real tenant database and a real
 * OpenAI account, exactly as {@code ChatService.ask()} wires and calls them, EXCEPT this file
 * reconstructs {@code buildContextSummary}'s two relevant sections (RESOLUTIONS + TABLE SCHEMA)
 * inline rather than reflectively invoking {@code ChatService}'s own private method — the
 * production methods each section actually calls ({@code ResolvedQuestion.renderPromptBlock()},
 * {@code PromptAssembler.assemble()}) are the real, unmodified ones; only their ORDER/combination
 * is replicated here, identically to {@code ChatService}. No production code is changed.
 */
class OpenPurchaseOrdersPlannerRealTenantValidation {

    private static final String TENANT_SLUG = "persistent-ai-test";

    @Test
    void compareOpenVsSubmittedVsPlainPurchaseOrdersThroughRealPlanner() throws Exception {
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

        AzureOpenAiClient aiClient = new AzureOpenAiClient(objectMapper, null);
        setField(aiClient, "apiKey", apiKey);
        setField(aiClient, "chatModel", "gpt-4o");
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

        Tenant tenant = tenantRepository.findBySlug(TENANT_SLUG).orElseThrow();
        String schema = tenant.schemaName();

        try {
            TenantContext.set(schema);
            String connectionKey = "conn-c1590229"; // the real, DB-verified connection for this tenant's single agent
            List<String> connKeys = List.of(connectionKey);
            List<String> domainKeys = List.of("PLATFORM");

            String[] questions = {
                    "Show me all purchase orders",
                    "Show me all submitted purchase orders",
                    "Show me all open purchase orders"
            };
            int turn = 0;
            for (String question : questions) {
                turn++;
                System.out.println("\n==================== \"" + question + "\" ====================");
                String conversationId = "conv-diag-openpo-" + turn; // fresh per question — no chaining

                ResolvedBusinessModel model = agentBrain.resolve(
                        "data-analyst", connKeys, domainKeys, question, conversationId, false);

                System.out.println("conceptScoped=" + model.conceptScoped());
                System.out.println("objects=" + model.objects().stream().map(o -> o.objectKey()).toList());
                System.out.println("objectTargets=" + model.objectTargets());
                System.out.println("routingDecision=" + model.routingDecision());
                System.out.println("resolutions=" + model.resolution().resolutions());

                ExecutionContract contract = executionContractBuilder.compile(model);
                PromptContext promptContext = promptContextBuilder.build(contract);
                System.out.println("PromptContext.isEmpty()=" + promptContext.isEmpty());

                String resolutionsBlock = model.resolution() != null && !model.resolution().isEmpty()
                        ? model.resolution().renderPromptBlock() : "";
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

                System.out.println("--- schemaCtx (Planner input) ---");
                System.out.println(schemaCtx);
                System.out.println("--- end schemaCtx ---");

                ReasoningPlanner.StepPlan plan = planner.nextStep(question, schemaCtx, new EvidenceStore());
                if (plan == null) {
                    System.out.println("PLANNER OUTPUT: null (done/no further queries)");
                } else if (plan.isClarification()) {
                    System.out.println("PLANNER OUTPUT: CLARIFICATION — " + plan.clarificationQuestion());
                } else {
                    System.out.println("PLANNER OUTPUT: description=" + plan.description());
                    System.out.println("  connectionKey=" + plan.connectionKey());
                    System.out.println("  sql=" + plan.sql());
                    System.out.println("  objectKeys=" + plan.objectKeys());

                    Optional<?> conn = connectionRepository.findByKeyOrName(plan.connectionKey());
                    System.out.println("  connectionRepository.findByKeyOrName(\"" + plan.connectionKey()
                            + "\") present=" + conn.isPresent());
                }
            }
        } finally {
            TenantContext.clear();
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
