package com.sei.nexus.governance;

import com.sei.nexus.query.QueryGovernanceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The single owner of the platform's SQL governance enforcement pipeline.
 *
 * <p>Given a raw SQL statement, {@link #governSql} runs the fixed, ordered chain —
 * {@link QueryGovernanceService#govern govern} (safety, table allow-list, join cap,
 * classification, row limit, route) → {@link DataContractService#evaluate contract}
 * → {@link RowLevelSecurityService#apply RLS} → {@link ColumnMaskingService#apply
 * masking} — and returns the fully governed SQL together with the governance facts
 * needed downstream, as a {@link GovernanceOutcome}.
 *
 * <p><b>Boundary (owns / does not own):</b> this component owns <em>enforcement</em>
 * and produces the one governed SQL both reasoning and agent engines execute. It does
 * <b>not</b> execute SQL ({@code DynamicSqlService} owns that, including the read-only
 * flag), does <b>not</b> persist audit ({@code GovernanceAuditService} owns that — this
 * pipeline returns governance facts, not an {@code AuditContext}), and does <b>not</b>
 * own the execution-record status lifecycle, reasoning, evidence, evaluation, or event
 * publishing (their existing owners keep them).
 *
 * <p><b>Precondition:</b> a {@code nexus_run} row for {@code runKey} must already exist,
 * because govern inserts a {@code nexus_query_execution} row whose {@code run_key} is a
 * NOT NULL foreign key to {@code nexus_run}. Establishing the run is the caller's
 * responsibility; this pipeline never creates runs.
 */
@Service
public class SqlGovernancePipeline {

    private final QueryGovernanceService   governanceService;
    private final DataContractService      contractService;
    private final RowLevelSecurityService  rlsService;
    private final ColumnMaskingService     maskingService;
    private final UserAttributesRepository userAttributesRepository;

    public SqlGovernancePipeline(QueryGovernanceService governanceService,
                                 DataContractService contractService,
                                 RowLevelSecurityService rlsService,
                                 ColumnMaskingService maskingService,
                                 UserAttributesRepository userAttributesRepository) {
        this.governanceService        = governanceService;
        this.contractService          = contractService;
        this.rlsService               = rlsService;
        this.maskingService           = maskingService;
        this.userAttributesRepository = userAttributesRepository;
    }

    /**
     * Runs the full governance enforcement chain and returns the governed SQL plus
     * governance facts. Mirrors the conversational path's govern → contract → RLS →
     * masking sequence exactly; performs no execution and no audit persistence.
     */
    public GovernanceOutcome governSql(String runKey, int stepNo, String connectionKey,
                                       String objectKeys, String sql, String userEmail,
                                       boolean forceAsync) {

        String resolvedRole = userAttributesRepository.getRole(userEmail);

        var gov = governanceService.govern(runKey, stepNo, "", connectionKey,
                objectKeys, sql, forceAsync);

        // Route branching mirrors ReasoningEngine: only BLOCK and EXECUTE_ASYNC are
        // terminal here; EXECUTE_SYNC and ASK_FOR_FILTER fall through to enforcement.
        if ("BLOCK".equals(gov.route())) {
            return GovernanceOutcome.blocked(gov.decisionReason(), gov.approvedSql(),
                    gov.classification(), gov.route(), gov.rowLimit(), gov.executionKey(),
                    resolvedRole, null);
        }
        if ("EXECUTE_ASYNC".equals(gov.route())) {
            return GovernanceOutcome.async(gov.approvedSql(), gov.classification(), gov.route(),
                    gov.rowLimit(), gov.executionKey(), resolvedRole);
        }

        List<String> objKeys = parseKeys(objectKeys);

        ContractResult contract = contractService.evaluate(gov.approvedSql(), objKeys);
        if (contract.isBlocked()) {
            String reason = contract.violationMessages().isEmpty()
                    ? "Data contract blocked this query."
                    : String.join("; ", contract.violationMessages());
            return GovernanceOutcome.blocked(reason, gov.approvedSql(), gov.classification(),
                    gov.route(), gov.rowLimit(), gov.executionKey(), resolvedRole, contract);
        }

        String     effectiveSql = contract.effectiveSql(gov.approvedSql());
        RlsResult  rls          = rlsService.apply(effectiveSql, userEmail, objKeys);
        MaskResult mask         = maskingService.apply(rls.sql(), userEmail, objKeys);

        return GovernanceOutcome.execute(mask.sql(), gov.approvedSql(), gov.classification(),
                gov.route(), gov.rowLimit(), gov.executionKey(), resolvedRole, contract, rls, mask);
    }

    /** Splits a comma-joined object-key string into a trimmed, blank-free list. */
    private List<String> parseKeys(String objectKeys) {
        if (objectKeys == null || objectKeys.isBlank()) return List.of();
        List<String> keys = new ArrayList<>();
        for (String k : objectKeys.split(",")) {
            String trimmed = k.trim();
            if (!trimmed.isEmpty()) keys.add(trimmed);
        }
        return keys;
    }
}
