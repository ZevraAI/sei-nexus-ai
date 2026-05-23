package com.sei.nexus.governance;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

/**
 * Validates that every LLM-generated SQL statement satisfies the data contracts
 * defined for the tables it queries, before the statement is executed.
 *
 * <p>Four contract rule types are enforced:
 * <ul>
 *   <li>REQUIRE_DATE_FILTER   — WHERE clause must reference at least one date column</li>
 *   <li>REQUIRE_COLUMN_FILTER — WHERE clause must reference a specific column</li>
 *   <li>REQUIRE_LIMIT         — SQL must contain a LIMIT clause</li>
 *   <li>BLOCK_FULL_SCAN       — SQL must have a WHERE clause</li>
 * </ul>
 *
 * <p>Three enforcement modes are supported per contract:
 * <ul>
 *   <li>BLOCK          — reject the query and surface a user-friendly explanation</li>
 *   <li>WARN           — allow execution but record the violation in the audit log</li>
 *   <li>AUTO_REMEDIATE — rewrite the SQL to add the missing constraint and proceed</li>
 * </ul>
 */
@Service
public class DataContractService {

    private static final Logger log = LoggerFactory.getLogger(DataContractService.class);

    // Default date range added when auto-remediating a missing date filter
    private static final int DEFAULT_REMEDIATION_DAYS = 30;

    private final DataContractRepository contractRepository;

    public DataContractService(DataContractRepository contractRepository) {
        this.contractRepository = contractRepository;
    }

    /**
     * Evaluate all active data contracts for the given object keys against the SQL.
     *
     * @param sql        SQL statement to validate.
     * @param objectKeys Data object keys referenced by the query.
     * @return {@link ContractResult} describing which contracts passed, which violated,
     *         and the effective SQL to execute (may be rewritten if AUTO_REMEDIATE fired).
     */
    public ContractResult evaluate(String sql, List<String> objectKeys) {
        if (sql == null || sql.isBlank()) return ContractResult.passed(List.of());

        // Load all active contracts — do not filter by objectKeys.
        // The LLM planner may omit object_keys; contracts must still be enforced.
        List<DataContract> contracts = contractRepository.findAll().stream()
                .filter(DataContract::isActive)
                .toList();
        if (contracts.isEmpty()) return ContractResult.passed(List.of());

        List<String> checked      = new ArrayList<>();
        List<String> violated     = new ArrayList<>();
        List<String> messages     = new ArrayList<>();
        String       effectiveSql = sql;
        ContractResult.ContractStatus finalStatus = ContractResult.ContractStatus.PASSED;

        for (DataContract contract : contracts) {
            checked.add(contract.contractKey());
            ViolationCheck check = checkContract(effectiveSql, contract);

            if (!check.violated()) continue;

            violated.add(contract.contractKey());

            switch (contract.enforcement()) {
                case "BLOCK" -> {
                    messages.add(check.message());
                    finalStatus = ContractResult.ContractStatus.BLOCKED;
                    log.info("Data contract '{}' BLOCKED query on object '{}'",
                            contract.contractName(), contract.objectKey());
                }
                case "WARN" -> {
                    messages.add(check.message());
                    if (finalStatus == ContractResult.ContractStatus.PASSED) {
                        finalStatus = ContractResult.ContractStatus.WARNED;
                    }
                    log.warn("Data contract '{}' WARNED for object '{}': {}",
                            contract.contractName(), contract.objectKey(), check.message());
                }
                case "AUTO_REMEDIATE" -> {
                    String remediated = remediate(effectiveSql, contract);
                    if (remediated != null) {
                        effectiveSql = remediated;
                        if (finalStatus == ContractResult.ContractStatus.PASSED) {
                            finalStatus = ContractResult.ContractStatus.REMEDIATED;
                        }
                        log.info("Data contract '{}' AUTO_REMEDIATED query on object '{}'",
                                contract.contractName(), contract.objectKey());
                    } else {
                        // Could not remediate — fall back to warn
                        messages.add(check.message() + " (auto-remediation not possible for this query)");
                        if (finalStatus == ContractResult.ContractStatus.PASSED) {
                            finalStatus = ContractResult.ContractStatus.WARNED;
                        }
                    }
                }
            }

            // If already blocked, no point continuing
            if (finalStatus == ContractResult.ContractStatus.BLOCKED) break;
        }

        String remediatedSql = finalStatus == ContractResult.ContractStatus.REMEDIATED ? effectiveSql : null;
        return new ContractResult(finalStatus, checked, violated, messages, remediatedSql);
    }

