package com.sei.nexus.governance;

import java.util.List;

/**
 * Result produced by {@link RowLevelSecurityService#apply}.
 *
 * @param sql                SQL string after all applicable RLS filters have been injected.
 * @param policiesApplied    Names of the RLS policies that fired.
 * @param injectedConditions The raw SQL conditions that were injected into the WHERE clause.
 * @param wasModified        True when at least one policy was applied.
 */
public record RlsResult(
        String       sql,
        List<String> policiesApplied,
        List<String> injectedConditions,
        boolean      wasModified
) {
    public static RlsResult passThrough(String originalSql) {
        return new RlsResult(originalSql, List.of(), List.of(), false);
    }
}
