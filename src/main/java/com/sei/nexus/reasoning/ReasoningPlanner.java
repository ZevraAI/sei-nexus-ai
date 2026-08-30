package com.sei.nexus.reasoning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.prompt.SqlIdentifierGuidance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Generates the single next SQL step for an investigation, given:
 * <ul>
 *   <li>the user's original question,</li>
 *   <li>the approved schema context (tables, columns, relationships),</li>
 *   <li>a compact summary of every step executed so far.</li>
 * </ul>
 *
 * <p>The key difference from the original single-shot planner: the LLM sees
 * <em>actual result summaries</em> from prior steps before deciding what to
 * query next. This enables genuine multi-hop causal reasoning — each step's
 * findings inform the next step's SQL.
 *
 * <p>Returns {@code null} when the planner determines no further queries are
 * needed (the evidence accumulated is already sufficient).
 */
@Component
public class ReasoningPlanner {

    private static final Logger log = LoggerFactory.getLogger(ReasoningPlanner.class);

    private static final String SYSTEM_PROMPT =
            SqlIdentifierGuidance.SCHEMA_AUTHORITY + "\n\n" + """
            You are a SQL investigation planner building a case step by step.
            The user's question and all evidence gathered so far are provided.
            Your job: generate the SINGLE next SQL query that will most advance the investigation.

            Rules:
            - Use only the tables and columns listed under "Approved schema".
            - Use the exact connection_key shown for each table.
            - Do NOT repeat a query that has already been executed (check "Evidence so far").
            - If the evidence already answers the question, return: {"done": true}
            - Write focused SQL — a targeted SELECT, not SELECT *. Select the columns whose
              business meaning is relevant to the user's question, using each column's role
              and business name from the approved schema — not every available column, and
              never the * wildcard. "Focused" means well-chosen, not minimal: when the user
              asks only for an identifier or code by name (e.g. "give me the order numbers"),
              that column alone is enough. When the user asks to see, show, or list the
              records themselves (e.g. "show me the orders"), a single identifier column is
              NOT enough — include enough of the object's other columns (its dimensions,
              measures, and attributes, not just its identifiers) for each row to be a
              useful, self-explanatory record on its own. If the user names a specific
              attribute (e.g. "...and expected delivery date"), include that column in
              addition to the identifying and descriptive columns you already chose.
              If the user explicitly asks for all fields or the full
              record, list every approved column of the relevant table by name; never use
              SELECT *.
            - Joins, aggregations, GROUP BY, ORDER BY, LIMIT are all allowed.
            - Extract filter values from the attached file content when present.
            - RESOLUTIONS map the user's terms to this tenant's canonical names and values.
              Prefer them over your own interpretation of those terms.
            - Every column's value domain in "Approved schema" is labeled either
              [legal values: ...] or [observed values: ...]. These mean different things:
                • [legal values: ...] is AUTHORITATIVE — the CLOSED, COMPLETE set of every
                  value that column can ever physically hold (e.g. a database enum). Nothing
                  outside this list is a valid literal for that column, ever.
                • [observed values: ...] is a SAMPLE only — real values seen in the data,
                  never a complete list. Do not treat it as exhaustive, and do not refuse a
                  value merely because it is absent from an observed sample — this is a
                  free-text-style column.
            - Before filtering an authoritative (legal-values) column on a literal, reason
              through these in order:
                1. EXACT MATCH — the user's term names one of the legal values (allowing for
                   case/whitespace differences only) → use that legal value exactly as listed.
                2. BUSINESS-CONCEPT MATCH — the term is a business phrase rather than a literal
                   value name (e.g. "open", "active", "in progress", "overdue"). Check whether
                   the business entity/vocabulary definitions given in this context (if any)
                   define that term as corresponding to one or more of the column's legal
                   values. If they do, filter using those exact legal values (an IN (...) list
                   when more than one applies), and state the mapping you used in "rationale".
                3. NO DEFENSIBLE MATCH — the term matches no legal value and no business
                   definition available to you supports a mapping to one or more legal values.
                   You MUST NOT invent, guess, or substitute a legal-sounding value in this
                   case, and you MUST NOT filter on the user's own literal term either — an
                   authoritative column only ever accepts its own legal values. Instead of
                   "sql", respond with "clarification_question" (see the response shape below)
                   naming the term you could not resolve and listing the actual legal values so
                   the user can choose one — do not generate any SQL for this step.
              This three-step reasoning applies ONLY to columns with a listed "legal values"
              domain. A column with only "observed values", or no listed domain at all, is
              free text — use the tolerant-matching guidance below instead; do not require an
              exact or defensible match for it.
            - When a filter literal resolves a user term to a stored value (e.g. the user
              said "TX" and you filter on 'Texas' from a legal-values list), declare it in
              "literal_bindings". Omit the field when there is nothing to declare.
            - Matching strategy for a text filter depends on the nature of the column being
              filtered, never on how the question is phrased — a browse-sounding question and
              a lookup-sounding question must be handled identically for the same column.
            - For a free-text column (a human-authored name, title, or description) with no
              legal-values list and no RESOLUTIONS entry for the term, do not assume the
              user's phrase is the exact stored value. Prefer a comparison that tolerates
              differences in punctuation, spacing, capitalization, or word form (e.g. a
              possessive apostrophe, a hyphen, a plural) rather than requiring the phrase to
              match the stored value exactly.
            - For an identifier or code the user is clearly quoting verbatim — an invoice
              number, PO number, SKU, promotion code, store or warehouse code, including
              numeric values — match it exactly. Do not apply tolerant matching to numbers
              or codes.

            Return JSON only (no markdown, no explanation):
            {"done":false,"description":"one-line goal","sql":"SELECT ...","connection_key":"...","object_keys":"key1,key2","rationale":"why this step advances the investigation","literal_bindings":[{"surface":"TX","column":"stores.state_province","value":"Texas"}]}

            OR, when step 3 above applies — a term you cannot defensibly resolve against an
            authoritative legal-values column:
            {"done":false,"clarification_question":"one clear question naming the term you could not resolve and listing the actual legal values so the user can choose","rationale":"why no legal value or business definition matched"}

            OR if no further queries are needed:
            {"done":true}
            """
            + "\n" + SqlIdentifierGuidance.IDENTIFIER_RULES;