    // ── Contract evaluation ───────────────────────────────────────────────────

    private ViolationCheck checkContract(String sql, DataContract contract) {
        return switch (contract.ruleType()) {
            case "REQUIRE_DATE_FILTER"   -> checkDateFilter(sql, contract);
            case "REQUIRE_COLUMN_FILTER" -> checkColumnFilter(sql, contract);
            case "REQUIRE_LIMIT"         -> checkLimit(sql, contract);
            case "BLOCK_FULL_SCAN"       -> checkFullScan(sql, contract);
            default -> ViolationCheck.pass();
        };
    }

    /**
     * Verifies the SQL has a WHERE condition referencing at least one of the
     * configured date columns.
     */
    private ViolationCheck checkDateFilter(String sql, DataContract contract) {
        JsonNode config   = contract.ruleConfig();
        JsonNode colsNode = config.get("columns");
        if (colsNode == null || !colsNode.isArray() || colsNode.size() == 0) {
            return ViolationCheck.pass(); // no columns configured → not enforceable
        }

        String whereClause = extractWhereClause(sql);
        if (whereClause == null) {
            return ViolationCheck.fail(
                    "Query on '" + contract.objectKey() + "' must include a date filter on one of: "
                    + streamTextValues(colsNode) + ". Add a WHERE condition on a date column.");
        }

        for (JsonNode col : colsNode) {
            if (containsColumnReference(whereClause, col.asText())) {
                return ViolationCheck.pass();
            }
        }

        return ViolationCheck.fail(
                "Query on '" + contract.objectKey() + "' must filter on at least one date column: "
                + streamTextValues(colsNode) + ". This prevents accidental full-history scans.");
    }

    /**
     * Verifies the SQL filters on a specific required column.
     */
    private ViolationCheck checkColumnFilter(String sql, DataContract contract) {
        JsonNode config = contract.ruleConfig();
        String   col    = config.has("column") ? config.get("column").asText() : null;
        if (col == null || col.isBlank()) return ViolationCheck.pass();

        String whereClause = extractWhereClause(sql);
        if (whereClause == null || !containsColumnReference(whereClause, col)) {
            return ViolationCheck.fail(
                    "Query on '" + contract.objectKey() + "' must include a filter on '"
                    + col + "'. This is required for data isolation.");
        }
        return ViolationCheck.pass();
    }

    /**
     * Verifies the SQL contains a LIMIT clause.
     */
    private ViolationCheck checkLimit(String sql, DataContract contract) {
        if (!Pattern.compile("(?i)\\bLIMIT\\b").matcher(sql).find()) {
            JsonNode config  = contract.ruleConfig();
            int      maxRows = config.has("max_rows") ? config.get("max_rows").asInt(10000) : 10000;
            return ViolationCheck.fail(
                    "Query on '" + contract.objectKey() + "' must include a LIMIT clause"
                    + " (maximum " + maxRows + " rows). Add LIMIT to constrain result size.");
        }
        return ViolationCheck.pass();
    }

    /**
     * Verifies the SQL is not a bare SELECT without any WHERE clause.
     */
    private ViolationCheck checkFullScan(String sql, DataContract contract) {
        if (extractWhereClause(sql) == null) {
            return ViolationCheck.fail(
                    "Full table scan blocked on '" + contract.objectKey() + "'. "
                    + "A WHERE clause is required to query this table.");
        }
        return ViolationCheck.pass();
    }

    // ── Auto-remediation ─────────────────────────────────────────────────────

    /**
     * Attempts to automatically fix the contract violation by rewriting the SQL.
     * Returns null when the violation cannot be auto-fixed.
     */
    private String remediate(String sql, DataContract contract) {
        return switch (contract.ruleType()) {
            case "REQUIRE_LIMIT"       -> addLimit(sql, contract.ruleConfig());
            case "REQUIRE_DATE_FILTER" -> addDefaultDateFilter(sql, contract.ruleConfig());
            default -> null; // REQUIRE_COLUMN_FILTER and BLOCK_FULL_SCAN cannot be auto-remediated
        };
    }

