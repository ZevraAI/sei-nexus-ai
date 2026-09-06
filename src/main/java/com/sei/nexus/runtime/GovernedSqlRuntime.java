package com.sei.nexus.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agentbrain.ExecutionBindings;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.common.Keys;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.governance.GovernanceAuditService;
import com.sei.nexus.governance.GovernanceOutcome;
import com.sei.nexus.governance.SqlGovernancePipeline;
import com.sei.nexus.query.QueryExecutionRepository;
import com.sei.nexus.reasoning.LiteralValidator;
import com.sei.nexus.reasoning.ReasoningPlanner;
import com.sei.nexus.semanticmodel.ColumnValueDomain;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.sql.SqlColumnReferenceExtractor;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single deterministic execution path for every governed SQL statement in Zevra
 * (Unified Answer Engine, Phase 1). It is the sole owner of, in order:
 *
 * <ol>
 *   <li><b>Literal validation</b> — the PRO-33 deterministic gate over persisted value domains.</li>
 *   <li><b>Connection existence</b> — for policies where the model names the connection.</li>
 *   <li><b>Business-object validation</b> — the ADR-0003 A14 gate: every table referenced by the
 *       SQL must be in the {@link ExecutionContract}'s approved surface (pure set membership).</li>
 *   <li><b>SQL governance</b> — delegated to {@link SqlGovernancePipeline}, which is
 *       <b>unchanged</b> (ADR-0003 A2).</li>
 *   <li><b>SQL execution</b> — via {@link DynamicSqlService}.</li>
 *   <li><b>Audit logging</b> — via {@link GovernanceAuditService}.</li>
 * </ol>
 *
 * <p>This stage order reproduces both pre-existing paths exactly: the conversational path
 * validated literals before checking the connection, and the agent path ran the contract gate
 * immediately before governance. Stages that a policy does not engage are inert.
 *
 * <p>This component makes deterministic <em>decisions</em> and performs their side effects; it
 * never controls a caller's loop. It returns an {@link Outcome} that the calling execution policy
 * (agent ReAct loop, conversational planner loop) interprets in its own vocabulary — tool
 * observations, evidence entries, SSE events. It performs no business reasoning and derives
 * nothing from persistence.
 *
 * <p><b>Behaviour preservation.</b> The two pre-existing execution paths differed in three
 * respects, which are preserved exactly as request options rather than unified away:
 * {@code readOnly} execution (agent only, ADR-0003 A1), {@code trackExecution} of the
 * {@code nexus_query_execution} record (conversational only), and the gate/literal stages, which
 * activate only when a contract / literal scope is supplied.
 */
@Component
public class GovernedSqlRuntime {

    private static final Logger log = LoggerFactory.getLogger(GovernedSqlRuntime.class);

    private final SqlGovernancePipeline    pipeline;
    private final DynamicSqlService        dynamicSql;
    private final GovernanceAuditService   auditService;
    private final SqlTableReferenceExtractor tableExtractor;
    private final SqlColumnReferenceExtractor columnExtractor;
    private final QueryExecutionRepository executionRepository;
    private final ConnectionRepository     connectionRepository;
    private final ExecutionReferenceRepository executionReferenceRepository;
    private final ObjectMapper             objectMapper;

    public GovernedSqlRuntime(SqlGovernancePipeline pipeline,
                              DynamicSqlService dynamicSql,
                              GovernanceAuditService auditService,
                              SqlTableReferenceExtractor tableExtractor,
                              SqlColumnReferenceExtractor columnExtractor,
                              QueryExecutionRepository executionRepository,
                              ConnectionRepository connectionRepository,
                              ExecutionReferenceRepository executionReferenceRepository,
                              ObjectMapper objectMapper) {
        this.pipeline             = pipeline;
        this.dynamicSql           = dynamicSql;
        this.auditService         = auditService;
        this.tableExtractor       = tableExtractor;
        this.columnExtractor      = columnExtractor;
        this.executionRepository  = executionRepository;
        this.connectionRepository = connectionRepository;
        this.executionReferenceRepository = executionReferenceRepository;
        this.objectMapper         = objectMapper;
    }

