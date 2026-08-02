package com.sei.nexus.semantic;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Business Language Resolution output (PRO-31, contract in PRO-30 §6).
 *
 * <p>Produced once per question by {@link BusinessLanguageResolver} and consumed by:
 * <ul>
 *   <li>the keyword-dependent context stages (graph filter, PRO-19 block ranking)
 *       via {@link #expandedTokens} — retrieval expansion;</li>
 *   <li>the prompt renderer via {@link #renderPromptBlock()} — the RESOLUTIONS
 *       section of the planner/decision context;</li>
 *   <li>the reasoning-trace payload — per-answer resolution provenance.</li>
 * </ul>
 *
 * <p>Annotate-never-substitute: {@link #original} is the user's question verbatim
 * and is the only question text that ever reaches the planner. Resolutions are
 * annotations beside it, never rewrites of it.
 *
 * <p>Zero-cost fallback: an empty instance (no resolutions, no tokens) makes
 * every consumer behave byte-identically to the pre-BLR pipeline.
 */
public record ResolvedQuestion(
        String original,
        List<Resolution> resolutions,
        Set<String> expandedTokens,
        // ── Deterministic Literal Resolution (PRO-33, contract PRO-32) ──────
        // Question tokens that matched no referent and are literal-shaped —
        // the constrained-choice targets of the LITERAL CANDIDATES block.
        List<String> unresolvedTerms,
        // All domain-bearing columns of the entity-bound tables in scope,
        // ranked per PRO-32 C2 (authoritative > status role > declared/
        // confirmed filterable). The prompt block offers the top few; the
        // literal validator consumes the whole list as its scope.
        List<LiteralCandidate> literalCandidates) {

    /** PRO-31 shape — no literal-resolution data (equivalent to none found). */
    public ResolvedQuestion(String original, List<Resolution> resolutions,
                            Set<String> expandedTokens) {
        this(original, resolutions, expandedTokens, List.of(), List.of());
    }

    /** Candidate columns rendered per unresolved term in the prompt block. */
    static final int MAX_CANDIDATE_COLUMNS = 3;
    /** Values rendered per candidate list (domains are already ≤ 25 by the
     *  PRO-24 cardinality gate; this is a belt-and-braces render cap). */
    static final int MAX_RENDERED_VALUES = 25;

    /**
     * A domain-bearing column offered as a literal-resolution target: the
     * persisted legal/observed values of {@code table.column}. Values come
     * exclusively from {@code nexus_value_domain} (PRO-24/27 gates) — never
     * sampled at question time.
     */
    public record LiteralCandidate(String table, String column,
                                   boolean authoritative, List<String> values) {
        public String qualifiedColumn() {
            return table + "." + column;
        }

        /** "legal" for authoritative (enum) domains, "observed" for sampled ones. */
        public String sourceLabel() {
            return authoritative ? "legal" : "observed";
        }
    }

    /** Reference kinds per PRO-30 §6 (metric reserved, not yet emitted). */
    public enum Kind {
        VALUE, COLUMN, ENTITY;

        /** Lowercase label used in the RESOLUTIONS block grammar. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One resolved term. {@code surface} is the term exactly as the user wrote it;
     * {@code target} is an existing referent (verbatim sql_equivalent, a predicate
     * composed from a persisted domain value, an entity + bound table, or a
     * qualified column) — the resolver never invents targets. {@code tier} is
     * {@code company} or {@code pack:<id>}; the universal tier is the model's
     * knowledge and never appears here (PRO-30 §6).
     */
    public record Resolution(String surface, Kind kind, String target, String tier) {

        /** Human-readable provenance label for the reasoning trace. */
        public String sourceLabel() {
            if (tier != null && tier.startsWith("pack:")) {
                return "Industry pack (" + tier.substring("pack:".length()) + ")";
            }
            return "Company vocabulary";
        }
    }

    public static ResolvedQuestion empty(String original) {
        return new ResolvedQuestion(original != null ? original : "", List.of(), Set.of(),
                List.of(), List.of());
    }

    public boolean isEmpty() {
        return resolutions.isEmpty();
    }

    /**
     * Renders the RESOLUTIONS prompt section with the exact PRO-30 §6 grammar:
     * <pre>"&lt;surface&gt;" = &lt;kind&gt;: &lt;target&gt;   [&lt;tier&gt;]</pre>
     * Returns the empty string when there are no resolutions, so prompts without
     * resolutions stay byte-identical to today's.
     */
    public String renderPromptBlock() {
        if (resolutions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("=== RESOLUTIONS ===\n");
        for (Resolution r : resolutions) {
            sb.append('"').append(r.surface()).append("\" = ").append(r.kind().label())
              .append(": ").append(r.target())
              .append("   [").append(r.tier()).append("]\n");
        }
        return sb.toString();
    }

    /**
     * Renders the LITERAL CANDIDATES section with the exact PRO-32 §3 grammar —
     * one constrained-choice paragraph per unresolved term over the top-ranked
     * candidate columns. Empty string when there is no unresolved term or no
     * candidate to offer (zero-cost: prompts stay byte-identical).
     */
    public String renderLiteralCandidatesBlock() {
        if (unresolvedTerms.isEmpty() || literalCandidates.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("=== LITERAL CANDIDATES ===\n");
        for (String term : unresolvedTerms) {
            sb.append('"').append(term)
              .append("\" matched no known term, value, column, or entity. If it denotes a data value,\n")
              .append("it MUST be one of these stored values (choose the exact spelling):\n");
            literalCandidates.stream().limit(MAX_CANDIDATE_COLUMNS).forEach(c -> {
                sb.append("  ").append(c.qualifiedColumn())
                  .append(" (").append(c.sourceLabel()).append("): ");
                List<String> shown = c.values().size() > MAX_RENDERED_VALUES
                        ? c.values().subList(0, MAX_RENDERED_VALUES) : c.values();
                sb.append(String.join(" | ", shown));
                if (c.values().size() > MAX_RENDERED_VALUES) sb.append(" | …");
                sb.append('\n');
            });
        }
        sb.append("If none of these is what the user means, ask for clarification. Never invent a literal.\n");
        return sb.toString();
    }
}
