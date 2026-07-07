package com.sei.nexus.brief;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.agentrunner.AgentRunner;
import com.sei.nexus.agentrunner.ZevraAgent;
import com.sei.nexus.agentrunner.ZevraAgentRepository;
import com.sei.nexus.agentrunner.ZevraSession;
import com.sei.nexus.common.Keys;
import com.sei.nexus.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class MorningBriefService {

    private static final Logger log = LoggerFactory.getLogger(MorningBriefService.class);

    // Brief question template — %s is replaced with agent.goal() at runtime so each
    // agent investigates its own domain. No hardcoded table names.
    private static final String BRIEF_QUESTION_TEMPLATE =
            "Prepare a morning operational brief for your domain.\n" +
            "Your focus: %s\n\n" +
            "The schema with exact column names, types, and status values is already in your context — " +
            "do NOT call describe_schema.\n" +
            "Run exactly 3 queries, then call final_answer:\n" +
            "  Query 1: SELECT <status_or_category_col>, COUNT(*) AS total FROM <most_relevant_table> " +
            "GROUP BY <status_col> ORDER BY <status_col> — see the full state distribution.\n" +
            "  Query 2: Find records needing attention — use exact status/severity values from the schema " +
            "(e.g. WHERE status = 'SUBMITTED') to find unresolved, overdue, or flagged items.\n" +
            "  Query 3: One more metric relevant to your focus — a count, total, or trend.\n" +
            "Include actual numbers in final_answer. State what needs attention and what is working well.";

    // System prompt for the final synthesis call that formats all agent outputs
    private static final String SYNTHESIS_SYSTEM_PROMPT = """
            You are producing an executive morning brief for operational leadership.

            TONE: Direct. Factual. Like a Goldman Sachs morning note.
            Numbers over adjectives. No filler phrases like "Great news" or "It's worth noting".
            Lead with the most important finding. Zero counts ARE data — report them.

            FORMAT: Return ONLY valid JSON — no markdown, no explanation:
            {
              "headline": "<one sentence — the single most important thing today>",
              "sections": [
                {
                  "type": "urgent",
                  "title": "Needs Attention",
                  "content": "<narrative>",
                  "items": [{"text": "<item>", "severity": "HIGH|MEDIUM"}]
                },
                {
                  "type": "snapshot",
                  "title": "Operational Snapshot",
                  "content": "<brief narrative context>",
                  "metrics": [
                    {"label": "<descriptive label e.g. Pending Orders>", "value": "<numeric count e.g. 6>", "trend": "", "direction": "up|down|flat"}
                  ]
                },
                {
                  "type": "insight",
                  "title": "Key Insight",
                  "content": "<one meaningful pattern or finding>"
                },
                {
                  "type": "working",
                  "title": "What's Working",
                  "content": "<what is performing well or what the data shows is stable>"
                }
              ],
              "agents_used": ["<agent name>"]
            }

            RULES:
            - ALWAYS generate a real headline and real sections based on what agents reported.
            - Zero values are valid data — "0 open claims" means the claims queue is clear; say so.
            - Omit the "urgent" section only if there is genuinely nothing requiring attention.
            - Never invent numbers. Use only what agents explicitly found.
            - If agents found delayed shipments, unresolved records, or anomalies — lead with those.
            - Keep each section to 1-3 sentences. Executives read this in under 3 minutes.
            """;

    private final MorningBriefRepository  briefRepository;
    private final ZevraAgentRepository    agentRepository;
    private final AgentRunner             agentRunner;
    private final AzureOpenAiClient       openAi;
    private final ObjectMapper            mapper;

    public MorningBriefService(MorningBriefRepository briefRepository,
                                ZevraAgentRepository agentRepository,
                                AgentRunner agentRunner,
                                AzureOpenAiClient openAi,
                                ObjectMapper mapper) {
        this.briefRepository = briefRepository;
        this.agentRepository  = agentRepository;
        this.agentRunner      = agentRunner;
        this.openAi           = openAi;
        this.mapper           = mapper;
    }

    // A brief still GENERATING after this long is orphaned (e.g. the app was
    // restarted mid-generation) — mark it FAILED so the UI offers a retry.
    private static final java.time.Duration GENERATING_TIMEOUT =
            java.time.Duration.ofMinutes(10);

    // ── Public API ────────────────────────────────────────────────────────────

    public Optional<MorningBrief> getTodaysBrief(String tenantSchema) {
        Optional<MorningBrief> brief =
                briefRepository.findByDate(tenantSchema, todayFor(tenantSchema));

        if (brief.isPresent() && "GENERATING".equals(brief.get().status())
                && brief.get().createdAt() != null
                && brief.get().createdAt().isBefore(
                        java.time.Instant.now().minus(GENERATING_TIMEOUT))) {
            log.warn("Brief '{}' stuck in GENERATING for over {} minutes — marking FAILED",
                    brief.get().id(), GENERATING_TIMEOUT.toMinutes());
            briefRepository.updateBriefFailed(brief.get().id());
            return briefRepository.findByDate(tenantSchema, todayFor(tenantSchema));
        }
        return brief;
    }

    /**
     * Today's date in the tenant's configured timezone (UTC when unset or invalid).
     * The brief "day" must follow the executive's clock, not the server's: with
     * UTC dates, an evening visit in a western timezone looks for tomorrow's
     * brief, which doesn't exist yet, and the page has nothing to show.
     */
    private LocalDate todayFor(String tenantSchema) {
        ZoneId zone = briefRepository.findConfig(tenantSchema)
                .map(cfg -> {
                    try { return ZoneId.of(cfg.timezone()); }
                    catch (Exception e) { return ZoneId.of("UTC"); }
                })
                .orElse(ZoneId.of("UTC"));
        return LocalDate.now(zone);
    }

    public Optional<MorningBriefConfig> getConfig(String tenantSchema) {
        return briefRepository.findConfig(tenantSchema);
    }

    public MorningBriefConfig saveConfig(MorningBriefConfig config) {
        briefRepository.upsertConfig(config);
        return briefRepository.findConfig(config.tenantSchema()).orElse(config);
    }

    public List<String> getBriefAgentIds(String tenantSchema) {
        return briefRepository.findConfig(tenantSchema)
                .map(MorningBriefConfig::briefAgentIds)
                .orElse(List.of());
    }

    /**
     * Triggers generation for today's brief. If one already exists and is READY,
     * deletes it first (regeneration). Returns the brief ID immediately;
     * generation runs asynchronously.
     */
    public String requestGeneration(String tenantSchema) {
        LocalDate today = todayFor(tenantSchema);

        // Delete any existing brief for today (allows regeneration)
        briefRepository.deleteBrief(tenantSchema, today);

        String briefId = Keys.uniqueKey("brief");
        MorningBrief stub = new MorningBrief(
                briefId, tenantSchema, today, "GENERATING",
                null, "[]", List.of(), null, null);
        briefRepository.insertBrief(stub);

        // Run generation in background — caller gets the ID immediately
        String schemaForAsync = tenantSchema;
        CompletableFuture.runAsync(() -> generateAsync(briefId, schemaForAsync, today));

        return briefId;
    }

    /**
     * Called by the scheduler. Generates if a config exists, is enabled, the
     * scheduled time has passed today, and no brief exists yet for today.
     */
    public void generateIfDue(MorningBriefConfig config) {
        String tenantSchema = config.tenantSchema();

        // Both the brief date and the schedule check use the tenant's timezone
        // so the "day" rolls over on the executive's clock, not the server's.
        ZoneId tz;
        java.time.LocalTime scheduledTime;
        try {
            tz = ZoneId.of(config.timezone());
            scheduledTime = java.time.LocalTime.parse(config.scheduleTime());
        } catch (Exception e) {
            log.warn("Invalid schedule config for tenant {}: {}", tenantSchema, e.getMessage());
            return;
        }
        LocalDate today = LocalDate.now(tz);

        if (briefRepository.findByDate(tenantSchema, today).isPresent()) return;
        if (java.time.LocalTime.now(tz).isBefore(scheduledTime)) return;

        log.info("Generating morning brief for tenant '{}'", tenantSchema);
        String briefId = Keys.uniqueKey("brief");
        MorningBrief stub = new MorningBrief(
                briefId, tenantSchema, today, "GENERATING",
                null, "[]", List.of(), null, null);
        briefRepository.insertBrief(stub);

        CompletableFuture.runAsync(() -> generateAsync(briefId, tenantSchema, today));
    }

    // ── Private generation logic ──────────────────────────────────────────────

    private void generateAsync(String briefId, String tenantSchema, LocalDate date) {
        TenantContext.set(tenantSchema);
        try {
            // 1. Find eligible agents — respect the executive's selection if configured.
            //    Empty brief_agent_ids = use all active agents (backward compatible).
            List<String> configuredIds = briefRepository.findConfig(tenantSchema)
                    .map(MorningBriefConfig::briefAgentIds)
                    .orElse(List.of());

            List<ZevraAgent> activeAgents = agentRepository.findByTenant(tenantSchema)
                    .stream()
                    .filter(a -> "ACTIVE".equals(a.status()))
                    .filter(a -> configuredIds.isEmpty() || configuredIds.contains(a.id()))
                    .collect(Collectors.toList());

            // 2. Run each agent with the morning brief question
            List<String> agentOutputs = new ArrayList<>();
            List<String> agentNames   = new ArrayList<>();

            for (ZevraAgent agent : activeAgents) {
                try {
                    ZevraAgent cappedAgent = new ZevraAgent(
                            agent.id(), agent.tenantSchema(), agent.name(), agent.slug(),
                            agent.description(), agent.persona(), agent.goal(),
                            agent.connectionKeys(), Math.min(agent.maxIterations(), 5),
                            agent.status(), agent.createdBy(), agent.createdAt(), agent.updatedAt());

                    com.sei.nexus.usage.UsageContext.set("brief", null, agent.name());
                    String briefQuestion = String.format(BRIEF_QUESTION_TEMPLATE, agent.goal());
                    ZevraSession session = agentRunner.run(cappedAgent, briefQuestion);

                    // Include the raw SQL query results from steps — not just the vague
                    // narrative in finalOutput. This gives synthesis concrete numbers.
                    String dataSection = extractQueryResults(session.stepsJson());
                    String narrative   = session.finalOutput() != null ? session.finalOutput() : "";

                    if (!dataSection.isBlank() || !narrative.isBlank()) {
                        agentOutputs.add(
                                "=== " + agent.name() + " ===\n\n" +
                                (narrative.isBlank() ? "" : "Summary: " + narrative + "\n\n") +
                                (dataSection.isBlank() ? "" : "Raw Query Data:\n" + dataSection));
                        agentNames.add(agent.name());
                    }

                    if (activeAgents.indexOf(agent) < activeAgents.size() - 1) {
                        Thread.sleep(3000);
                    }
                } catch (Exception e) {
                    log.warn("Agent '{}' failed during brief generation: {}", agent.name(), e.getMessage());
                }
            }

            // 3. Synthesise into structured JSON via a single LLM formatting call
            String briefJson = synthesise(agentOutputs, agentNames);

            // 4. Parse and persist
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(briefJson, Map.class);
            String headline     = String.valueOf(parsed.getOrDefault("headline", "Morning brief ready."));
            String sectionsJson = mapper.writeValueAsString(parsed.getOrDefault("sections", List.of()));

            briefRepository.updateBriefReady(briefId, headline, sectionsJson, agentNames);
            log.info("Morning brief '{}' ready for tenant '{}'", briefId, tenantSchema);

        } catch (Exception e) {
            log.error("Morning brief generation failed for tenant '{}': {}", tenantSchema, e.getMessage());
            briefRepository.updateBriefFailed(briefId);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Extracts the actual SQL queries and their result rows from a session's steps JSON,
     * giving the synthesis LLM concrete numbers rather than just the agent's vague narrative.
     */
    @SuppressWarnings("unchecked")
    private String extractQueryResults(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) return "";
        try {
            List<Map<String, Object>> steps = mapper.readValue(stepsJson, List.class);
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> step : steps) {
                if (!"TOOL_CALL".equals(step.get("type"))) continue;
                Object input  = step.get("input");
                Object output = step.get("output");
                if (input == null || output == null) continue;
                String sql = "";
                if (input instanceof Map<?,?> m) sql = String.valueOf(((Map<?,?>) m).get("sql") != null ? ((Map<?,?>) m).get("sql") : "");
                if (sql.isBlank()) continue;
                sb.append("Query: ").append(sql.trim()).append("\n");
                sb.append("Result: ").append(mapper.writeValueAsString(output)).append("\n\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String synthesise(List<String> agentOutputs, List<String> agentNames) {
        if (agentOutputs.isEmpty()) {
            return """
                    {"headline":"No active agents provided data for today's brief.",
                     "sections":[{"type":"insight","title":"Getting Started",
                       "content":"Configure and activate Zevra Agents to enable your morning brief."}],
                     "agents_used":[]}
                    """;
        }

        String combinedOutputs = String.join("\n\n", agentOutputs);
        String userPrompt = "Agent reports:\n\n" + combinedOutputs +
                "\n\nAgents that contributed: " + String.join(", ", agentNames);

        return openAi.chatWithJson(
                List.of(ChatMessage.user(userPrompt)),
                SYNTHESIS_SYSTEM_PROMPT);
    }
}