    /** What the deterministic path decided. The caller maps this to its own vocabulary. */
    public enum Status {
        /** A literal violated a persisted domain; re-plannable once. Nothing was governed. */
        LITERAL_REJECTED,
        /** A repeat literal violation on an authoritative domain. Nothing was governed. */
        LITERAL_BLOCKED,
        /** The requested connection does not exist. Nothing was governed. */
        CONNECTION_NOT_FOUND,
        /** A referenced table is outside the contract's approved surface. Nothing was governed. */
        UNAPPROVED_OBJECTS,
        /** A referenced column does not exist on its (approved) table. Nothing was governed. */
        INVALID_COLUMNS,
        /** Governance blocked at safety/allow-list level (no contract evaluation). Not audited. */
        GOVERNANCE_BLOCKED,
        /** Governance blocked at contract level. Audited with {@code blocked=true}. */
        CONTRACT_BLOCKED,
        /** Governance routed the statement to asynchronous execution. */
        ASYNC,
        /** Executed successfully and audited. */
        EXECUTED,
        /** Execution threw; audited with null row count. */
        FAILED
    }

    /**
     * One governed-SQL request. Use the {@link #forAgent} / {@link #forPlanner} factories — they
     * encode the two execution policies in use today.
     */
    public record Request(
            String runKey,
            int stepNo,
            String connectionKey,
            String objectKeys,
            String sql,
            String userEmail,
            boolean forceAsync,
            boolean readOnly,
            boolean trackExecution,
            boolean requireConnectionExists,
            ExecutionContract contract,
            boolean enforceContractGate,
            Map<String, ColumnValueDomain> literalScope,
            List<ReasoningPlanner.LiteralBinding> literalBindings,
            String questionText,
            Set<String> literalRejections,
            // Execution Continuity: carried through so the ExecutionReference records them; both
            // nullable. Runtime records parentExecutionId verbatim — it never decides lineage.
            String conversationId,
            String parentExecutionId) {

        /**
         * Autonomous-agent policy: contract gate on, literal validation off (no domain scope
         * today), read-only execution (A1), no execution-record tracking. The connection is
         * pre-checked against the agent's allow-list by the caller, so no existence check here.
         */
        public static Request forAgent(String runKey, int stepNo, String connectionKey, String sql,
                                       String userEmail, ExecutionContract contract,
                                       String conversationId, String parentExecutionId) {
            return new Request(runKey, stepNo, connectionKey, "", sql, userEmail,
                    false, true, false, false, contract, true, null, null, null, null,
                    conversationId, parentExecutionId);
        }

        /**
         * Conversational-planner policy: literal validation on, connection existence checked
         * (the planner names the connection itself), execution-record tracking on, governance
         * may route async.
         *
         * <p>The business-object gate is engaged only when a {@code contract} is supplied
         * (Phase 3 Step 1 compiles one). {@code enforceContractGate} selects the migration mode:
         * {@code false} shadows the verdict onto the outcome without changing behaviour,
         * {@code true} rejects as the agent policy does.
         */
        public static Request forPlanner(String runKey, int stepNo, String connectionKey,
                                         String objectKeys, String sql, String userEmail,
                                         boolean forceAsync,
                                         Map<String, ColumnValueDomain> literalScope,
                                         List<ReasoningPlanner.LiteralBinding> literalBindings,
                                         String questionText,
                                         Set<String> literalRejections,
                                         ExecutionContract contract,
                                         boolean enforceContractGate,
                                         String conversationId, String parentExecutionId) {
            return new Request(runKey, stepNo, connectionKey, objectKeys, sql, userEmail,
                    forceAsync, false, true, true, contract, enforceContractGate,
                    literalScope, literalBindings, questionText, literalRejections,
                    conversationId, parentExecutionId);
        }
    }

