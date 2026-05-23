package com.sei.nexus.governance;

import java.time.Instant;

/**
 * Immutable record of a single query execution and all governance decisions
 * applied to it. Written asynchronously after every QUERY_LIVE_DATA step.
 *
 * <p>event_type values:
 * <pre>
 *   QUERY_EXECUTED     — query ran normally (may also have masking/RLS sub-events)
 *   COLUMN_MASKED      — at least one column was replaced or removed before return
 *   RLS_APPLIED        — at least one row-level filter was injected
 *   CONTRACT_VIOLATED  — a data contract check triggered (may be BLOCK or WARN)
 *   ACCESS_DENIED      — query was blocked entirely by a contract or safety rule
 * </pre>
 */
public record AuditEvent(
        String   eventKey,
        String   eventType,
        String   userEmail,
        String   userRole,
        String   runKey,
        String   connectionKey,
        String[] objectKeys,
        String[] columnsAccessed,
        String[] columnsMasked,
        String[] rlsPoliciesApplied,
        String[] contractsChecked,
        String[] contractsViolated,
        String   originalSql,
        String   executedSql,
        Integer  rowCountReturned,
        Integer  rowsFilteredByRls,
        Integer  executionMs,
        String   ipAddress,
        Instant  createdAt
) {}
