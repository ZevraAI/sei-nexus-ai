package com.sei.nexus.reasoning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.common.Keys;
import com.sei.nexus.runtime.ExecutionReference;
import com.sei.nexus.runtime.GovernedSqlRuntime;
import com.sei.nexus.semanticmodel.ColumnValueDomain;
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
    private final GovernedSqlRuntime       runtime;
    private final ObjectMapper             objectMapper;
    private final ColumnMetadataRequestHandler metadataRequestHandler;

    public ReasoningEngine(ReasoningPlanner planner,
                           ReasoningEvaluator evaluator,
                           ReasoningEventBus eventBus,
                           ReasoningRepository reasoningRepository,
                           GovernedSqlRuntime runtime,
                           ObjectMapper objectMapper,
                           ColumnMetadataRequestHandler metadataRequestHandler) {
        this.planner               = planner;
        this.evaluator             = evaluator;
        this.eventBus              = eventBus;
        this.reasoningRepository   = reasoningRepository;
        this.runtime               = runtime;
        this.objectMapper          = objectMapper;
        this.metadataRequestHandler = metadataRequestHandler;
    }

    /**
     * Result produced by a full reasoning session.
     *
     * @param evidence          All step evidence accumulated.
     * @param queryData         Rows from the most productive step (for frontend visualisation).
     * @param investigationDatasets Every row-bearing step's own rows, preserved independently and
     *                          in step order — mechanically derived from {@code evidence}, never
     *                          filtered by {@code evaluatorDecision}, never merged, never ranked
     *                          against each other. Distinct from {@code queryData} above: that
     *                          field remains the single last-SUFFICIENT-step selection (unchanged,
     *                          kept for backward compatibility); this field is the complete set of
     *                          successful business-data results the investigation produced. See
     *                          {@link InvestigationDataset}.
     * @param resultSnapshot    JSON of the last sync result (for conversation memory).
     * @param crossSource       True when data from more than one database was used.
     * @param validatedBindings Literal bindings the planner declared that passed
     *                          deterministic domain validation on a step that
     *                          executed successfully (PRO-33) — the trace and
     *                          learning-capture payload.
     */
    public record ReasoningResult(
            EvidenceStore          evidence,
            List<Map<String, Object>> queryData,
            List<InvestigationDataset> investigationDatasets,
            String                 resultSnapshot,
            boolean                crossSource,
            List<ValidatedBinding> validatedBindings,
            List<String>           shadowGateFindings
    ) {}

    /** A planner-declared literal resolution confirmed against a persisted domain. */
    public record ValidatedBinding(String surface, String column, String value,
                                   boolean authoritative) {}

    /**
     * Run the iterative reasoning loop for a single user question.
     *
     * @param question    Raw user question (no attachment content).
     * @param enrichedQ   Enriched version that may include uploaded file content.
     * @param sessionKey  Reasoning session key (already created by the caller).
     * @param schemaCtx   Approved schema context string for the planner.
     * @param runKey       Parent run key — used for governance audit events.
     * @param userEmail    Authenticated user.
     * @param forceAsync   If true, heavy steps are queued for async execution.
     * @param literalScope Domain-bearing columns in scope for deterministic
     *                     literal validation (PRO-33); null/empty disables the
     *                     gate and reproduces pre-DLR behavior exactly.
     * @param priorExecution The conversation's last {@link ExecutionReference}, if any — the
     *                       same record already fetched by the caller for execution-continuity
     *                       grounding. When present, its result is loaded into this session's
     *                       EvidenceStore as step 0 and {@link ReasoningEvaluator} judges
     *                       whether it already answers THIS question before any planning
     *                       happens. Planner never sees whether evidence was seeded or
     *                       produced by this loop — it only ever sees an EvidenceStore.
     * @param resolvedObjects The run's compiled {@link ExecutionContract} — always the full,
     *                        already-resolved object set, independent of {@code
     *                        enforceContractGate}/{@code contract} (which may be {@code null}
     *                        under the "off" gate migration mode). Used exclusively to validate
     *                        and resolve Missing-Column Metadata Requests (see {@link
     *                        ColumnMetadataRequestHandler}) — never for gating/enforcement,
     *                        which remains entirely governed by {@code contract} as before this
     *                        parameter existed. {@code null} degrades every metadata request to a
     *                        rejection (nothing to validate against), never a guess.
     */
    public ReasoningResult reason(String question, String enrichedQ, String sessionKey,
                                  String schemaCtx, String runKey, String userEmail,
                                  boolean forceAsync,
                                  Map<String, ColumnValueDomain> literalScope,
                                  com.sei.nexus.agentbrain.ExecutionContract contract,
                                  boolean enforceContractGate,
                                  String conversationId, String parentExecutionId,
                                  ExecutionReference priorExecution,
                                  ExecutionContract resolvedObjects) {

        EvidenceStore evidence      = new EvidenceStore();
        String        resultSnapshot = null;
        int           stepNo         = 1;
        // PRO-33 retry memory: (column, literal) pairs already rejected once —
        // the "re-prompted once" bound of PRO-32 §5.
        Set<String>            literalRejections = new HashSet<>();
        List<ValidatedBinding> validatedBindings = new ArrayList<>();
        // Phase 3 Step 2: what the business-object gate would have rejected, when shadowing.
        List<String>           shadowGateFindings = new ArrayList<>();

        // Reuses the existing ExecutionReference — no new persistence. Seeds this session's
        // EvidenceStore with the conversation's last execution as step 0, then asks the
        // evaluator (its existing, only job) whether that's already enough to answer THIS
        // question. SUFFICIENT/DEAD_END skips the loop entirely; otherwise the loop below runs
        // exactly as it does for a brand-new investigation, continuing from step 1.
        if (priorExecution != null) {
            String seedDescription = "Result carried over from the previous turn in this conversation";
            evidence.add(0, seedDescription,
                    priorExecution.sqlReference(), priorExecution.connectionKey(),
                    parseRows(priorExecution.resultJson()), null, null, null,
                    priorExecution.executionMs());
            resultSnapshot = priorExecution.resultJson();

            ReasoningEvaluator.EvaluationResult preEval = evaluator.evaluate(question, evidence);
            // Observability: persist this decision exactly as loop iterations persist theirs
            // (saveStep, same table, same columns) — without this, SUFFICIENT and DEAD_END are
            // indistinguishable after the fact, which is how the Luxury Peptide defect stayed
            // invisible until traced by hand.
            ReasoningPlanner.StepPlan seedPlan = new ReasoningPlanner.StepPlan(
                    seedDescription, priorExecution.sqlReference(), priorExecution.connectionKey(),
                    null, null);
            saveStep(sessionKey, 0, seedPlan, preEval.decision(), preEval.rationale(),
                    evidence, evidence.latestRows(), priorExecution.resultJson());

            boolean reusedPriorEvidence = preEval.isSufficient() || "DEAD_END".equals(preEval.decision());
            // Observability (conversational evidence-reuse correctness): lets a real request be
            // classified, after the fact, as "reused the prior turn's result set" vs "invoked
            // Planner for a new execution" without logging the question or rationale text (both
            // may contain business/user data) — only the decision and the resulting boolean.
            log.info("CONVERSATION_EVIDENCE_REUSE conversationId={} decision={} reusedPriorResultSet={}",
                    conversationId, preEval.decision(), reusedPriorEvidence);
            if (reusedPriorEvidence) {
                stepNo = MAX_STEPS + 1;
            } else {
                stepNo = evidence.stepCount() + 1;
            }
        }

        while (stepNo <= MAX_STEPS) {
            // ── 1. Plan the next step ─────────────────────────────────────────
            ReasoningPlanner.StepPlan plan = planner.nextStep(enrichedQ, schemaCtx, evidence);
            if (plan == null) {
                log.info("Planner says done after {} step(s) for run {}", evidence.stepCount(), runKey);
                break;
            }

            // Semantic Reasoning Over Authoritative Value Domains: the planner declined to
            // generate SQL because the user's term cannot be defensibly resolved against an
            // authoritative legal-values column (see ReasoningPlanner's SYSTEM_PROMPT). Recorded
            // as evidence — exactly like UNAPPROVED_OBJECTS/LITERAL_REJECTED below — so the
            // existing composeAnswer pipeline turns it into a clarifying question for the user,
            // rather than silently stopping with no explanation. No SQL is executed; nothing
            // reaches GovernedSqlRuntime/Postgres for this step.
            if (plan.isClarification()) {
                log.info("Planner requests clarification at step {} for run '{}': {}",
                        stepNo, runKey, plan.clarificationQuestion());
                evidence.addOutcome(stepNo, plan.description(), "", "", plan.rationale(),
                        "CLARIFICATION_NEEDED", plan.clarificationQuestion(), 0L);
                saveStep(sessionKey, stepNo, plan, "CLARIFICATION_NEEDED", plan.clarificationQuestion(),
                        evidence, List.of(), null);
                eventBus.publish(runKey, "step_blocked",
                        Map.of("stepNo", stepNo, "reason", plan.clarificationQuestion()));
                break;
            }

            // Missing-Column Metadata Request: the planner already knows this object is relevant
            // (it named it from "Approved schema") but was not shown its columns — see
            // ReasoningPlanner's SYSTEM_PROMPT. Java's only role is to validate the requested
            // object against the resolved object set and, on a match, retrieve its authoritative
            // columns — never to search, rank, or guess on the planner's behalf. Re-plannable,
            // like UNAPPROVED_OBJECTS: consumes one step of the existing MAX_STEPS budget so this
            // cannot loop indefinitely, then the next planner call sees the outcome (columns, or
            // a rejection) and continues.
            if (plan.isMetadataRequest()) {
                ReasoningPlanner.MetadataRequest req = plan.metadataRequest();
                Optional<String> columns = metadataRequestHandler.resolveColumns(resolvedObjects, req.object());
                String decision;
                String detail;
                if (columns.isPresent()) {
                    schemaCtx = schemaCtx + "\n\nRequested column metadata for \"" + req.object() + "\":\n"
                            + columns.get();
                    decision = "METADATA_RETRIEVED";
                    // Mechanical count of this object's own rendered column bullets (PromptAssembler's
                    // fixed "    • `col`" format) — not a semantic reading of the columns, purely a
                    // count of how many bullet lines Java's own renderer produced, for a clearer
                    // evidence label than a bare "retrieved" with no size indication.
                    int columnCount = countOccurrences(columns.get(), "    • `");
                    detail = columnCount > 0
                            ? "Retrieved " + columnCount + " column(s) of metadata for '" + req.object() + "'."
                            : "Retrieved metadata for '" + req.object() + "' (no columns on record).";
                    log.info("Step {} retrieved metadata for object '{}' (run '{}')", stepNo, req.object(), runKey);
                } else {
                    decision = "METADATA_UNAVAILABLE";
                    detail   = "'" + req.object() + "' does not exist in Zevra's enterprise metadata "
                            + "for this investigation's approved connections — metadata request rejected. "
                            + "Re-check the exact table name or business name shown for the intended "
                            + "object under \"Approved schema\" and retry with that exact identity — "
                            + "not a Knowledge Graph entity or concept label.";
                    log.info("Step {} metadata request for object '{}' rejected — not found in the "
                            + "resolved scope or the enterprise metadata catalog (run '{}')",
                            stepNo, req.object(), runKey);
                }
                evidence.addMetadataStep(stepNo, plan.description(), plan.rationale(), decision, detail);
                saveStep(sessionKey, stepNo, plan, decision, detail, evidence, List.of(), null);
                eventBus.publish(runKey, "step_metadata_request",
                        Map.of("stepNo", stepNo, "object", req.object(), "decision", decision));
                stepNo++;
                continue;
            }

            log.info("Reasoning step {}/{} for run '{}': {}", stepNo, MAX_STEPS, runKey, plan.description());
            eventBus.publish(runKey, "step_started", Map.of(
                    "stepNo",      stepNo,
                    "description", plan.description()));

            // ── 2. Deterministic execution (Unified Answer Engine, Phase 1) ────
            //     The shared GovernedSqlRuntime owns literal validation (PRO-33),
            //     the connection existence check, the governance chain
            //     (SqlGovernancePipeline unchanged), execution, execution-record
            //     tracking, and audit — in that order, exactly as this engine
            //     sequenced them inline before Phase 1. This engine keeps reasoning,
            //     evidence, evaluation, PRO-33 capture, event publishing, and loop
            //     control — behaviour identical to the prior inline chain.
            GovernedSqlRuntime.Outcome ro = runtime.execute(
                    GovernedSqlRuntime.Request.forPlanner(runKey, stepNo, plan.connectionKey(),
                            plan.objectKeys(), plan.sql(), userEmail, forceAsync,
                            literalScope, plan.literalBindings(), enrichedQ, literalRejections,
                            contract, enforceContractGate, conversationId, parentExecutionId));

            // Connection named by the planner does not exist — skip the step.
            if (ro.status() == GovernedSqlRuntime.Status.CONNECTION_NOT_FOUND) {
                log.warn("Step {} skipped — connection '{}' not found", stepNo, plan.connectionKey());
                eventBus.publish(runKey, "step_error", Map.of(
                        "stepNo", stepNo,
                        "reason", ro.message()));
                break;
            }

            // Business-object gate (ADR-0003 A14) — the referenced table is outside the
            // approved surface. Like the agent path, this is a deterministic, physical
            // observation the planner can re-plan against rather than a hard failure.
            if (ro.status() == GovernedSqlRuntime.Status.UNAPPROVED_OBJECTS) {
                String reason = "Query references table(s) not in the approved execution contract: "
                        + ro.unapprovedTables() + ". Approved tables: [" + ro.approvedTables() + "].";
                log.info("Step {} gated for run '{}': {}", stepNo, runKey, reason);
                evidence.addOutcome(stepNo, plan.description(), plan.sql(), plan.connectionKey(),
                        plan.rationale(), "UNAPPROVED_OBJECTS", reason, 0L);
                saveStep(sessionKey, stepNo, plan, "UNAPPROVED_OBJECTS", reason,
                        evidence, List.of(), null);
                eventBus.publish(runKey, "step_error", Map.of("stepNo", stepNo, "reason", reason));
                stepNo++;
                continue;   // the planner sees the approved list and re-plans
            }

            // Column-existence gate (defense-in-depth) — a referenced column does not exist on
            // its table. Same re-plannable treatment as UNAPPROVED_OBJECTS: a deterministic,
            // physical observation, never a fatal fault — the planner sees the exact invalid
            // reference(s) and can re-plan (e.g. via a metadata request, or by using a column it
            // already has) rather than the query reaching the database and failing there.
            if (ro.status() == GovernedSqlRuntime.Status.INVALID_COLUMNS) {
                log.info("Step {} gated for run '{}': {}", stepNo, runKey, ro.message());
                evidence.addOutcome(stepNo, plan.description(), plan.sql(), plan.connectionKey(),
                        plan.rationale(), "INVALID_COLUMNS", ro.message(), 0L);
                saveStep(sessionKey, stepNo, plan, "INVALID_COLUMNS", ro.message(), evidence, List.of(), null);
                eventBus.publish(runKey, "step_error", Map.of("stepNo", stepNo, "reason", ro.message()));
                stepNo++;
                continue;   // the planner sees which column(s) were invalid and re-plans
            }

            // Shadow mode: the gate would have rejected this step but is not enforcing.
            // Recorded for migration measurement only — execution proceeds unchanged.
            if (!ro.unapprovedTables().isEmpty()) {
                shadowGateFindings.add("step " + stepNo + ": " + ro.unapprovedTables()
                        + " not in approved surface (approved: [" + ro.approvedTables() + "])");
            }

            // Literal hard block — a repeat violation on an authoritative domain.
            if (ro.status() == GovernedSqlRuntime.Status.LITERAL_BLOCKED) {
                evidence.addOutcome(stepNo, plan.description(), plan.sql(), plan.connectionKey(),
                        plan.rationale(), "LITERAL_BLOCKED", ro.message(), 0L);
                saveStep(sessionKey, stepNo, plan, "LITERAL_BLOCKED", ro.message(),
                        evidence, List.of(), null);
                eventBus.publish(runKey, "step_blocked",
                        Map.of("stepNo", stepNo, "reason", ro.message()));
                break;
            }

            // Literal reject — rejects-never-rewrites: the exact legal list returns
            // through this same loop as evidence for one bounded re-plan.
            if (ro.status() == GovernedSqlRuntime.Status.LITERAL_REJECTED) {
                log.info("Step {} literal-rejected for run '{}': {}", stepNo, runKey, ro.message());
                evidence.addOutcome(stepNo, plan.description(), plan.sql(), plan.connectionKey(),
                        plan.rationale(), "LITERAL_REJECTED", ro.message(), 0L);
                saveStep(sessionKey, stepNo, plan, "LITERAL_REJECTED", ro.message(),
                        evidence, List.of(), null);
                eventBus.publish(runKey, "step_error",
                        Map.of("stepNo", stepNo, "reason", ro.message()));
                stepNo++;
                continue;   // planner sees the legal list and retries
            }

            // Governance BLOCK (safety / allow-list / joins) — not audited today.
            if (ro.status() == GovernedSqlRuntime.Status.GOVERNANCE_BLOCKED) {
                evidence.addOutcome(stepNo, plan.description(), plan.sql(), plan.connectionKey(),
                        plan.rationale(), "BLOCKED", ro.message(), 0L);
                saveStep(sessionKey, stepNo, plan, "BLOCKED", ro.message(), evidence, List.of(), null);
                eventBus.publish(runKey, "step_blocked", Map.of("stepNo", stepNo, "reason", ro.message()));
                break;
            }

            if (ro.status() == GovernedSqlRuntime.Status.ASYNC) {
                eventBus.publish(runKey, "step_async",
                        Map.of("stepNo", stepNo, "execution_key", ro.governance().executionKey()));
                break;
            }

            // Contract BLOCK — already audited (blocked=true) by the runtime.
            if (ro.status() == GovernedSqlRuntime.Status.CONTRACT_BLOCKED) {
                evidence.addOutcome(stepNo, plan.description(), plan.sql(), plan.connectionKey(),
                        plan.rationale(), "CONTRACT_BLOCKED", ro.message(), 0L);
                saveStep(sessionKey, stepNo, plan, "CONTRACT_BLOCKED", ro.message(),
                        evidence, List.of(), null);
                eventBus.publish(runKey, "step_blocked", Map.of("stepNo", stepNo, "reason", ro.message()));
                break;
            }

            // Execution failed — already audited by the runtime.
            if (ro.status() == GovernedSqlRuntime.Status.FAILED) {
                log.error("Step {} execution failed: {}", stepNo, ro.message(), ro.failure());
                evidence.addOutcome(stepNo, plan.description(), plan.sql(), plan.connectionKey(),
                        plan.rationale(), "ERROR", ro.message(), 0L);
                break;
            }

            // ── 4. Executed: evaluate the evidence and decide whether to continue ──
            List<Map<String, Object>> rows = ro.rows();
            String rJson   = ro.rowsJson();
            long   elapsed = ro.executionMs();
            resultSnapshot = rJson;

            // ── PRO-33: declared bindings confirmed against a persisted
            // domain on a step that actually executed — trace + learning.
            if (literalScope != null && plan.literalBindings() != null) {
                for (ReasoningPlanner.LiteralBinding b : plan.literalBindings()) {
                    ColumnValueDomain d =
                            LiteralValidator.lookup(literalScope, b.column());
                    if (d != null && d.values().contains(b.value())) {
                        ValidatedBinding vb = new ValidatedBinding(
                                b.surface(), d.qualifiedColumn(), b.value(), d.authoritative());
                        if (!validatedBindings.contains(vb)) validatedBindings.add(vb);
                    }
                }
            }

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

            stepNo++;
        }

        // LEGACY COMPATIBILITY PATH — NOT the authoritative UI-content mechanism. This is the
        // pre-existing single-dataset selection heuristic (last-SUFFICIENT-step, else most rows),
        // kept ONLY so ChatResponse.queryData (and its existing consumers: the frontend's
        // DataTable/DataViz/SuggestedQuestions, ScheduledReportService/ReportHtmlComposer) keep
        // working unchanged. It is exactly the kind of Java-side semantic dataset selection the
        // sections-based contract exists to replace — see StructuredAnswer.Section /
        // ChatService#resolveSections, which is the authoritative mechanism going forward and
        // does NOT read this field or this method's selection logic at all; `investigationDatasets`
        // below (every row-bearing step, unfiltered, unselected) is what the sections contract
        // is built from. Do not extend this heuristic further; do not let any new capability
        // depend on it. Candidate for removal once the frontend migrates to rendering `sections`.
        List<Map<String, Object>> queryData = evidence.getSteps().stream()
                .filter(s -> "SUFFICIENT".equals(s.evaluatorDecision()))
                .reduce((first, last) -> last)
                .map(s -> s.rows().size() > 100 ? s.rows().subList(0, 100) : s.rows())
                .orElseGet(() -> evidence.getSteps().stream()
                        .filter(s -> !s.rows().isEmpty())
                        .max(Comparator.comparingInt(s -> s.rows().size()))
                        .map(s -> s.rows().size() > 100 ? s.rows().subList(0, 100) : s.rows())
                        .orElse(List.of()));

        // Information-preservation fix: every row-bearing step, independent of the single
        // "primary visualisation" selection above — mechanically copied from EvidenceStore, in
        // step order, with no filtering by evaluatorDecision, no ranking, and no merging of one
        // step's rows into another's. A step whose evaluator verdict was NEED_MORE_DATA is
        // preserved here exactly like a SUFFICIENT step — its own query still succeeded (see
        // EvidenceStore's outcome-vs-evaluatorDecision javadoc). Which of these datasets answers
        // which part of the question remains entirely Agent Brain's concern; Java only stops
        // discarding evidence it already has.
        List<InvestigationDataset> investigationDatasets = evidence.getSteps().stream()
                .filter(s -> !s.rows().isEmpty())
                .map(s -> new InvestigationDataset(s.stepNo(), s.description(),
                        s.rows().size() > 100 ? s.rows().subList(0, 100) : s.rows()))
                .toList();

        // Update session with final stats
        boolean crossSource = evidence.connectionKeys().size() > 1;
        reasoningRepository.updateSessionStatus(sessionKey, "CONCLUDED", null, 0.8, Instant.now());

        return new ReasoningResult(evidence, queryData, investigationDatasets, resultSnapshot, crossSource,
                List.copyOf(validatedBindings), List.copyOf(shadowGateFindings));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Mechanical substring-occurrence count — used only to size the metadata-retrieved evidence
     *  label from Java's own fixed rendering format, never to interpret column meaning. */
    private static int countOccurrences(String text, String marker) {
        if (text == null || marker.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(marker, idx)) != -1) {
            count++;
            idx += marker.length();
        }
        return count;
    }

    /** Parses an ExecutionReference's resultJson back into rows for evidence seeding. */
    private List<Map<String, Object>> parseRows(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(resultJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse prior execution result for evidence seeding: {}", e.getMessage());
            return List.of();
        }
    }

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
                    null, 0.0, null, Instant.now(),
                    evaluatorDecision, evaluatorRationale);
            reasoningRepository.saveStep(step);
        } catch (Exception e) {
            log.warn("Failed to persist reasoning step: {}", e.getMessage());
        }
    }

}