    /**
     * The deterministic result. {@code message} carries the caller-facing reason for every
     * non-executed status; {@code governance} is present once the pipeline ran.
     */
    public record Outcome(
            Status status,
            String message,
            GovernanceOutcome governance,
            List<Map<String, Object>> rows,
            String rowsJson,
            long executionMs,
            List<String> unapprovedTables,
            String approvedTables,
            Exception failure,
            // Execution Continuity: the immutable record of what executed — present only on
            // EXECUTED, null for every non-executed outcome. Returned alongside the Outcome.
            ExecutionReference executionReference) {

        public boolean isExecuted() { return status == Status.EXECUTED; }
    }

    /**
     * Runs the deterministic path end to end. Never throws for an expected verdict — every
     * decision, including execution failure, is returned as an {@link Outcome}.
     */
    public Outcome execute(Request r) {
        // ── 1. Deterministic literal validation (PRO-33) — only with a domain scope; fails open ──
        //     Runs first, before any connection or contract check, exactly as the conversational
        //     path ordered these stages before Phase 1.
        if (r.literalScope() != null && !r.literalScope().isEmpty()) {
            try {
                LiteralValidator.Result lit = LiteralValidator.validate(
                        r.sql(), r.literalBindings(), r.literalScope(), r.questionText());
                if (!lit.valid()) {
                    LiteralValidator.RejectionDecision d =
                            LiteralValidator.decide(lit.violations(), r.literalRejections());
                    if (d.hardBlock()) return decision(Status.LITERAL_BLOCKED, d.message(), null, List.of(), null);
                    if (d.reject())    return decision(Status.LITERAL_REJECTED, d.message(), null, List.of(), null);
                    // all violations advisory-exhausted: execute honestly
                }
            } catch (Exception ve) {
                log.warn("Literal validation failed open for step {}: {}", r.stepNo(), ve.getMessage());
            }
        }

        // ── 2. Connection existence — only for policies that let the model name the connection ──
        if (r.requireConnectionExists()
                && connectionRepository.findByKeyOrName(r.connectionKey()).isEmpty()) {
            return decision(Status.CONNECTION_NOT_FOUND,
                    "Connection '" + r.connectionKey() + "' not found", null, List.of(), null);
        }

        // ── 3. Business-object gate (ADR-0003 A14) — only when a contract scopes the request ──
        //     When the policy enforces, an unapproved reference is a deterministic verdict. When
        //     it only shadows, the finding is recorded on the outcome and the statement proceeds
        //     — so a migrating experience can measure what the gate *would* reject before it does.
        List<String> shadowUnapproved = List.of();
        String       shadowApproved   = null;
        if (r.contract() != null) {
            Set<String> referenced = tableExtractor.referencedTables(r.sql());
            List<String> unapproved = referenced.stream()
                    .filter(t -> !r.contract().executionBindings().isApproved(r.connectionKey(), t))
                    .toList();
            if (!unapproved.isEmpty()) {
                String approved = r.contract().executionBindings().objectBindings().values().stream()
                        .filter(t -> r.connectionKey().equals(t.connectionKey()))
                        .map(ExecutionBindings.ExecutionTarget::table)
                        .distinct().sorted().collect(Collectors.joining(", "));
                if (r.enforceContractGate()) {
                    return decision(Status.UNAPPROVED_OBJECTS, null, null, unapproved, approved);
                }
                shadowUnapproved = unapproved;
                shadowApproved   = approved;
                log.warn("Contract gate (shadow) would reject step {} of run {}: table(s) {} not in "
                                + "the approved surface for connection '{}'. Approved: [{}]",
                        r.stepNo(), r.runKey(), unapproved, r.connectionKey(), approved);
            }
        }

        // ── 3b. Column-existence gate (defense-in-depth) — only when a contract scopes the
        //     request, sibling to the table gate above. Deterministic identifier verification,
        //     never semantic column selection: rejects a column that does not exist on its
        //     table, never substitutes or guesses a replacement. Always enforced (not a shadow
        //     mode) — an invalid column reaching the database is exactly the failure mode this
        //     gate exists to catch before execution.
        if (r.contract() != null) {
            Map<String, Set<String>> knownColumnsByTable = r.contract().columnsByTable(r.connectionKey());
            List<String> invalidColumns = columnExtractor.invalidColumnReferences(r.sql(), knownColumnsByTable);
            if (!invalidColumns.isEmpty()) {
                String message = "Column(s) not found on their referenced table(s) for connection '"
                        + r.connectionKey() + "': " + invalidColumns + ". Re-check the exact column names "
                        + "approved for that table.";
                log.warn("Step {} of run {} rejected — invalid column reference(s): {}",
                        r.stepNo(), r.runKey(), invalidColumns);
                return decision(Status.INVALID_COLUMNS, message, null, invalidColumns, null);
            }
        }

        // ── 4. Governance chain — SqlGovernancePipeline is unchanged (ADR-0003 A2) ────────────
        GovernanceOutcome gov = pipeline.governSql(
                r.runKey(), r.stepNo(), r.connectionKey(),
                r.objectKeys() == null ? "" : r.objectKeys(),
                r.sql(), r.userEmail(), r.forceAsync());
        List<String> objKeys = parseKeys(r.objectKeys());

        if (gov.isBlocked()) {
            // Contract blocks are audited (blocked=true); govern-level safety/allow-list
            // blocks are not — exactly as both prior paths behaved.
            if (gov.contractOutcome() != null) {
                auditService.recordOutcome(gov, r.userEmail(), r.runKey(), r.connectionKey(),
                        objKeys, null, null, true);
                return decision(Status.CONTRACT_BLOCKED, gov.reason(), gov, shadowUnapproved, shadowApproved);
            }
            return decision(Status.GOVERNANCE_BLOCKED, gov.reason(), gov, shadowUnapproved, shadowApproved);
        }

        if (gov.isAsync()) {
            if (r.trackExecution()) {
                executionRepository.updateStatus(gov.executionKey(), "QUEUED", null, null, null);
            }
            return decision(Status.ASYNC, null, gov, shadowUnapproved, shadowApproved);
        }

        // ── 5. Execute the governed SQL, then audit ───────────────────────────────────────────
        long startMs = System.currentTimeMillis();
        try {
            if (r.trackExecution()) {
                executionRepository.updateStatus(gov.executionKey(), "RUNNING", Instant.now(), null, null);
            }
            List<Map<String, Object>> rows = dynamicSql.executeQuery(
                    r.connectionKey(), gov.governedSql(), gov.rowLimit(), r.readOnly());
            String rowsJson = objectMapper.writeValueAsString(rows);
            if (r.trackExecution()) {
                executionRepository.updateResult(gov.executionKey(), rowsJson, "SUCCESS", Instant.now());
            }
            long elapsed = System.currentTimeMillis() - startMs;
            auditService.recordOutcome(gov, r.userEmail(), r.runKey(), r.connectionKey(),
                    objKeys, rows.size(), (int) elapsed, false);
            // Execution Continuity: record the immutable ExecutionReference — the semantic snapshot
            // is COPIED verbatim from the contract; the rest are facts Runtime observed. Persisted
            // independently of the trackExecution lifecycle so both policies emit it. Best-effort:
            // a persistence hiccup must never fail an otherwise-successful execution.
            ExecutionReference reference =
                    buildExecutionReference(r, gov, rows, rowsJson, Instant.ofEpochMilli(startMs), elapsed);
            try {
                executionReferenceRepository.save(reference);
            } catch (Exception persistEx) {
                log.warn("ExecutionReference persist failed for run {} (execution still returned): {}",
                        r.runKey(), persistEx.getMessage());
            }
            return new Outcome(Status.EXECUTED, null, gov, rows, rowsJson, elapsed,
                    shadowUnapproved, shadowApproved, null, reference);
        } catch (Exception ex) {
            if (r.trackExecution()) {
                executionRepository.updateStatus(gov.executionKey(), "FAILED", null,
                        Instant.now(), ex.getMessage());
            }
            auditService.recordOutcome(gov, r.userEmail(), r.runKey(), r.connectionKey(),
                    objKeys, null, null, false);
            return new Outcome(Status.FAILED, ex.getMessage(), gov, List.of(), null, 0L,
                    shadowUnapproved, shadowApproved, ex, null);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private static Outcome decision(Status status, String message, GovernanceOutcome gov,
                                    List<String> unapproved, String approved) {
        return new Outcome(status, message, gov, List.of(), null, 0L, unapproved, approved, null, null);
    }

    /**
     * Assembles the immutable {@link ExecutionReference} for a successful execution. The semantic
     * snapshot is <b>copied verbatim</b> from the contract (never derived); the remaining fields are
     * facts Runtime observed. No comparison to any prior execution occurs here.
     */
    private static ExecutionReference buildExecutionReference(Request r, GovernanceOutcome gov,
            List<Map<String, Object>> rows, String rowsJson, Instant startedAt, long elapsed) {
        List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());

        // ── Semantic snapshot: copied from the ExecutionContract, unchanged ──
        String contractId = null, semanticHash = null;
        List<String> retrievalTargets = List.of();
        Map<String, String> objectBindings = Map.of();
        Map<String, String> attributeBindings = Map.of();
        List<String> approvedAssets = List.of();
        if (r.contract() != null) {
            contractId   = r.contract().contractId();
            semanticHash = r.contract().semanticHash();
            ExecutionBindings b = r.contract().executionBindings();
            objectBindings = new LinkedHashMap<>();
            for (Map.Entry<String, ExecutionBindings.ExecutionTarget> e : b.objectBindings().entrySet()) {
                objectBindings.put(e.getKey(), qualifiedTable(e.getValue()));
            }
            retrievalTargets = objectBindings.values().stream().distinct().collect(Collectors.toList());
            attributeBindings = new LinkedHashMap<>();
            for (Map.Entry<String, ExecutionBindings.ExecutionTarget> e : b.attributeBindings().entrySet()) {
                ExecutionBindings.ExecutionTarget t = e.getValue();
                attributeBindings.put(e.getKey(), qualifiedTable(t)
                        + (t.column() != null ? "." + t.column() : ""));
            }
            approvedAssets = b.approvedAssets().stream()
                    .map(a -> a.connectionKey() + ":" + a.canonicalIdentifier())
                    .sorted().collect(Collectors.toList());
        }

        return new ExecutionReference(
                Keys.uniqueKey("exec"), r.parentExecutionId(), r.conversationId(), r.runKey(),
                r.connectionKey(), startedAt, Instant.now(), elapsed,
                gov != null ? gov.route() : null, rows.size(), columns, rowsJson,
                gov != null ? gov.governedSql() : null,
                contractId, semanticHash, retrievalTargets, objectBindings, attributeBindings, approvedAssets);
    }

    /** schema.table for a target (schema omitted when blank). Verbatim projection of approved bindings. */
    private static String qualifiedTable(ExecutionBindings.ExecutionTarget t) {
        String schema = t.schema();
        return (schema != null && !schema.isBlank()) ? schema + "." + t.table() : t.table();
    }

    /** Comma-separated object keys → list; blank yields an empty list (agent passes ""). */
    private static List<String> parseKeys(String keys) {
        if (keys == null || keys.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String k : keys.split(",")) {
            String t = k.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }
}
