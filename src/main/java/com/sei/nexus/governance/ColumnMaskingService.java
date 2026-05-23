package com.sei.nexus.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

/**
 * Rewrites the SELECT clause of a SQL statement to apply column-level masking
 * policies before the query is executed.
 *
 * <p>Four masking strategies are supported:
 * <ul>
 *   <li>EXCLUDE  — column is removed from the SELECT list entirely</li>
 *   <li>HASH     — replaced with {@code MD5(CAST(col AS TEXT)) AS col}</li>
 *   <li>PARTIAL  — replaced with {@code LEFT(CAST(col AS TEXT), N) || '****' AS col}</li>
 *   <li>CONSTANT — replaced with {@code 'value' AS col}</li>
 * </ul>
 *
 * <p>The service operates on LLM-generated SQL which follows predictable
 * patterns. It handles:
 * <ul>
 *   <li>Simple columns: {@code email}</li>
 *   <li>Table-qualified: {@code u.email}, {@code users.email}</li>
 *   <li>Explicit aliases: {@code email AS user_email}</li>
 *   <li>Implicit aliases: {@code email user_email}</li>
 *   <li>Columns inside aggregate functions are left alone (the function result
 *       is what the user sees, not the raw column).</li>
 * </ul>
 */
@Service
public class ColumnMaskingService {

    private static final Logger log = LoggerFactory.getLogger(ColumnMaskingService.class);

    private final ColumnPolicyRepository    policyRepository;
    private final UserAttributesRepository  userAttributesRepository;

    public ColumnMaskingService(ColumnPolicyRepository policyRepository,
                                UserAttributesRepository userAttributesRepository) {
        this.policyRepository        = policyRepository;
        this.userAttributesRepository = userAttributesRepository;
    }

    /**
     * Apply all active column masking policies to a SQL statement.
     *
     * <p>Policies are loaded by loading <em>all</em> active policies for the tenant
     * and matching them against column names that appear in the SELECT clause.
     * We intentionally do NOT filter by the LLM-provided {@code objectKeys} because:
     * <ul>
     *   <li>The planner sometimes omits object_keys from its plan.</li>
     *   <li>The planner sometimes uses a wrong or invented key.</li>
     *   <li>For compliance, masking must fire regardless of planner accuracy.</li>
     * </ul>
     * The objectKeys parameter is accepted for API compatibility but is only used
     * as an optional hint — policies are always loaded from all active policies.
     *
     * @param sql         The SQL string to rewrite (must be a SELECT statement).
     * @param userEmail   The authenticated user — used to check role exemptions.
     * @param objectKeys  Hint only; not used to filter policy loading.
     */
    public MaskResult apply(String sql, String userEmail, List<String> objectKeys) {
        if (sql == null || sql.isBlank()) return MaskResult.passThrough(sql);

        // Always load all policies — do NOT filter by objectKeys.
        // Column name matching inside the SELECT clause is the real guard.
        List<ColumnPolicy> policies = policyRepository.findAll();
        if (policies.isEmpty()) return MaskResult.passThrough(sql);

        String userRole = userAttributesRepository.getRole(userEmail);

        // Only keep policies that apply to this user (they are not in the exempt list)
        List<ColumnPolicy> applicable = policies.stream()
                .filter(p -> !isExempt(userRole, p.exemptRoles()))
                .toList();

        if (applicable.isEmpty()) return MaskResult.passThrough(sql);

        // Parse the SELECT … FROM boundary
        SelectBounds bounds = findSelectBounds(sql);
        if (bounds == null) return MaskResult.passThrough(sql);

        String beforeSelect = sql.substring(0, bounds.selectStart());
        String selectClause = sql.substring(bounds.selectStart(), bounds.fromStart());
        String afterFrom    = sql.substring(bounds.fromStart());

        // Parse individual column expressions from the SELECT clause
        List<String> columns = splitColumns(selectClause);

        List<String> maskedNames = new ArrayList<>();
        List<String> rewritten   = new ArrayList<>();

        for (String colExpr : columns) {
            String trimmed = colExpr.trim();
            ColumnPolicy matchedPolicy = findMatchingPolicy(trimmed, applicable);

            if (matchedPolicy == null) {
                rewritten.add(colExpr);
                continue;
            }

            String masked = applyMask(trimmed, matchedPolicy);
            if (masked == null) {
                // EXCLUDE — drop the column
                maskedNames.add(matchedPolicy.columnName());
                log.debug("Excluded column '{}' for user '{}'", matchedPolicy.columnName(), userEmail);
            } else {
                rewritten.add(masked);
                maskedNames.add(matchedPolicy.columnName());
                log.debug("Masked column '{}' ({}) for user '{}'",
                        matchedPolicy.columnName(), matchedPolicy.maskType(), userEmail);
            }
        }

        // If all columns were excluded, substitute a safe sentinel so the query
        // still executes and returns a row count (useful for audit/reporting)
        if (rewritten.isEmpty()) {
            rewritten.add("'[all columns masked]' AS _masked");
        }

        String newSql = beforeSelect + String.join(", ", rewritten) + afterFrom;
        return new MaskResult(newSql, maskedNames, !maskedNames.isEmpty());
    }

    // ── SQL parsing helpers ───────────────────────────────────────────────────

