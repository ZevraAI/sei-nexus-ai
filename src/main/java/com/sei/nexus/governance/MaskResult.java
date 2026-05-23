package com.sei.nexus.governance;

import java.util.List;

/**
 * Result produced by {@link ColumnMaskingService#apply}.
 *
 * @param sql           The SQL string after column masking has been applied.
 *                      Equal to the input SQL when no policies matched.
 * @param maskedColumns Names of columns that were masked or excluded.
 *                      Empty when no policies applied.
 * @param wasModified   True when the SQL was changed from the original.
 */
public record MaskResult(
        String       sql,
        List<String> maskedColumns,
        boolean      wasModified
) {
    /** Convenience factory when no masking was applied. */
    public static MaskResult passThrough(String originalSql) {
        return new MaskResult(originalSql, List.of(), false);
    }
}