    private final AzureOpenAiClient aiClient;
    private final ObjectMapper      objectMapper;

    public ReasoningPlanner(AzureOpenAiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient     = aiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * @param question    The raw user question.
     * @param schemaCtx   Approved schema context string (tables, columns, relationships).
     * @param evidence    Accumulated evidence from prior steps.
     * @return The next step plan, or {@code null} if the planner says it's done.
     */
    public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
        try {
            String prompt = buildPrompt(question, schemaCtx, evidence);
            com.sei.nexus.ai.LlmCallTag.set("PLANNER");
            String raw    = aiClient.chat(List.of(ChatMessage.user(prompt)), SYSTEM_PROMPT);
            String json   = extractJson(raw);
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            if (Boolean.TRUE.equals(parsed.get("done"))) return null;

            // Semantic Reasoning Over Authoritative Value Domains: the planner's own, explicit
            // way to decline generating SQL when a user's term cannot be defensibly resolved
            // against an authoritative (legal-values) enum column — see the SYSTEM_PROMPT's
            // "NO DEFENSIBLE MATCH" rule. Checked before "sql" so a response carrying both is
            // still treated as a clarification (never silently falls through to executing SQL
            // built on a term the planner itself flagged as unresolved).
            String clarification = strOr(parsed, "clarification_question", "");
            if (!clarification.isBlank()) {
                return new StepPlan(
                        strOr(parsed, "description", "Clarification needed"),
                        null, null, "",
                        strOr(parsed, "rationale", ""),
                        List.of(),
                        clarification.strip());
            }

            String sql     = (String) parsed.get("sql");
            String connKey = (String) parsed.get("connection_key");
            if (sql == null || sql.isBlank() || connKey == null || connKey.isBlank()) return null;

            return new StepPlan(
                    strOr(parsed, "description", "Investigation step " + (evidence.stepCount() + 1)),
                    sql.strip(),
                    connKey.strip(),
                    strOr(parsed, "object_keys", ""),
                    strOr(parsed, "rationale", ""),
                    parseLiteralBindings(parsed.get("literal_bindings")));
        } catch (Exception e) {
            log.warn("ReasoningPlanner failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String question, String schemaCtx, EvidenceStore evidence) {
        return "Question: " + question + "\n\n"
                + "Approved schema:\n" + schemaCtx + "\n\n"
                + "Evidence so far:\n" + evidence.buildContextForLlm();
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    private String strOr(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return (v != null && !v.toString().isBlank()) ? v.toString() : def;
    }

    /**
     * Parses the optional {@code literal_bindings} array (PRO-33 / PRO-32 §0.2)
     * from the planner's JSON. Absent, malformed, or incomplete entries yield
     * an empty/partial list — the field is a declaration hook, never a reason
     * to fail the step. Package-private for tests.
     */
    static List<LiteralBinding> parseLiteralBindings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<LiteralBinding> out = new java.util.ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object surface = m.get("surface");
            Object column  = m.get("column");
            Object value   = m.get("value");
            if (surface == null || column == null || value == null) continue;
            String s = surface.toString().trim();
            String c = column.toString().trim();
            String v = value.toString().trim();
            if (s.isEmpty() || c.isEmpty() || v.isEmpty()) continue;
            out.add(new LiteralBinding(s, c, v));
        }
        return List.copyOf(out);
    }

    /**
     * A declared literal resolution: which user term ({@code surface}) the
     * planner mapped to which stored value on which column — the validation
     * and explainability hook of Deterministic Literal Resolution.
     */
    public record LiteralBinding(String surface, String column, String value) {}

    /**
     * Immutable value object representing a planned step — either a SQL step, or (Semantic
     * Reasoning Over Authoritative Value Domains) a declined step carrying {@code
     * clarificationQuestion} instead: the planner determined the user's term cannot be
     * defensibly resolved against an authoritative legal-values column, and no SQL should be
     * generated for it. {@code sql}/{@code connectionKey} are {@code null} in that case —
     * callers MUST check {@link #isClarification()} before attempting to execute {@code sql}.
     */
    public record StepPlan(
            String description,
            String sql,
            String connectionKey,
            String objectKeys,
            String rationale,
            // Declared literal resolutions; empty when nothing was declared (PRO-33).
            List<LiteralBinding> literalBindings,
            // Non-null/non-blank ⇒ this step is a clarification request, not a SQL step (see
            // class javadoc). Null for every pre-existing caller/constructor below.
            String clarificationQuestion
    ) {
        /** Pre-clarification shape — no clarification (PRO-33). */
        public StepPlan(String description, String sql, String connectionKey,
                        String objectKeys, String rationale, List<LiteralBinding> literalBindings) {
            this(description, sql, connectionKey, objectKeys, rationale, literalBindings, null);
        }

        /** Pre-PRO-33 shape — no declared bindings, no clarification. */
        public StepPlan(String description, String sql, String connectionKey,
                        String objectKeys, String rationale) {
            this(description, sql, connectionKey, objectKeys, rationale, List.of(), null);
        }

        public boolean isClarification() {
            return clarificationQuestion != null && !clarificationQuestion.isBlank();
        }
    }
}