    /**
     * Locates the start of the SELECT column list and the start of the FROM keyword.
     * Returns null when the statement is not a simple SELECT (e.g. CTEs, subqueries
     * in the outer select — the LLM rarely generates these).
     */
    private SelectBounds findSelectBounds(String sql) {
        Matcher selectMatcher = Pattern.compile("(?i)^\\s*SELECT\\s+").matcher(sql);
        if (!selectMatcher.find()) return null;
        int colListStart = selectMatcher.end();

        // Find FROM at depth 0 (not inside parentheses)
        int depth = 0;
        for (int i = colListStart; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (depth == 0 && sql.regionMatches(true, i, "FROM", 0, 4)) {
                // Confirm it is a word boundary (space or start)
                boolean prevOk = i == colListStart || Character.isWhitespace(sql.charAt(i - 1));
                boolean nextOk = i + 4 >= sql.length() || !Character.isLetterOrDigit(sql.charAt(i + 4));
                if (prevOk && nextOk) {
                    return new SelectBounds(colListStart, i);
                }
            }
        }
        return null;
    }

    /**
     * Splits a comma-separated SELECT column list into individual expressions,
     * correctly handling nested parentheses (functions, CASE expressions).
     */
    private List<String> splitColumns(String selectClause) {
        List<String> result = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < selectClause.length(); i++) {
            char c = selectClause.charAt(i);
            if (c == '(')       depth++;
            else if (c == ')')  depth--;
            else if (c == ',' && depth == 0) {
                result.add(selectClause.substring(start, i));
                start = i + 1;
            }
        }
        result.add(selectClause.substring(start));
        return result;
    }

    /**
     * Returns the first policy whose column name appears as a top-level
     * column reference in the expression (not inside a function call).
     */
    private ColumnPolicy findMatchingPolicy(String colExpr, List<ColumnPolicy> policies) {
        for (ColumnPolicy p : policies) {
            if (columnMatches(colExpr, p.columnName())) return p;
        }
        return null;
    }

    /**
     * Returns true when {@code colName} is a top-level reference in the
     * column expression — that is, it appears as:
     * <ul>
     *   <li>{@code colName}          — bare column</li>
     *   <li>{@code alias.colName}    — table-qualified column</li>
     *   <li>{@code colName AS ...}   — column with explicit alias</li>
     *   <li>{@code colName ...}      — column with implicit alias</li>
     * </ul>
     * and NOT as an argument inside a function call at depth > 0.
     */
    private boolean columnMatches(String colExpr, String colName) {
        // Walk at depth 0 only; at depth > 0 we are inside a function — skip
        int depth = 0;
        for (int i = 0; i < colExpr.length(); i++) {
            char c = colExpr.charAt(i);
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (depth > 0) continue;

            // At depth 0, try to match colName at position i (case-insensitive)
            if (colExpr.regionMatches(true, i, colName, 0, colName.length())) {
                // Verify left boundary: start of string, '.', or whitespace
                boolean leftOk = i == 0 || colExpr.charAt(i - 1) == '.'
                        || Character.isWhitespace(colExpr.charAt(i - 1));
                // Verify right boundary: end, whitespace, or AS keyword
                int end = i + colName.length();
                boolean rightOk = end >= colExpr.length()
                        || Character.isWhitespace(colExpr.charAt(end))
                        || colExpr.charAt(end) == ',';
                if (leftOk && rightOk) return true;
            }
        }
        return false;
    }

    /**
     * Returns the masked column expression, or {@code null} for EXCLUDE.
     * Always preserves the original column name as the alias so downstream
     * code and the user see a consistent column name.
     */
    private String applyMask(String colExpr, ColumnPolicy policy) {
        String col  = policy.columnName();
        String alias = deriveAlias(colExpr, col);

        return switch (policy.maskType()) {
            case "EXCLUDE"  -> null;
            case "HASH"     -> "MD5(CAST(" + col + " AS TEXT)) AS " + alias;
            case "PARTIAL"  -> "LEFT(CAST(" + col + " AS TEXT), " + policy.partialChars()
                               + ") || '****' AS " + alias;
            case "CONSTANT" -> {
                String safe = (policy.constantValue() != null ? policy.constantValue() : "REDACTED")
                        .replace("'", "''");
                yield "'" + safe + "' AS " + alias;
            }
            default -> colExpr; // unknown type — leave unchanged
        };
    }

    /**
     * Extracts the alias to use in the masked expression.
     * Precedence: explicit AS alias → implicit alias → column name itself.
     */
    private String deriveAlias(String colExpr, String colName) {
        // Explicit alias: "... AS alias_name"
        Matcher asMatcher = Pattern.compile("(?i)\\bAS\\s+(\\w+)\\s*$").matcher(colExpr.trim());
        if (asMatcher.find()) return asMatcher.group(1);

        // Implicit alias: last whitespace-separated word that is not the column
        String trimmed = colExpr.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 2) {
            String last = parts[parts.length - 1];
            if (!last.equalsIgnoreCase(colName) && last.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                return last;
            }
        }
        return colName;
    }

    private boolean isExempt(String userRole, String[] exemptRoles) {
        if (userRole == null || exemptRoles == null || exemptRoles.length == 0) return false;
        for (String exempt : exemptRoles) {
            if (exempt.equalsIgnoreCase(userRole)) return true;
        }
        return false;
    }

    private record SelectBounds(int selectStart, int fromStart) {}
}