    private String addLimit(String sql, JsonNode config) {
        if (Pattern.compile("(?i)\\bLIMIT\\b").matcher(sql).find()) return null;
        int maxRows = config.has("max_rows") ? config.get("max_rows").asInt(10000) : 10000;
        return sql.stripTrailing() + " LIMIT " + maxRows;
    }

    private String addDefaultDateFilter(String sql, JsonNode config) {
        JsonNode colsNode = config.get("columns");
        if (colsNode == null || !colsNode.isArray() || colsNode.size() == 0) return null;

        int days  = config.has("max_range_days") ? config.get("max_range_days").asInt(DEFAULT_REMEDIATION_DAYS)
                                                 : DEFAULT_REMEDIATION_DAYS;
        String col = colsNode.get(0).asText();

        // Use ANSI SQL date arithmetic that works across major databases
        String condition = col + " >= CURRENT_TIMESTAMP - INTERVAL '" + days + " days'";

        // Inject before GROUP BY / ORDER BY / LIMIT or at end
        Matcher breakMatcher = Pattern.compile(
                "(?i)\\b(GROUP\\s+BY|ORDER\\s+BY|LIMIT|OFFSET|HAVING)\\b").matcher(sql);

        if (Pattern.compile("(?i)\\bWHERE\\b").matcher(sql).find()) {
            if (breakMatcher.find()) {
                return sql.substring(0, breakMatcher.start()).stripTrailing()
                        + " AND (" + condition + ") " + sql.substring(breakMatcher.start());
            }
            return sql.stripTrailing() + " AND (" + condition + ")";
        } else {
            if (breakMatcher.find()) {
                return sql.substring(0, breakMatcher.start()).stripTrailing()
                        + " WHERE " + condition + " " + sql.substring(breakMatcher.start());
            }
            return sql.stripTrailing() + " WHERE " + condition;
        }
    }

    // ── SQL analysis helpers ──────────────────────────────────────────────────

    /**
     * Extracts the WHERE clause portion of a SQL statement at parenthesis depth 0.
     * Returns null if no WHERE clause is present.
     */
    private String extractWhereClause(String sql) {
        int depth = 0;
        int whereStart = -1;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }

            if (depth == 0 && whereStart < 0
                    && sql.regionMatches(true, i, "WHERE", 0, 5)) {
                boolean leftOk  = i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1));
                int end = i + 5;
                boolean rightOk = end >= sql.length() || !Character.isLetterOrDigit(sql.charAt(end));
                if (leftOk && rightOk) { whereStart = end; }
            }

            if (depth == 0 && whereStart >= 0) {
                // Detect end of WHERE: GROUP BY, ORDER BY, LIMIT, HAVING, UNION, end
                if (sql.regionMatches(true, i, "GROUP", 0, 5)
                 || sql.regionMatches(true, i, "ORDER", 0, 5)
                 || sql.regionMatches(true, i, "LIMIT", 0, 5)
                 || sql.regionMatches(true, i, "HAVING", 0, 6)
                 || sql.regionMatches(true, i, "UNION", 0, 5)) {
                    boolean leftOk = i == 0 || Character.isWhitespace(sql.charAt(i - 1));
                    if (leftOk) return sql.substring(whereStart, i);
                }
            }
        }
        if (whereStart >= 0) return sql.substring(whereStart);
        return null;
    }

    /** Returns true when the text contains a reference to the given column name. */
    private boolean containsColumnReference(String text, String columnName) {
        Pattern p = Pattern.compile(
                "(?i)(^|[^a-zA-Z0-9_])" + Pattern.quote(columnName) + "([^a-zA-Z0-9_]|$)");
        return p.matcher(text).find();
    }

    private String streamTextValues(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        arrayNode.forEach(n -> values.add(n.asText()));
        return String.join(", ", values);
    }

    // ── Violation result ──────────────────────────────────────────────────────

    private record ViolationCheck(boolean violated, String message) {
        static ViolationCheck pass()               { return new ViolationCheck(false, null); }
        static ViolationCheck fail(String message) { return new ViolationCheck(true, message); }
    }
}
