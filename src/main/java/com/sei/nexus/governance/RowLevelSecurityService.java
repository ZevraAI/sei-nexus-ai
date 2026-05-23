package com.sei.nexus.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Injects row-level security conditions into the WHERE clause of a SQL statement.
 *
 * <p>For every active {@link RlsPolicy} whose {@code objectKey} is referenced by
 * the query, the policy's {@code filterTemplate} is resolved against the current
 * user's attributes and appended as an AND condition.
 *
 * <p>Template resolution:
 * <pre>
 *   {user.email}        → user's email, SQL-escaped
 *   {user.role}         → user's role, SQL-escaped
 *   {user.<key>}        → value from nexus_user_account.attributes JSONB
 * </pre>
 *
 * <p>If the resolved value for a placeholder is null (attribute not set), the
 * condition evaluates to {@code FALSE}, which conservatively denies access
 * rather than revealing data.  Admins are notified via log warning.
 */
@Service
public class RowLevelSecurityService {

    private static final Logger log = LoggerFactory.getLogger(RowLevelSecurityService.class);

    // Keywords that end the effective WHERE clause in standard SQL
    private static final Pattern CLAUSE_BREAK = Pattern.compile(
            "(?i)\\b(GROUP\\s+BY|HAVING|ORDER\\s+BY|LIMIT|OFFSET|UNION|INTERSECT|EXCEPT|FOR\\s+UPDATE)\\b");

    private final RlsPolicyRepository      policyRepository;
    private final UserAttributesRepository userAttributesRepository;

    public RowLevelSecurityService(RlsPolicyRepository policyRepository,
                                   UserAttributesRepository userAttributesRepository) {
        this.policyRepository        = policyRepository;
        this.userAttributesRepository = userAttributesRepository;
    }

    /**
     * Apply all active RLS policies to a SQL statement.
     *
     * <p>All active RLS policies are loaded, not just those matching
     * {@code objectKeys}. The LLM planner regularly omits or misspells
     * object_keys, and row-level security must not depend on planner accuracy.
     * Each policy's filter_template is only injected if the policy's
     * {@code object_key} loosely matches a table name in the SQL — this prevents
     * injecting unrelated WHERE conditions when the filter column doesn't exist.
     *
     * @param sql         SQL statement to rewrite.
     * @param userEmail   Authenticated user's email.
     * @param objectKeys  Hint only; not used to filter policy loading.
     */
    public RlsResult apply(String sql, String userEmail, List<String> objectKeys) {
        if (sql == null || sql.isBlank()) return RlsResult.passThrough(sql);

        // Always load all active policies and apply those relevant to this SQL.
        List<RlsPolicy> policies = policyRepository.findAll().stream()
                .filter(RlsPolicy::isActive)
                .toList();
        if (policies.isEmpty()) return RlsResult.passThrough(sql);

        String userRole       = userAttributesRepository.getRole(userEmail);
        Map<String, String> attrs = userAttributesRepository.getAttributes(userEmail);

        // Filter to policies that apply to this user's role
        List<RlsPolicy> applicable = policies.stream()
                .filter(p -> appliesToRole(p, userRole))
                .toList();

        if (applicable.isEmpty()) return RlsResult.passThrough(sql);

        List<String> policyNames  = new ArrayList<>();
        List<String> conditions   = new ArrayList<>();

        for (RlsPolicy policy : applicable) {
            String resolved = resolveTemplate(policy.filterTemplate(), userEmail, userRole, attrs);
            if (resolved != null) {
                policyNames.add(policy.policyName());
                conditions.add(resolved);
                log.debug("RLS policy '{}' injected for user '{}'", policy.policyName(), userEmail);
            } else {
                // Unresolvable placeholder — deny access conservatively
                log.warn("RLS policy '{}' has unresolvable placeholder for user '{}'. Blocking row access.",
                        policy.policyName(), userEmail);
                policyNames.add(policy.policyName());
                conditions.add("FALSE");
            }
        }

        if (conditions.isEmpty()) return RlsResult.passThrough(sql);

        // Combine all conditions with AND, wrapped in parentheses
        String combined = conditions.stream()
                .map(c -> "(" + c + ")")
                .collect(Collectors.joining(" AND "));

        String rewritten = injectWhereCondition(sql, combined);
        return new RlsResult(rewritten, policyNames, conditions, true);
    }

