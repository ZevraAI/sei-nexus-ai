package com.sei.nexus.reasoning;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic literal validation (PRO-33; contract PRO-32 §5).
 *
 * <p>Rule: every equality/IN literal aimed at a domain-bearing column must
 * exist in that column's persisted Value Domain. The validator checks two
 * inputs per planned step — the planner's declared {@code literal_bindings}
 * and the literals it can bind deterministically in the SQL text
 * ({@code col = 'lit'} / {@code col IN ('a','b')}) — against the scope the
 * resolver derived (all domain-bearing columns of the entity-bound tables).
 *
 * <p>It <b>rejects, never rewrites</b>: violations are reported to the caller,
 * which feeds them back through the existing planner/evaluator loop. Literals
 * that appear verbatim in the user's question or attachment are exempt —
 * user-supplied ground truth is not the resolver's to veto (PRO-32 §5).
 * Enforcement strength (hard vs advisory) follows domain authority and is the
 * caller's decision via {@link Violation#authoritative()}.
 *
 * <p>Fail-open by construction: unparseable SQL contributes no extracted
 * literals, an empty scope validates everything, and any unexpected error in
 * {@link #validate} must be caught by the caller — validation may never make
 * answers less available than today.
 */
public final class LiteralValidator {

    /** A domain-bearing column in scope: its persisted legal/observed values. */
    public record DomainInfo(String table, String column, boolean authoritative,
                             List<String> values) {
        public String qualifiedColumn() {
            return table + "." + column;
        }
    }

    /** One invalid literal: what was used, where, and what would be legal. */
    public record Violation(String column, String literal, boolean authoritative,
                            List<String> legalValues) {

        /** The exact PRO-32 §5 re-prompt message. */
        public String message() {
            return "'" + literal + "' is not a legal value of " + column
                    + "; legal values: [" + String.join(", ", legalValues) + "]";
        }
    }

    public record Result(List<Violation> violations) {
        public boolean valid() {
            return violations.isEmpty();
        }
    }

    // col = 'literal'   (column may be alias- or schema-qualified; '' = escaped quote)
    private static final Pattern EQUALITY = Pattern.compile(
            "(?i)([a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*){0,2})\\s*=\\s*'((?:[^']|'')*)'");
    // col IN ('a', 'b', …)
    private static final Pattern IN_LIST = Pattern.compile(
            "(?i)([a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*){0,2})\\s+in\\s*\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'((?:[^']|'')*)'");

    private LiteralValidator() {}

    /**
     * Validates one planned step. {@code questionText} is the user-facing
     * question (enriched form, so attachment values count) used for the
     * verbatim exemption.
     */
    public static Result validate(String sql,
                                  List<ReasoningPlanner.LiteralBinding> bindings,
                                  Map<String, DomainInfo> scope,
                                  String questionText) {
        if (scope == null || scope.isEmpty()) return new Result(List.of());
        String exemptionText = questionText != null
                ? questionText.toLowerCase(Locale.ROOT) : "";

        Set<String> dedupe = new LinkedHashSet<>();
        List<Violation> violations = new ArrayList<>();

        // 1. Declared bindings — the planner's own claims are checked first.
        if (bindings != null) {
            for (ReasoningPlanner.LiteralBinding b : bindings) {
                check(b.column(), b.value(), scope, exemptionText, dedupe, violations);
            }
        }

        // 2. Deterministically extractable SQL literals (defense in depth).
        if (sql != null && !sql.isBlank()) {
            Matcher eq = EQUALITY.matcher(sql);
            while (eq.find()) {
                check(eq.group(1), unescape(eq.group(2)), scope, exemptionText, dedupe, violations);
            }
            Matcher in = IN_LIST.matcher(sql);
            while (in.find()) {
                Matcher lit = QUOTED.matcher(in.group(2));
                while (lit.find()) {
                    check(in.group(1), unescape(lit.group(1)), scope, exemptionText, dedupe, violations);
                }
            }
        }
        return new Result(List.copyOf(violations));
    }

    /**
     * Resolves a (possibly alias/schema-qualified) column reference against
     * the scope: full form, then table.column, then bare column — bare only
     * when the scope registered it unambiguously.
     */
    public static DomainInfo lookup(Map<String, DomainInfo> scope, String columnRef) {
        if (columnRef == null || columnRef.isBlank()) return null;
        String ref = columnRef.toLowerCase(Locale.ROOT);
        DomainInfo d = scope.get(ref);
        if (d != null) return d;
        String[] parts = ref.split("\\.");
        if (parts.length >= 2) {
            d = scope.get(parts[parts.length - 2] + "." + parts[parts.length - 1]);
            if (d != null) return d;
        }
        return scope.get(parts[parts.length - 1]);
    }

    private static void check(String columnRef, String literal,
                              Map<String, DomainInfo> scope, String exemptionText,
                              Set<String> dedupe, List<Violation> violations) {
        if (literal == null || literal.isBlank()) return;
        DomainInfo domain = lookup(scope, columnRef);
        if (domain == null) return;                                  // no domain ⇒ no gate
        if (domain.values().contains(literal)) return;               // exists ⇒ valid
        // Verbatim exemption: user-supplied ground truth is never vetoed.
        if (!exemptionText.isEmpty()
                && exemptionText.contains(literal.toLowerCase(Locale.ROOT))) return;
        String key = domain.qualifiedColumn() + "='" + literal + "'";
        if (dedupe.add(key)) {
            violations.add(new Violation(domain.qualifiedColumn(), literal,
                    domain.authoritative(), domain.values()));
        }
    }

    private static String unescape(String sqlLiteral) {
        return sqlLiteral.replace("''", "'");
    }

    // ── Retry policy (PRO-32 §5): one re-prompt, then tier-dependent ─────────

    /** What the engine should do with a step whose validation failed. */
    public record RejectionDecision(boolean reject, boolean hardBlock, String message) {}

    /**
     * First violation of a (column, literal) pair ⇒ reject the step and
     * re-prompt with the legal list (both tiers). A repeat violation ⇒
     * authoritative: hard block (visible failure); observed: advisory
     * exhausted — the step executes honestly. {@code priorRejections} is the
     * engine's memory across the loop and is updated in place.
     */
    public static RejectionDecision decide(List<Violation> violations,
                                           Set<String> priorRejections) {
        boolean reject = false;
        boolean hardBlock = false;
        List<String> messages = new ArrayList<>();
        for (Violation v : violations) {
            String key = v.column() + "='" + v.literal() + "'";
            if (priorRejections.add(key)) {
                reject = true;                       // first offence: re-prompt
                messages.add(v.message());
            } else if (v.authoritative()) {
                hardBlock = true;                    // repeat on complete domain
                messages.add(v.message());
            }
            // repeat on observed domain: advisory exhausted — allow through
        }
        return new RejectionDecision(reject || hardBlock, hardBlock,
                String.join("; ", messages));
    }
}
