package com.sei.nexus.sql;

import org.springframework.stereotype.Service;

/**
 * Validates and normalises raw SQL before it reaches the query-governance layer.
 *
 * <p>Rules enforced:
 * <ol>
 *   <li>Non-blank</li>
 *   <li>Must start with SELECT</li>
 *   <li>No semicolons (prevents statement chaining)</li>
 *   <li>No DML/DDL keywords as standalone words</li>
 *   <li>No bare {@code SELECT *}</li>
 * </ol>
 * </p>
 */
@Service
public class SqlSafetyService {

    private static final String[] DANGEROUS_KEYWORDS = {
            "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER",
            "TRUNCATE", "EXEC", "EXECUTE", "MERGE", "GRANT", "REVOKE"
    };

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Immutable result of a safety check.
     *
     * @param safe   {@code true} if the SQL passed all checks
     * @param reason human-readable failure reason; {@code null} when safe
     */
    public record SafetyResult(boolean safe, String reason) {}

    /**
     * Validates {@code sql} against the SEI Nexus safety rules.
     */
    public SafetyResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return new SafetyResult(false, "SQL is empty");
        }

        String normalized = sql.strip().toUpperCase().replaceAll("\\s+", " ");

        // Rule 1: must be a SELECT statement
        if (!normalized.startsWith("SELECT")) {
            return new SafetyResult(false, "Only SELECT statements are allowed");
        }

        // Rule 2: no semicolons — prevent multiple-statement injection
        if (sql.contains(";")) {
            return new SafetyResult(false, "Multiple statements (semicolons) are not allowed");
        }

        // Rule 3: no DML/DDL keywords as whole words
        for (String kw : DANGEROUS_KEYWORDS) {
            if (normalized.matches(".*\\b" + kw + "\\b.*")) {
                return new SafetyResult(false, "Statement contains disallowed keyword: " + kw);
            }
        }

        // Rule 4: no SELECT *
        if (normalized.matches(".*SELECT\\s+\\*.*")) {
            return new SafetyResult(false,
                    "SELECT * is not allowed; specify column names explicitly");
        }

        return new SafetyResult(true, null);
    }

    /**
     * Normalises dialect-specific pagination syntax.
     *
     * <ul>
     *   <li>For ORACLE: converts {@code LIMIT n} to {@code FETCH FIRST n ROWS ONLY}
     *       when a LIMIT clause is present (added by the governance layer).</li>
     *   <li>For POSTGRES: converts Oracle {@code FETCH FIRST n ROWS ONLY} back to
     *       {@code LIMIT n} if encountered in incoming SQL.</li>
     *   <li>For other types the SQL is returned unchanged.</li>
     * </ul>
     *
     * @param sql            raw SQL (may already contain a pagination clause)
     * @param connectionType POSTGRES | ORACLE | REST_API
     */
    public String normalizeDialect(String sql, String connectionType) {
        if (sql == null || sql.isBlank() || connectionType == null) {
            return sql;
        }

        switch (connectionType.toUpperCase()) {
            case "ORACLE" -> {
                // Replace trailing LIMIT n with FETCH FIRST n ROWS ONLY
                String result = sql.strip().replaceAll(
                        "(?i)\\bLIMIT\\s+(\\d+)\\s*$",
                        "FETCH FIRST $1 ROWS ONLY");
                // If ROWNUM is already being used as a filter, leave alone
                return result;
            }
            case "POSTGRES" -> {
                // Replace FETCH FIRST n ROWS ONLY with LIMIT n
                return sql.strip().replaceAll(
                        "(?i)\\bFETCH\\s+FIRST\\s+(\\d+)\\s+ROWS\\s+ONLY\\b",
                        "LIMIT $1");
            }
            default -> {
                return sql;
            }
        }
    }
}