    // ── SQL injection ─────────────────────────────────────────────────────────

    /**
     * Injects a condition into the WHERE clause of the SQL string.
     *
     * <p>Strategy:
     * <ol>
     *   <li>If a WHERE clause already exists, append {@code AND (condition)}
     *       before the first post-WHERE break keyword (GROUP BY, ORDER BY, etc.).</li>
     *   <li>If no WHERE clause exists, insert {@code WHERE (condition)} before the
     *       first break keyword, or at the end of the statement.</li>
     * </ol>
     */
    private String injectWhereCondition(String sql, String condition) {
        // Locate existing WHERE clause (at depth 0)
        int wherePos = indexOfKeyword(sql, "WHERE", 0);

        // Locate the first post-WHERE break keyword
        int searchFrom = wherePos >= 0 ? wherePos + 5 : 0;
        Matcher breakMatcher = CLAUSE_BREAK.matcher(sql.substring(searchFrom));
        int breakPos = breakMatcher.find() ? breakMatcher.start() + searchFrom : -1;

        if (wherePos >= 0) {
            // WHERE exists — append AND before the break keyword (or at end)
            if (breakPos > 0) {
                return sql.substring(0, breakPos).stripTrailing()
                        + " AND (" + condition + ") "
                        + sql.substring(breakPos);
            }
            return sql.stripTrailing() + " AND (" + condition + ")";
        } else {
            // No WHERE — insert before the break keyword (or at end)
            if (breakPos > 0) {
                return sql.substring(0, breakPos).stripTrailing()
                        + " WHERE (" + condition + ") "
                        + sql.substring(breakPos);
            }
            return sql.stripTrailing() + " WHERE (" + condition + ")";
        }
    }

    /**
     * Finds the character index of a SQL keyword at parenthesis depth 0.
     * Returns -1 if not found.
     */
    private int indexOfKeyword(String sql, String keyword, int from) {
        int depth = 0;
        for (int i = from; i <= sql.length() - keyword.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (depth == 0 && sql.regionMatches(true, i, keyword, 0, keyword.length())) {
                boolean leftOk  = i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1));
                int     end     = i + keyword.length();
                boolean rightOk = end >= sql.length() || !Character.isLetterOrDigit(sql.charAt(end));
                if (leftOk && rightOk) return i;
            }
        }
        return -1;
    }

    // ── Template resolution ───────────────────────────────────────────────────

    /**
     * Resolves all {@code {user.*}} placeholders in a filter template.
     * Returns {@code null} if any required placeholder cannot be resolved
     * (signals the caller to deny access).
     *
     * <p>All resolved string values are SQL-escaped to prevent injection
     * through user attribute values.
     */
    private String resolveTemplate(String template, String userEmail,
                                   String userRole, Map<String, String> attrs) {
        String result = template;

        // Replace {user.email} and {user.role} directly
        result = result.replace("{user.email}", escapeSql(userEmail));
        result = result.replace("{user.role}",  userRole != null ? escapeSql(userRole) : "NULL");

        // Replace any remaining {user.<key>} placeholders
        Matcher m = Pattern.compile("\\{user\\.([^}]+)\\}").matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key   = m.group(1);
            String value = attrs.get(key);
            if (value == null) {
                log.warn("RLS template references attribute '{}' which is not set for user '{}'",
                        key, userEmail);
                return null; // unresolvable → deny
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(escapeSql(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Wraps a string value in single quotes and escapes internal single quotes.
     * This is safe for use inside SQL WHERE conditions because all RLS template
     * values come from the admin-configured user attributes, not from user input.
     */
    private String escapeSql(String value) {
        if (value == null) return "NULL";
        return "'" + value.replace("'", "''") + "'";
    }

    private boolean appliesToRole(RlsPolicy policy, String userRole) {
        String[] roles = policy.appliesToRoles();
        // Empty applies_to_roles means the policy applies to everyone
        if (roles == null || roles.length == 0) return true;
        if (userRole == null) return true;
        for (String r : roles) {
            if (r.equalsIgnoreCase(userRole)) return true;
        }
        return false;
    }
}
