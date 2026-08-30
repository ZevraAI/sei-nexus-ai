package com.sei.nexus.reasoning;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sei.nexus.agentbrain.ConceptScopedMetadataResolver;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.ExecutionContractBuilder;
import com.sei.nexus.agentbrain.PromptContext;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.ResolvedBusinessModel;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.onboarding.TenantSettingsRepository;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.SemanticRepository;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler;
import com.sei.nexus.semanticmodel.SemanticModel;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.semantic.ResolvedQuestion;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantAwareDataSource;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DIAGNOSTIC ONLY — real DB, real OpenAI, real tenant {@code persistent-ai-test}, connection
 * {@code conn-c1590229}. Excluded from Surefire's default run (same {@code *RealTenantValidation}
 * convention). Uses ONLY reflection to observe the real, unmodified private production boundary
 * inside {@link ConceptScopedMetadataResolver} — {@code resolveStage1SelectionInternal} (which
 * {@code resolveObjectKeysWithRouting} calls internally) — so the exact validated Stage 1
 * {@code conceptKeys} for THIS SAME invocation can be compared against the exact, immediately
 * following, deterministic Stage 2 lookup ({@code SemanticService#findEntitiesByConnectionAndConcepts},
 * a public, real, DB-backed method) for that same invocation — one real LLM call per run, not two
 * independent samples. {@code PromptContext.isEmpty()} is then derived by feeding that same
 * Stage-2 result through the real, unmodified {@link EnterpriseSemanticAssembler#assembleByObjectKeys}
 * (when non-empty) / {@link SemanticModel} empty-case (when empty — the exact branch {@link
 * com.sei.nexus.agentbrain.AgentBrain#conceptScopedModelWithRouting} takes), {@link
 * ExecutionContractBuilder}, and {@link PromptContextBuilder} — all real, unmodified, deterministic,
 * no additional LLM call. A Logback {@link ListAppender} on {@link ConceptScopedMetadataResolver}'s
 * own logger captures the real, unmodified {@code STAGE1_CONVERSATION_CHAIN} line per run to verify
 * chaining state — no production code touched.
 */
class OpenPurchaseOrdersStage1Stage2BoundaryRealTenantValidation {

    private static final String TENANT_SLUG = "persistent-ai-test";
    private static final String CONNECTION_KEY = "conn-c1590229";
    private static final int RUNS = 15;

    @Test
    void repeatedAgentBrainWiredRunsCaptureStage1ToStage2Boundary() throws Exception {
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
        ExecutionContractBuilder executionContractBuilder = new ExecutionContractBuilder(new SqlTableReferenceExtractor());
        PromptContextBuilder promptContextBuilder = new PromptContextBuilder();

        // ── Real, unmodified reflection into the resolver's private Stage 1/Stage 2 boundary ──
        Method resolveStage1SelectionInternal = ConceptScopedMetadataResolver.class.getDeclaredMethod(
                "resolveStage1SelectionInternal", String.class, IndustryPack.class, List.class,
                String.class, String.class, boolean.class, boolean.class);
        resolveStage1SelectionInternal.setAccessible(true);
        Class<?> stage1SelectionClass = null;
        for (Class<?> nested : ConceptScopedMetadataResolver.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("Stage1Selection")) stage1SelectionClass = nested;
        }
        Method selectedAccessor = stage1SelectionClass.getDeclaredMethod("selected");
        Method routingAccessor  = stage1SelectionClass.getDeclaredMethod("routing");
        selectedAccessor.setAccessible(true);
        routingAccessor.setAccessible(true);

        Logger resolverLogger = (Logger) LoggerFactory.getLogger(ConceptScopedMetadataResolver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        resolverLogger.addAppender(appender);

        Tenant tenant = tenantRepository.findBySlug(TENANT_SLUG).orElseThrow();
        String schema = tenant.schemaName();
        String question = "Show me all open purchase orders";

        List<String> rows = new ArrayList<>();
        int caseA = 0, caseB = 0, caseC = 0, caseD = 0;

        try {
            TenantContext.set(schema);

            TenantPack assignment = packRepository.findActivePackForConnection(CONNECTION_KEY).orElseThrow();
            IndustryPack pack = packRepository.findPackById(assignment.packKey()).orElseThrow();
            List<String> usedConceptKeys = semanticService.findDistinctConceptKeysForConnection(CONNECTION_KEY);
            System.out.println("usedConceptKeys (constant across all runs, DB-derived) = " + usedConceptKeys);

            for (int run = 1; run <= RUNS; run++) {
                String conversationId = "conv-diag-boundary-" + run; // fresh — never reused

                appender.list.clear();
                Object stage1Selection;
                try {
                    stage1Selection = resolveStage1SelectionInternal.invoke(conceptResolver,
                            CONNECTION_KEY, pack, usedConceptKeys, question, conversationId, true, false);
                } catch (Exception e) {
                    System.out.println("Run " + run + ": EXCEPTION invoking Stage 1 — " + e.getCause());
                    rows.add("Run " + run + ": EXCEPTION");
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                    continue;
                }

                @SuppressWarnings("unchecked")
                List<String> stage1ConceptKeys = (List<String>) selectedAccessor.invoke(stage1Selection);
                Object routing = routingAccessor.invoke(stage1Selection);

                // Real chain-log line, unmodified production observability.
                String chainLog = appender.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(m -> m.contains("STAGE1_CONVERSATION_CHAIN"))
                        .reduce((a, b) -> b).orElse("(none captured)");

                // Real, deterministic Stage 2 — same conceptKeys this exact invocation produced.
                List<BusinessEntity> matched = stage1ConceptKeys == null || stage1ConceptKeys.isEmpty()
                        ? List.of()
                        : semanticService.findEntitiesByConnectionAndConcepts(CONNECTION_KEY, stage1ConceptKeys);
                List<String> objectKeys = matched.stream().map(BusinessEntity::primaryObjectKey)
                        .filter(k -> k != null && !k.isBlank()).distinct().toList();

                // Real, deterministic downstream — exact branch AgentBrain#conceptScopedModelWithRouting takes.
                SemanticModel model = objectKeys.isEmpty()
                        ? new SemanticModel(List.of(), Map.of(), Map.of())
                        : assembler.assembleByObjectKeys(objectKeys);
                ResolvedBusinessModel resolvedModel = new ResolvedBusinessModel(
                        "data-analyst", List.of(CONNECTION_KEY), question,
                        model.objects(), model.objectTargets(), model.attributeTargets(),
                        ResolvedQuestion.empty(question), Map.of(), true, Optional.empty());
                ExecutionContract contract = executionContractBuilder.compile(resolvedModel);
                PromptContext promptContext = promptContextBuilder.build(contract);

                boolean stage1Empty = stage1ConceptKeys == null || stage1ConceptKeys.isEmpty();
                boolean stage2Empty = objectKeys.isEmpty();
                String classification;
                if (!stage1Empty && !stage2Empty) { classification = "A"; caseA++; }
                else if (!stage1Empty && stage2Empty) { classification = "D (Stage1 ok, Stage2 empty)"; caseD++; }
                else if (stage1Empty) { classification = "C (Stage1 itself empty)"; caseC++; }
                else { classification = "B"; caseB++; }
                // Note: "B" (Stage1 non-empty going in but conceptKeys lost before Stage2) cannot
                // occur in this harness since Stage2 is called with stage1ConceptKeys directly —
                // included only to keep the same taxonomy as the task's classification scheme.

                System.out.println("\n=== RUN " + run + " ===");
                System.out.println("chainLog: " + chainLog);
                System.out.println("Stage1 conceptKeys = " + stage1ConceptKeys);
                System.out.println("Stage1 routing = " + routing);
                System.out.println("Stage2 (findEntitiesByConnectionAndConcepts) matched objectKeys = " + objectKeys);
                System.out.println("PromptContext.isEmpty() = " + promptContext.isEmpty());
                System.out.println("classification = " + classification);

                rows.add(String.format("Run %d | Stage1=%s | Stage2objects=%s | PromptContext.isEmpty=%s | %s",
                        run, stage1ConceptKeys, objectKeys, promptContext.isEmpty(), classification));

                try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            }
        } finally {
            resolverLogger.detachAppender(appender);
            TenantContext.clear();
        }

        System.out.println("\n\n================ SUMMARY TABLE ================");
        for (String r : rows) System.out.println(r);
        System.out.println("\nA (Stage1 ok, Stage2 ok) = " + caseA);
        System.out.println("B (lost between Stage1/Stage2, cannot occur in this harness) = " + caseB);
        System.out.println("C (Stage1 itself empty) = " + caseC);
        System.out.println("D (Stage1 ok, Stage2 empty) = " + caseD);
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
