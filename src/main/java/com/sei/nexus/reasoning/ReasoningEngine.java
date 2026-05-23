package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.Keys;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.governance.*;
import com.sei.nexus.query.QueryExecutionRepository;
import com.sei.nexus.query.QueryGovernanceService;
import com.sei.nexus.sql.DynamicSqlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Iterative reasoning engine that replaces the single-shot SQL planning in ChatService.
 *
 * <p>Instead of generating all SQL steps at once and executing them blindly, this engine:
 * <ol>
 *   <li>Asks {@link ReasoningPlanner} for the single best next SQL query (with evidence context).</li>
 *   <li>Runs the query through the full governance chain (contract → RLS → masking).</li>
 *   <li>Asks {@link ReasoningEvaluator} whether the accumulated evidence is now sufficient.</li>
 *   <li>Repeats up to {@value #MAX_STEPS} times or until SUFFICIENT / DEAD_END / no plan.</li>
 * </ol>
 *
 * <p>Every step publishes SSE events to {@link ReasoningEventBus} so connected clients
 * can follow the investigation in real time.
 */
@Service
public class ReasoningEngine {

    private static final Logger log = LoggerFactory.getLogger(ReasoningEngine.class);

    static final int MAX_STEPS = 6;

    // ── dependencies ──────────────────────────────────────────────────────────
    private final ReasoningPlanner         planner;
    private final ReasoningEvaluator       evaluator;
    private final ReasoningEventBus        eventBus;
    private final ReasoningRepository      reasoningRepository;
    private final ConnectionRepository     connectionRepository;
    private final QueryGovernanceService   governanceService;
    private final QueryExecutionRepository executionRepository;
    private final DynamicSqlService        dynamicSqlService;
    private final DataContractService      contractService;
    private final RowLevelSecurityService  rlsService;
    private final ColumnMaskingService     maskingService;
    private final GovernanceAuditService   auditService;
    private final UserAttributesRepository userAttrsRepository;
    private final ObjectMapper             objectMapper;

    public ReasoningEngine(ReasoningPlanner planner,
                           ReasoningEvaluator evaluator,
                           ReasoningEventBus eventBus,
                           ReasoningRepository reasoningRepository,
                           ConnectionRepository connectionRepository,
                           QueryGovernanceService governanceService,
                           QueryExecutionRepository executionRepository,
                           DynamicSqlService dynamicSqlService,
                           DataContractService contractService,
                           RowLevelSecurityService rlsService,
                           ColumnMaskingService maskingService,
                           GovernanceAuditService auditService,
                           UserAttributesRepository userAttrsRepository,
                           ObjectMapper objectMapper) {
        this.planner              = planner;
        this.evaluator            = evaluator;
        this.eventBus             = eventBus;
        this.reasoningRepository  = reasoningRepository;
        this.connectionRepository = connectionRepository;
        this.governanceService    = governanceService;
        this.executionRepository  = executionRepository;
        this.dynamicSqlService    = dynamicSqlService;
        this.contractService      = contractService;
        this.rlsService           = rlsService;
        this.maskingService       = maskingService;
        this.auditService         = auditService;
        this.userAttrsRepository  = userAttrsRepository;
        this.objectMapper         = objectMapper;
    }

    /**
     * Result produced by a full reasoning session.
     *
     * @param evidence        All step evidence accumulated.
     * @param queryData       Rows from the most productive step (for frontend visualisation).
     * @param resultSnapshot  JSON of the last sync result (for conversation memory).
     * @param crossSource     True when data from more than one database was used.
     */
    public record ReasoningResult(
            EvidenceStore          evidence,
            List<Map<String, Object>> queryData,
            String                 resultSnapshot,
            boolean                crossSource
    ) {}

    /**
     * Run the iterative reasoning loop for a single user question.
     *
     * @param question    Raw user question (no attachment content).
     * @param enrichedQ   Enriched version that may include uploaded file content.
     * @param sessionKey  Reasoning session key (already created by the caller).
     * @param schemaCtx   Approved schema context string for the planner.
     * @param runKey      Parent run key — used for governance audit events.
     * @param userEmail   Authenticated user.
     * @param forceAsync  If true, heavy steps are queued for async execution.
     */
    public ReasoningResult reason(String question, String enrichedQ, String sessionKey,
                                  String schemaCtx, String runKey, String userEmail,
                                  boolean forceAsync) {

        EvidenceStore evidence      = new EvidenceStore();
        String        resultSnapshot = null;
        int           stepNo         = 1;

        while (stepNo <= MAX_STEPS) {
            // ── 1. Plan the next step ─────────────────────────────────────────
            ReasoningPlanner.StepPlan plan = planner.nextStep(enrichedQ, schemaCtx, evidence);
            if (plan == null) {
                log.info("Planner says done after {} step(s) for run {}", evidence.stepCount(), runKey);
                break;
            }

            log.info("Reasoning step {}/{} for run '{}': {}", stepNo, MAX_STEPS, runKey, plan.description());
            eventBus.publish(runKey, "step_started", Map.of(
                    "stepNo",      stepNo,
                    "description", plan.description()));

            // ── 2. Validate the connection ────────────────────────────────────
            if (connectionRepository.findByKeyOrName(plan.connectionKey()).isEmpty()) {
                log.warn("Step {} skipped — connection '{}' not found", stepNo, plan.connectionKey());
                eventBus.publish(runKey, "step_error", Map.of(
                        "stepNo", stepNo,
                        "reason", "Connection '" + plan.connectionKey() + "' not found"));
                break;
            }

            // ── 3. Governance chain ───────────────────────────────────────────
            var gov = governanceService.govern(runKey, stepNo, "", plan.connectionKey(),
                    plan.objectKeys(), plan.sql(), forceAsync);

            if ("BLOCK".equals(gov.route())) {
                evidence.add(stepNo, plan.description(), plan.sql(), plan.connectionKey(),
                        List.of(), plan.rationale(), "BLOCKED", gov.decisionReason(), 0L);
                saveStep(sessionKey, stepNo, plan, "BLOCKED", gov.decisionReason(), evidence, List.of(), null);
                eventBus.publish(runKey, "step_blocked", Map.of("stepNo", stepNo, "reason", gov.decisionReason()));
                break;
            }

            if ("EXECUTE_ASYNC".equals(gov.route())) {
                executionRepository.updateStatus(gov.executionKey(), "QUEUED", null, null, null);
                eventBus.publish(runKey, "step_async", Map.of("stepNo", stepNo, "execution_key", gov.executionKey()));
                break;
            }

            // ── 4. Data contract, RLS, column masking ─────────────────────────
            List<String> objKeys = parseKeys(plan.objectKeys());
            ContractResult contract = contractService.evaluate(gov.approvedSql(), objKeys);

            if (contract.isBlocked()) {
                String reason = contract.violationMessages().isEmpty()
                        ? "Data contract blocked this query."
                        : String.join("; ", contract.violationMessages());
                evidence.add(stepNo, plan.description(), plan.sql(), plan.connectionKey(),
                        List.of(), plan.rationale(), "CONTRACT_BLOCKED", reason, 0L);
                saveStep(sessionKey, stepNo, plan, "CONTRACT_BLOCKED", reason, evidence, List.of(), null);
                eventBus.publish(runKey, "step_blocked", Map.of("stepNo", stepNo, "reason", reason));

                // Record governance audit event
                AuditContext auditCtx = AuditContext.of(userEmail, userAttrsRepository.getRole(userEmail), runKey, plan.connectionKey())
                        .addObjectKeys(objKeys).originalSql(gov.approvedSql()).executedSql(gov.approvedSql())
                        .applyContractResult(contract);
                auditService.record(auditCtx, true);
                break;
            }

            String effectiveSql = contract.effectiveSql(gov.approvedSql());
            RlsResult  rls      = rlsService.apply(effectiveSql, userEmail, objKeys);
            MaskResult mask     = maskingService.apply(rls.sql(), userEmail, objKeys);

            // ── 5. Execute ────────────────────────────────────────────────────
            long startMs = System.currentTimeMillis();
            AuditContext auditCtx = AuditContext.of(userEmail, userAttrsRepository.getRole(userEmail), runKey, plan.connectionKey())
                    .addObjectKeys(objKeys).originalSql(gov.approvedSql()).executedSql(mask.sql())
                    .applyContractResult(contract).applyRlsResult(rls).applyMaskResult(mask);
            try {
                executionRepository.updateStatus(gov.executionKey(), "RUNNING", Instant.now(), null, null);
                List<Map<String, Object>> rows = dynamicSqlService.executeQuery(
                        plan.connectionKey(), mask.sql(), gov.rowLimit());
                String rJson = objectMapper.writeValueAsString(rows);
                executionRepository.updateResult(gov.executionKey(), rJson, "SUCCESS", Instant.now());
                resultSnapshot = rJson;

                long elapsed = System.currentTimeMillis() - startMs;
                auditCtx.rowCount(rows.size()).executionMs((int) elapsed);
                auditService.record(auditCtx, false);

                // ── 6. Evaluate ───────────────────────────────────────────────
                ReasoningEvaluator.EvaluationResult eval = evaluator.evaluate(question, buildTempEvidence(evidence, stepNo, plan, rows, elapsed));
                evidence.add(stepNo, plan.description(), plan.sql(), plan.connectionKey(),
                        rows, plan.rationale(), eval.decision(), eval.rationale(), elapsed);
                saveStep(sessionKey, stepNo, plan, eval.decision(), eval.rationale(), evidence, rows, rJson);

                eventBus.publish(runKey, "step_completed", Map.of(
                        "stepNo",   stepNo,
                        "rowCount", rows.size(),
                        "summary",  evidence.getSteps().get(evidence.stepCount() - 1).rowSummary()));
                eventBus.publish(runKey, "evaluation", Map.of(
                        "stepNo",   stepNo,
                        "decision", eval.decision(),
                        "rationale",eval.rationale()));

                if (eval.isSufficient() || "DEAD_END".equals(eval.decision())) break;

            } catch (Exception ex) {
                executionRepository.updateStatus(gov.executionKey(), "FAILED", null, Instant.now(), ex.getMessage());
                auditService.record(auditCtx, false);
                log.error("Step {} execution failed: {}", stepNo, ex.getMessage(), ex);
                evidence.add(stepNo, plan.description(), plan.sql(), plan.connectionKey(),
                        List.of(), plan.rationale(), "ERROR", ex.getMessage(), 0L);
                break;
            }

            stepNo++;
        }

        // Choose the best rows for frontend visualisation (most rows from any step, capped at 100)
        List<Map<String, Object>> queryData = evidence.getSteps().stream()
                .filter(s -> !s.rows().isEmpty())
                .max(Comparator.comparingInt(s -> s.rows().size()))
                .map(s -> s.rows().size() > 100 ? s.rows().subList(0, 100) : s.rows())
                .orElse(List.of());

        // Update session with final stats
        boolean crossSource = evidence.connectionKeys().size() > 1;
        reasoningRepository.updateSessionStatus(sessionKey, "CONCLUDED", null, 0.8, Instant.now());

        return new ReasoningResult(evidence, queryData, resultSnapshot, crossSource);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Builds a temporary EvidenceStore snapshot for evaluation — avoids mutating the real store. */
    private EvidenceStore buildTempEvidence(EvidenceStore existing, int stepNo,
                                            ReasoningPlanner.StepPlan plan,
                                            List<Map<String, Object>> rows, long ms) {
        EvidenceStore temp = new EvidenceStore();
        for (EvidenceStore.StepEvidence s : existing.getSteps()) {
            temp.add(s.stepNo(), s.description(), s.sql(), s.connectionKey(),
                    s.rows(), s.plannerRationale(), s.evaluatorDecision(), s.evaluatorRationale(), s.executionMs());
        }
        temp.add(stepNo, plan.description(), plan.sql(), plan.connectionKey(),
                rows, plan.rationale(), null, null, ms);
        return temp;
    }

    private void saveStep(String sessionKey, int stepNo, ReasoningPlanner.StepPlan plan,
                          String evaluatorDecision, String evaluatorRationale,
                          EvidenceStore evidence, List<Map<String, Object>> rows, String rJson) {
        try {
            String outputJson = rJson != null && rows.size() <= 200 ? rJson
                    : rJson != null ? objectMapper.writeValueAsString(rows.subList(0, 200)) : null;
            String evidenceSummary = evidence.buildContextForLlm();

            ReasoningStep step = new ReasoningStep(
                    Keys.uniqueKey("rstep"), sessionKey, stepNo,
                    "DATA_CHECK", plan.description(),
                    evidenceSummary.length() > 2000 ? evidenceSummary.substring(0, 2000) : evidenceSummary,
                    null, 0.0, null, Instant.now());
            reasoningRepository.saveStep(step);
        } catch (Exception e) {
            log.warn("Failed to persist reasoning step: {}", e.getMessage());
        }
    }

    private List<String> parseKeys(String keys) {
        if (keys == null || keys.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String k : keys.split(",")) {
            String t = k.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }
}
