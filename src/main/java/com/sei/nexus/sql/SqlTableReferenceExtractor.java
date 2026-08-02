package com.sei.nexus.sql;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical, deterministic SQL table-reference extraction and identifier canonicalization
 * for the platform (ADR-0003 A13).
 *
 * <p>This is the single shared utility used by the ExecutionContractBuilder (when
 * precomputing a contract's approved assets) and the agent runtime's enforcement gate,
 * so both sides canonicalize identifiers identically and the runtime performs pure set
 * membership with no normalization of its own.
 *
 * <p>Extraction is FROM/JOIN based, matching the platform's existing convention. It is
 * sufficient for the agent gate: multi-statement SQL, CTEs and {@code SELECT *} are
 * rejected downstream by the governance safety stage, so a construct this extractor does
 * not descend into cannot execute regardless. Governance keeps its own private extractor
 * for now; migrating it to this utility is a separate, governance-scoped story.
 */
@Component
public class SqlTableReferenceExtractor {

    private static final Pattern FROM_JOIN = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN)\\s+([A-Za-z_\"][\\w.\"]*)");

    /** The set of table identifiers referenced by {@code sql}, each in canonical form. */
    public Set<String> referencedTables(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        if (sql == null || sql.isBlank()) return tables;
        Matcher m = FROM_JOIN.matcher(sql);
        while (m.find()) {
            tables.add(canonical(m.group(1)));
        }
        return tables;
    }

    /**
     * Canonical form of a table identifier: quotes stripped, trimmed, upper-cased. Shared
     * by the contract builder and the runtime gate so membership checks are exact. Both a
     * bare {@code table} and a {@code schema.table} reference canonicalize consistently.
     */
    public String canonical(String identifier) {
        if (identifier == null) return "";
        return identifier.replace("\"", "").trim().toUpperCase();
    }

    /**
     * True when an unqualified table in {@code schema} resolves without qualification — i.e. the
     * schema is the connection's default search path ({@code public}, or unknown). A bare
     * reference to a non-default schema does not resolve, so the schema-qualified form is the only
     * executable identity there. Shared by the contract builder (which approves only executable
     * identifiers) and the renderer (which presents them), so both agree on one rule.
     */
    public static boolean isDefaultSchema(String schema) {
        return schema == null || schema.isBlank() || "public".equalsIgnoreCase(schema.trim());
    }
}
