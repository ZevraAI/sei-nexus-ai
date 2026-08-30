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
import com.sei.nexus.semantic.ResolvedQuestion;
import com.sei.nexus.semantic.SemanticRepository;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
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
 * DIAGNOSTIC ONLY — real DB, real OpenAI, real tenant {@code persistent-ai-test}, connection
 * {@code conn-c1590229}. Excluded from Surefire's default run.
 *
 * <p>Reproduces the actual multi-turn conversations from the real Chat UI, through the real,
 * unmodified public production entry points: {@link BusinessLanguageResolver#resolve}, {@link
 * AgentBrain#resolve(String, List, List, String, String, Boolean)} (the exact method {@code
 * ChatService.ask()} calls, including its internal combined Stage 1 + Stage 2 resolution and
 * Decision Router absorption routing decision), {@link ExecutionContractBuilder#compile}, {@link
 * PromptContextBuilder#build}, {@link PromptAssembler#assemble}, and {@link
 * ReasoningPlanner#nextStep}. Multi-turn cases reuse the SAME {@code conversationId} across turns
 * so {@code previous_response_id} chaining engages exactly as it does in a real conversation.
 *
 * <p>Deliberately stops short of executing generated SQL against the tenant's own business data
 * connection (which would require decrypting {@code nexus_connection.encrypted_secret} and
 * opening a second live JDBC connection to a third-party-shaped data source — a materially larger
 * and riskier step than this diagnostic's evidentiary need). Instead, the same decisive signal
 * this whole investigation has used throughout — {@link
 * ConnectionRepository#findByKeyOrName}, the exact check {@link
 * com.sei.nexus.runtime.GovernedSqlRuntime} performs before executing anything — is captured
 * directly against the Planner's real output, which is sufficient to determine pass/fail for
 * every case without executing real business SQL.
 */
class OpenPurchaseOrdersFullConversationRealTenantValidation {

    private static final String TENANT_SLUG = "persistent-ai-test";
    private static final String CONNECTION_KEY = "conn-c1590229";

    @Test
    void reproduceTheThreeRealConversations() throws Exception {
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

        com.sei.nexus.agentbrain.ConceptScopedMetadataResolver conceptResolver =
                new com.sei.nexus.agentbrain.ConceptScopedMetadataResolver(
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
        List<String> connKeys = List.of(CONNECTION_KEY);
        List<String> domainKeys = List.of("PLATFORM");

        try {
            TenantContext.set(schema);

            System.out.println("\n\n################ CASE A: \"purchase orders\" -> \"only submitted\" ################");
            runConversation("case-a", new String[] {"Show me all purchase orders", "I want only submitted"},
                    connKeys, domainKeys, agentBrain, blr, executionContractBuilder, promptContextBuilder,
                    promptAssembler, planner, connectionRepository);

            System.out.println("\n\n################ CASE B: \"open purchase orders\" (single turn) ################");
            runConversation("case-b", new String[] {"Show me all open purchase orders"},
                    connKeys, domainKeys, agentBrain, blr, executionContractBuilder, promptContextBuilder,
                    promptAssembler, planner, connectionRepository);

            System.out.println("\n\n################ CASE C: \"purchase orders\" -> \"only open purchase orders\" ################");
            runConversation("case-c", new String[] {"Show me all purchase orders", "I want only open purchase orders"},
                    connKeys, domainKeys, agentBrain, blr, executionContractBuilder, promptContextBuilder,
                    promptAssembler, planner, connectionRepository);
        } finally {
            TenantContext.clear();
        }
    }

    private void runConversation(String caseId, String[] turns, List<String> connKeys, List<String> domainKeys,
                                  AgentBrain agentBrain, BusinessLanguageResolver blr,
                                  ExecutionContractBuilder executionContractBuilder,
                                  PromptContextBuilder promptContextBuilder, PromptAssembler promptAssembler,
                                  ReasoningPlanner planner, ConnectionRepository connectionRepository) throws Exception {
        String conversationId = "conv-diag-" + caseId; // SAME across turns in this conversation — real chaining
        int turnNo = 0;
        for (String question : turns) {
            turnNo++;
            System.out.println("\n===== " + caseId.toUpperCase() + " TURN " + turnNo + ": \"" + question + "\" =====");

            ResolvedQuestion blrResolution = domainKeys.isEmpty()
                    ? ResolvedQuestion.empty(question) : blr.resolve(question, domainKeys);
            System.out.println("BLR resolutions = " + blrResolution.resolutions());

            ResolvedBusinessModel model = agentBrain.resolve(
                    "data-analyst", connKeys, domainKeys, question, conversationId, false);

            System.out.println("conceptScoped=" + model.conceptScoped());
            System.out.println("routingDecision=" + model.routingDecision());
            System.out.println("objects=" + model.objects().stream().map(o -> o.objectKey()).toList());
            System.out.println("objectTargets=" + model.objectTargets());

            ExecutionContract contract = executionContractBuilder.compile(model);
            PromptContext promptContext = promptContextBuilder.build(contract);
            System.out.println("PromptContext.isEmpty()=" + promptContext.isEmpty());

            String resolutionsBlock = !model.resolution().isEmpty() ? model.resolution().renderPromptBlock() : "";
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
            System.out.println("schemaCtx tableSchema chars=" + tableSchemaBlock.length()
                    + " resolutions chars=" + resolutionsBlock.length());

            String decisionSource = model.routingDecision().isPresent()
                    ? "PERSISTENT_KNOWLEDGE_COMBINED" : "DECISION_ROUTER_LEGACY (routing absent — would call getLlmDecision)";
            System.out.println("decisionSource=" + decisionSource);

            ReasoningPlanner.StepPlan plan = planner.nextStep(question, schemaCtx, new EvidenceStore());
            if (plan == null) {
                System.out.println("PLANNER: null (done)");
            } else if (plan.isClarification()) {
                System.out.println("PLANNER: CLARIFICATION — " + plan.clarificationQuestion());
            } else {
                System.out.println("PLANNER description=" + plan.description());
                System.out.println("PLANNER connectionKey=" + plan.connectionKey());
                System.out.println("PLANNER sql=" + plan.sql());
                Optional<?> conn = connectionRepository.findByKeyOrName(plan.connectionKey());
                System.out.println("connectionRepository.findByKeyOrName(\"" + plan.connectionKey()
                        + "\") present=" + conn.isPresent()
                        + (conn.isPresent() ? " => WOULD EXECUTE" : " => WOULD FAIL: CONNECTION_NOT_FOUND"));
            }

            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
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
