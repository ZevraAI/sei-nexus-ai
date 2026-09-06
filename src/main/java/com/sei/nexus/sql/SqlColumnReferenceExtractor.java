package com.sei.nexus.sql;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, best-effort extraction of dot-qualified column references ({@code
 * table.column} / {@code alias.column}) for the column-existence defense-in-depth gate
 * (sibling to {@link SqlTableReferenceExtractor}'s table-existence gate).
 *
 * <p>Deliberately conservative and FAILS OPEN, exactly like {@link SqlTableReferenceExtractor}'s
 * own documented scope limitation: a dot-qualified reference whose left-hand side cannot be
 * confidently resolved — via this statement's own FROM/JOIN clauses — to a table the caller
 * supplied known columns for (an alias for a table outside that known set, a subquery/CTE alias,
 * or ambiguous parsing) is never flagged. This extractor only ever reports a reference it is
 * certain resolves to a known table and whose column is certain not to be in that table's known
 * column list — it never ranks, scores, or substitutes a column, only tests set membership.
 */
@Component
public class SqlColumnReferenceExtractor {

    private static final Pattern FROM_JOIN = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN)\\s+([A-Za-z_][\\w.\"]*)(?:\\s+(?:AS\\s+)?([A-Za-z_]\\w*))?");
    private static final Pattern DOT_REF = Pattern.compile("\\b([A-Za-z_]\\w*)\\.([A-Za-z_]\\w*)\\b");
    private static final Set<String> RESERVED = Set.of(
            "ON", "WHERE", "GROUP", "ORDER", "AND", "OR", "JOIN", "INNER", "LEFT", "RIGHT",
            "FULL", "OUTER", "SELECT", "AS", "LIMIT", "HAVING", "SET", "BY", "USING");

    /**
     * @param sql                 The SQL statement to check.
     * @param knownColumnsByTable Authoritative columns per table (lower-cased table name → set
     *                            of lower-cased column names) — supplied by the caller, which
     *                            owns deciding what "known" means for this request (e.g. the
     *                            resolved {@code ExecutionContract}'s object set for a
     *                            connection). An empty/{@code null} map disables this check
     *                            entirely (fails open), never reports.
     * @return Every {@code alias.column} / {@code table.column} reference this extractor could
     *         confidently resolve to a known table but whose column is NOT in that table's known
     *         column list — i.e. invalid column references. Never a ranked/suggested replacement.
     */
    public List<String> invalidColumnReferences(String sql, Map<String, Set<String>> knownColumnsByTable) {
        if (sql == null || sql.isBlank() || knownColumnsByTable == null || knownColumnsByTable.isEmpty()) {
            return List.of();
        }
        Map<String, String> aliasToTable = resolveAliases(sql);
        if (aliasToTable.isEmpty()) return List.of();

        List<String> invalid = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();
        Matcher m = DOT_REF.matcher(sql);
        while (m.find()) {
            String left = m.group(1);
            String col  = m.group(2);
            if (RESERVED.contains(left.toUpperCase(Locale.ROOT))) continue;

            String tableKey = aliasToTable.get(left.toLowerCase(Locale.ROOT));
            if (tableKey == null) continue; // unresolved alias/table — fail open

            Set<String> knownCols = knownColumnsByTable.get(tableKey);
            if (knownCols == null) continue; // table outside the caller's known set — fail open

            if (!knownCols.contains(col.toLowerCase(Locale.ROOT))) {
                String ref = left + "." + col;
                if (reported.add(ref)) invalid.add(ref);
            }
        }
        return invalid;
    }

    /**
     * Maps every alias and bare/schema-qualified table name introduced by this statement's own
     * FROM/JOIN clauses to a canonical (lower-cased, bare) table key — the same key shape the
     * caller is expected to use in {@code knownColumnsByTable}.
     */
    private Map<String, String> resolveAliases(String sql) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = FROM_JOIN.matcher(sql);
        while (m.find()) {
            String rawTable = m.group(1).replace("\"", "").trim();
            String alias    = m.group(2);
            String bareTable = rawTable.contains(".")
                    ? rawTable.substring(rawTable.lastIndexOf('.') + 1) : rawTable;
            String key = bareTable.toLowerCase(Locale.ROOT);

            map.put(key, key);
            map.putIfAbsent(rawTable.toLowerCase(Locale.ROOT), key);
            if (alias != null && !RESERVED.contains(alias.toUpperCase(Locale.ROOT))) {
                map.put(alias.toLowerCase(Locale.ROOT), key);
            }
        }
        return map;
    }
}
