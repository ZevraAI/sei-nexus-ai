package com.sei.nexus.governance;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * A structural rule that every LLM-generated query against the associated
 * data object must satisfy before it is allowed to execute.
 *
 * <p>rule_type values and their rule_config schemas:
 * <pre>
 *   REQUIRE_DATE_FILTER
 *       {"columns":["created_at","updated_at"],"max_range_days":90}
 *       Query must have a WHERE condition on at least one of the listed date columns.
 *       Optionally, the date range must not exceed max_range_days.
 *
 *   REQUIRE_COLUMN_FILTER
 *       {"column":"tenant_id"}
 *       Query must have a WHERE condition on the specified column.
 *
 *   REQUIRE_LIMIT
 *       {"max_rows":10000}
 *       Query must have a LIMIT clause (value <= max_rows).
 *
 *   BLOCK_FULL_SCAN
 *       {}
 *       Query must have a WHERE clause; a bare SELECT without filtering is rejected.
 * </pre>
 *
 * <p>enforcement values:
 * <pre>
 *   BLOCK          — reject query and return a clear explanation to the user
 *   WARN           — log the violation but allow execution
 *   AUTO_REMEDIATE — rewrite the SQL to add the missing constraint and proceed
 * </pre>
 */
public record DataContract(
        String   contractKey,
        String   contractName,
        String   objectKey,
        String   ruleType,       // REQUIRE_DATE_FILTER | REQUIRE_COLUMN_FILTER | REQUIRE_LIMIT | BLOCK_FULL_SCAN
        JsonNode ruleConfig,     // parsed from rule_config JSONB column
        String   enforcement,    // BLOCK | WARN | AUTO_REMEDIATE
        boolean  isActive,
        String   createdBy,
        Instant  createdAt,
        Instant  updatedAt
) {}
