package com.sei.nexus.agentbrain;

import com.sei.nexus.prompt.SqlIdentifierGuidance;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link PromptContext} into grounding text for the model (ADR-0003 semantic model,
 * Phase 1B). Deterministic; performs no business reasoning. It grounds the model in business
 * objects and their business attributes, each shown with the exact physical table/column the
 * model must use — so the model never invents a table or a column.
 *
 * <p>The rendering is hardened for identifier fidelity: it declares the listed schema complete
 * and authoritative, presents every physical identifier as a backticked literal, suppresses the
 * {@code name → name} tautology when a column has no distinct business name, and closes with an
 * absolute rule to copy identifiers character-for-character.
 *
 * <p><b>Rendering is policy-driven (Unified Answer Engine, Phase 3).</b> The knowledge lives on
 * the {@link PromptContext}; {@link RenderOptions} let each execution policy choose how much of
 * it to surface — whether to schema-qualify and show the connection key, whether to show physical
 * data types, and a character budget. The budget bounds only what is <em>rendered</em>; it never
 * changes the approved execution surface (that lives on the immutable {@link ExecutionContract}).
 * The no-argument {@link #assemble(PromptContext)} is the historical compact rendering used by the
 * autonomous-agent policy.
 */
@Component
public class PromptAssembler {

    /**
     * How a policy wants the grounding rendered. {@code maxChars ≤ 0} means unbounded.
     *
     * @param qualifyLocation schema-qualify the table and surface the connection key
     * @param includeDataType show each column's physical data type
     * @param maxChars        rendering budget over the object listing; 0 = unbounded
     */
    // RenderOptions carries ONLY presentation policy — how much descriptive detail to show and how
    // to format it. It deliberately does NOT carry any semantic-grounding decision: which business
    // objects, attributes, and value domains the model reasons over is shared grounding produced by
    // the pipeline, identical for every execution strategy (Unified Answer Engine conformance).
    // (Value-domain exposure was previously a field here — a semantic decision an execution strategy
    // could toggle; it is now always rendered, see renderDetail/renderValueDomainsOnly.)
    public record RenderOptions(boolean qualifyLocation, boolean includeDataType,
                                boolean includePurpose, int maxChars) {
        /** The autonomous-agent presentation policy: compact, unqualified, unbounded. */
        public static RenderOptions compact() {
            return new RenderOptions(false, false, false, 0);
        }
        boolean bounded() { return maxChars > 0; }
    }

    /** Values rendered per domain-bearing column — a rendering cap; the model carries the full list. */
    private static final int MAX_RENDERED_VALUES = 20;

    /** Compact rendering (autonomous-agent policy). */
    public String assemble(PromptContext ctx) {
        return assemble(ctx, RenderOptions.compact());
    }

    /** Renders the grounding according to the given policy. */
    public String assemble(PromptContext ctx, RenderOptions opts) {
        if (ctx.isEmpty()) {
            return "Business objects available to you: NONE.\n"
                    + "No business data is mapped to this agent's connections, so you cannot query any table. "
                    + "If the user asks for data, explain via final_answer that no business objects are available.";
        }

        StringBuilder sb = new StringBuilder();

        // (1) Schema authority (shared) — the listed schema is the whole world; suppress prior priors.
        sb.append(SqlIdentifierGuidance.SCHEMA_AUTHORITY).append("\n\n");

        // (3)(4) Schema listing — physical identifiers as backticked literals; a business name is
        //        shown only when it adds information (tautology guard: never render `x` → `x`).
        //
        //        RENDERING INVARIANT: the budget may reduce descriptive detail but must never
        //        remove an approved object's executable identity. So every object's Tier-1 identity
        //        (schema-qualified table + connection [+ purpose]) is always rendered; the budget
        //        governs only Tier-2 detail (columns, types, value domains, guidance), spent in the
        //        relevance order AgentBrain produced. The budget never touches approvedAssets.
        int detailChars = 0;
        boolean detailBudgetExhausted = false;
        for (PromptContext.PromptObject o : ctx.objects()) {
            sb.append(renderIdentity(o, opts));                 // Tier 1 — always
            String detail = renderDetail(o, opts);              // Tier 2 — budget-governed
            if (!opts.bounded() || (!detailBudgetExhausted && detailChars + detail.length() <= opts.maxChars())) {
                sb.append(detail);
                detailChars += detail.length();
            } else {
                detailBudgetExhausted = true;
                sb.append("  (columns omitted to fit the context budget)\n");
                // The presentation budget may drop DESCRIPTIVE detail, but never SEMANTIC value
                // knowledge — value domains are shared grounding and always survive the budget.
                sb.append(renderValueDomainsOnly(o));
            }
            sb.append('\n');
        }

        // (2) Identifier-fidelity rules (shared) — placed last, the final instruction before the task.
        sb.append(SqlIdentifierGuidance.IDENTIFIER_RULES).append('\n');

        return sb.toString();
    }

    /**
     * Tier 1 — the object's executable identity, always rendered regardless of budget:
     * schema-qualified table (when the policy qualifies), connection key, and purpose.
     * Compact options reproduce the historical bytes exactly (bare table, no connection/purpose).
     */
    private static String renderIdentity(PromptContext.PromptObject o, RenderOptions opts) {
        StringBuilder sb = new StringBuilder();
        // Present the executable identity. Qualify when the policy asks, OR when the schema is
        // non-default — there a bare table does not resolve, so the schema-qualified form is the
        // only executable reference (evidence-driven; default-schema rendering is unchanged).
        boolean schemaKnown = o.schema() != null && !o.schema().isBlank();
        boolean qualify = schemaKnown
                && (opts.qualifyLocation()
                    || !com.sei.nexus.sql.SqlTableReferenceExtractor.isDefaultSchema(o.schema()));
        String tableRef = qualify ? o.schema().trim() + "." + o.physicalTable() : o.physicalTable();
        sb.append("TABLE `").append(tableRef).append('`');
        if (differs(o.businessName(), o.physicalTable())) {
            sb.append("  — ").append(o.businessName().trim());
        }
        sb.append('\n');
        if (opts.qualifyLocation() && o.connectionKey() != null && !o.connectionKey().isBlank()) {
            sb.append("  connection_key: ").append(o.connectionKey())
              .append(" (use this exact value)\n");
        }
        if (opts.includePurpose() && o.purpose() != null && !o.purpose().isBlank()) {
            sb.append("  purpose: ").append(o.purpose().trim()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Tier 2 — descriptive detail governed by the rendering budget: guidance and the column list
     * (with types and value domains per policy). Never carries the object's identity.
     */
    private static String renderDetail(PromptContext.PromptObject o, RenderOptions opts) {
        StringBuilder sb = new StringBuilder();
        if (o.guidance() != null && !o.guidance().isBlank()) {
            sb.append("  guidance: ").append(o.guidance().trim()).append('\n');
        }
        sb.append("  columns (write each `column` exactly as shown):\n");
        for (PromptContext.PromptAttribute a : o.attributes()) {
            sb.append("    • `").append(a.physicalColumn()).append("` (").append(a.role());
            if (opts.includeDataType() && a.dataType() != null && !a.dataType().isBlank()) {
                sb.append(", ").append(a.dataType());
            }
            sb.append(')');
            if (differs(a.businessName(), a.physicalColumn())) {
                sb.append("  — ").append(a.businessName().trim());
            }
            // Value domains are SHARED semantic grounding, not a presentation option — always
            // rendered, identical for every execution strategy. Never gated by RenderOptions.
            sb.append(renderValueDomain(a.valueDomain()));
            sb.append('\n');
        }
        return sb.toString();   // the per-object blank-line separator is appended by the caller
    }

    /**
     * The value domains for an object's domain-bearing columns, with no descriptive detail — the
     * semantic value grounding that always survives the presentation budget (used when the budget
     * omits an object's descriptive column detail). Empty when the object has no value domains.
     */
    private static String renderValueDomainsOnly(PromptContext.PromptObject o) {
        StringBuilder sb = new StringBuilder();
        for (PromptContext.PromptAttribute a : o.attributes()) {
            String vd = renderValueDomain(a.valueDomain());
            if (!vd.isEmpty()) {
                sb.append("    `").append(a.physicalColumn()).append('`').append(vd).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Renders a column's legal/observed values, capped; empty when the column has no domain. When a
     * physical value carries a Business Value label (an approved mapping), it renders as
     * {@code value = Concept} (e.g. {@code 10 = Draft}); unmapped values render bare. Labels are
     * presentation only — they never change which physical values are legal.
     */
    private static String renderValueDomain(com.sei.nexus.semanticmodel.ColumnValueDomain vd) {
        if (vd == null || vd.values().isEmpty()) return "";
        java.util.List<String> values = vd.values();
        boolean truncated = values.size() > MAX_RENDERED_VALUES;
        java.util.List<String> shown = truncated ? values.subList(0, MAX_RENDERED_VALUES) : values;
        String joined = shown.stream()
                .map(v -> { String label = vd.labelFor(v); return label != null ? v + " = " + label : v; })
                .collect(java.util.stream.Collectors.joining(" | "));
        return "  [" + (vd.authoritative() ? "legal" : "observed") + " values: "
                + joined + (truncated ? " | …" : "") + "]";
    }

    /** True when the business name carries information beyond the physical identifier itself. */
    private static boolean differs(String businessName, String physical) {
        return businessName != null && !businessName.isBlank()
                && !businessName.trim().equalsIgnoreCase(physical == null ? "" : physical.trim());
    }
}
