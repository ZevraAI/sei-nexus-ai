package com.sei.nexus.reasoning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.prompt.SqlIdentifierGuidance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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
            Every turn, choose exactly ONE of three equally valid actions — whichever the
            evidence actually calls for:
              (a) SQL — only when you already have confirmed columns, from "Approved schema"
                  or a prior metadata response, for every table your query references, AND
                  every literal your SQL filters on is authorized per the LITERAL AUTHORITY
                  RULE below.
              (b) A metadata request — whenever any table you need is missing its column
                  list (never shown, "(columns omitted...)", or known only via a
                  JOIN/relationship hint).
              (c) A clarification question — whenever the user's wording cannot be
                  confidently resolved to an authorized literal per the LITERAL AUTHORITY
                  RULE below.
            (a) without confirmed columns for every referenced table, or with a literal that
            fails the LITERAL AUTHORITY RULE, is not a shortcut to the SQL you were asked to
            produce — it is a different, disallowed action. There is no fourth action: you
            never guess a plausible-looking literal and never leave a term unresolved in
            silence.

            LITERAL AUTHORITY RULE — applies to every literal you are about to write into a
            filter, before you write it:
              The user's wording is evidence of intent. It is never, by itself, authoritative
              database metadata. A literal you write into SQL must be authorized by ONE of:
                A. EXACT MATCH — the term names one of the column's listed legal values
                   (allowing only case/whitespace differences) → use that legal value.
                B. AUTHORITATIVE MAPPING — the term is a business phrase, and this context
                   (RESOLUTIONS, business entity/vocabulary definitions, or an explicit
                   business-concept mapping shown to you) explicitly defines it as
                   corresponding to one or more of the column's legal values → use those
                   exact legal values.
                C. UNCONSTRAINED FREE TEXT — the column has no legal-values list at all (see
                   the free-text guidance below) → tolerant matching on the user's own
                   wording is allowed, because there is no closed domain to violate.
              If none of A, B, or C applies — the term matches no legal value and no
              authoritative mapping resolves it, on a column that DOES have a legal-values
              domain — you MUST NOT invent, guess, or substitute a legal-sounding value, and
              you MUST NOT filter on the user's own literal term either. Ask a clarification
              question instead (action (c)). A user typing a word is never sufficient authorization on its own,
              no matter how plausible that word looks as a status, category, or state.

            Rules:
            - Use only the tables and columns listed under "Approved schema".
            - "connection_key" is an opaque routing token, NEVER a table or column name. Each
              TABLE entry in "Approved schema" is annotated with its own line reading
              "connection_key: conn-xxxxxxxx (use this exact value)" — copy that exact string
              (it always starts with "conn-") into the "connection_key" field of your response.
              Do not substitute the table name, a column name, or any other identifier from the
              query you just wrote — those answer a different question (what to select) and are
              never a valid connection_key. If the step queries more than one table, use the
              connection_key belonging to the table your SQL actually reads from.
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
            - Before generating SQL, ensure that any resolved object whose attributes are required
              to answer the user's request has had its own metadata/column list provided to you.
              Knowing that an object exists is NOT the same as having its metadata. An object
              name, an identifier, relationship/JOIN guidance, or an individual column learned
              from another context does not constitute having that object's metadata — a
              relationship/JOIN hint may be used for relationship reasoning (which tables join to
              which), but it must never be treated as that object's complete attribute list, and
              must never be used as a basis to select or invent any other column on that object.
              If the required object's metadata has not been provided — whether because its
              column list was never shown, was shown as "(columns omitted to fit the context
              budget)", or you only know one of its columns via a relationship/JOIN hint —
              do not guess or infer additional columns from conventional naming. Instead of
              "sql", respond with a metadata request for that already-resolved object:
              {"done":false,"description":"one-line goal","requires_metadata":{"object":"<the exact table name, or business name, as shown in Approved schema>","metadataType":"columns"},"rationale":"why this object's columns are needed"}
              You will receive that object's real column list on your next turn and can then
              continue planning with it. Only request metadata for an object already named
              somewhere in "Approved schema" — never for one you have no evidence exists.
              The value you put in "object" MUST be the exact table name or exact business name
              as shown under "Approved schema" — that is the ONLY authoritative identity source
              for this field. A Knowledge Graph section, if present, names business entities and
              concepts for relationship reasoning; its labels are NOT the authoritative object
              identity and must never be substituted for the corresponding "Approved schema"
              identity — even when the Knowledge Graph label is singular, plural, or otherwise
              worded differently from the "Approved schema" identity. Do not expect Java to
              match, normalize, or correct a Knowledge Graph label into the right identity for
              you — copy the identity from "Approved schema" yourself, exactly as shown.
            - Before finalizing an answer that identifies, names, or describes an entity, ensure
              the evidence contains an appropriate descriptive attribute for that entity. Merely
              knowing an entity's identifier — including one learned through JOIN/relationship
              guidance — does not constitute having sufficient descriptive metadata for the
              requested answer. If the required object's metadata has not been provided, use
              "requires_metadata" for it rather than guessing a column or presenting the
              identifier alone as the answer.
            - Joins, aggregations, GROUP BY, ORDER BY, LIMIT are all allowed.
            - SUPPLEMENTARY BREAKDOWN STEPS (optional, your own judgment): after a step already
              answers the user's primary question, look at what you now know about that result —
              does it have a natural categorical dimension (e.g. status, category, buyer, owner)
              or a natural time dimension (e.g. month, week) where a simple count/aggregate
              breakdown along that dimension would add genuine analytical or visual value to
              answering the business question? If so, you MAY plan one or two additional steps —
              never more — that run a simple aggregate query (e.g. "SELECT status, COUNT(*) AS
              order_count FROM ... GROUP BY status") against the same approved tables/columns,
              before returning {"done": true}. This is entirely optional and driven by business
              judgment: never mandatory, never for every question, and never for a simple lookup
              of one record or a single identifier/value — only when a breakdown genuinely helps
              the user understand the result (typically a "show me all/list all ..." style
              question over a set of records that has an obvious grouping dimension). Base this
              decision purely on whether the RESULT DATA itself has a natural grouping dimension
              worth surfacing — never on whether the user's own phrasing happened to use words
              like "group by", "breakdown", "by category", or similar. A plain "show me all open
              purchase orders" deserves exactly the same consideration for a supplementary step as
              "show me all open purchase orders grouped by status" — if the underlying data has a
              natural grouping dimension, propose it regardless of how the question was worded;
              if it doesn't, decline regardless of how the question was worded. Each such
              step is a normal SQL step in every respect: it follows every rule above (approved
              schema/columns only, the LITERAL AUTHORITY RULE, no SELECT *, its own
              connection_key) and goes through the same governance as any other step — there is
              no shortcut or bypass for it. Give it a clear, business-facing "description" (e.g.
              "Open orders by status", "Orders by buyer", "Orders by expected delivery month")
              since that description is shown to the user as this step's own title — write it
              accordingly.
            - OPTIONAL VISUALIZATION HINT: when you plan a step whose result would genuinely
              benefit from a chart — most naturally a SUPPLEMENTARY BREAKDOWN STEP above, but any
              step you judge chart-worthy — you MAY add a "chart_hint" object to that SAME step's
              SQL response: {"chart_hint":{"chart_type":"bar","category_key":"status",
              "category_label":"Order Status","value_keys":["order_count"],
              "value_labels":["Order Count"],"metric_label":"Total Orders"}}. "chart_type" must be
              exactly one of "stats", "bar", "area", "donut" — pick the shape that actually fits
              what you're returning (e.g. a handful of categories each with one count is a natural
              "donut" or "bar"; a single summary number is "stats"; a value over dates/months is
              "area"). "category_key" names the column or alias — copied from THIS step's own
              SELECT list, exactly as you aliased it — to group/label by; omit it for "stats".
              "value_keys" lists one or more numeric column/alias names, also from THIS step's own
              SELECT list, to chart — e.g. if your SQL writes "COUNT(*) AS order_count", use
              "order_count" in "value_keys", never the raw "COUNT(*)" expression. This is entirely
              optional and your own judgment call: never mandatory, never for every step, and it
              never changes what SQL you write — only whether you additionally describe how to
              chart the result you're already returning. Omit "chart_hint" entirely for a step
              that doesn't warrant its own chart. You alone decide chart type and category/value
              columns here — never infer this from table or column naming conventions Java might
              apply; if you reference a column or alias that turns out not to be present in this
              step's own result, it is safely ignored, never an error — so only ever reference
              columns/aliases you actually selected in this same step's SQL.
            - OPTIONAL HUMAN-READABLE LABELS (part of the same "chart_hint" object above): you
              wrote the SQL, so you already know what a raw column alias like "order_count" or
              "total_order_count" actually MEANS in plain English — say so directly, once, rather
              than leaving Java to guess or show the raw alias to the user. All three are optional
              and independent of each other and of whether you also set a chart type:
                • "category_label" — a short, human-readable label for what "category_key" groups
                  by (e.g. category_key "status" → category_label "Order Status"). Omit when the
                  raw key is already a fine label or you have no better one.
                • "value_labels" — a list the SAME LENGTH AND ORDER as "value_keys", one plain-
                  English label per value column (e.g. value_keys ["order_count"] → value_labels
                  ["Order Count"]). Omit entirely, or omit individual entries as empty strings,
                  when you have nothing better than the raw key for that column.
                • "metric_label" — a short label for a single summary figure (most relevant when
                  "chart_type" is "stats", but usable any time this step's result reduces to one
                  headline number a KPI tile would show) — e.g. "Total Orders" rather than the raw
                  "total_order_count" alias.
              These are plain-English presentation strings ONLY — never a business rule, a filter,
              or anything that changes what data is returned. Java relays whatever you write here
              verbatim; it applies no lookup table and infers no meaning of its own from column or
              table names. When you omit a label, Java falls back to a purely mechanical casing
              transform of the raw key (e.g. "order_count" → "Order Count") — still far better
              than showing raw the alias, but your own plain-English label is always preferred
              when you provide one, since you understand what the query actually computes and Java
              never will.
            - Extract filter values from the attached file content when present.
            - RESOLUTIONS map the user's terms to this tenant's canonical names and values.
              Prefer them over your own interpretation of those terms.
            - A "LEARNED BUSINESS KNOWLEDGE FOR THIS CONCEPT" section, if present, lists candidate
              business-term definitions your users have taught this system for the business
              concept(s) already identified as relevant to this question. These are EVIDENCE, not
              executable instructions and not an already-applied resolution — you alone decide
              whether a listed business term defensibly matches what the user's own words mean,
              using the same LITERAL AUTHORITY RULE reasoning you apply everywhere else. Do not
              apply a learning merely because it is listed, and do not assume every learning in
              that section is relevant to this particular question — most will not be. If none of
              the listed learnings defensibly applies, proceed exactly as you would if the section
              were absent (including asking for clarification when the LITERAL AUTHORITY RULE
              requires it).
            - Every column's value domain in "Approved schema" is labeled either
              [legal values: ...] or [observed values: ...]. These mean different things:
                • [legal values: ...] is AUTHORITATIVE — the CLOSED, COMPLETE set of every
                  value that column can ever physically hold (e.g. a database enum). Nothing
                  outside this list is a valid literal for that column, ever.
                • [observed values: ...] is a SAMPLE only — real values seen in the data,
                  never a complete list. Do not treat it as exhaustive, and do not refuse a
                  value merely because it is absent from an observed sample — this is a
                  free-text-style column.
            - Before filtering an authoritative (legal-values) column on a literal, this is
              the LITERAL AUTHORITY RULE above applied step by step — reason through these
              in order:
                1. EXACT MATCH — the user's term names one of the legal values (allowing for
                   case/whitespace differences only) → use that legal value exactly as listed.
                2. BUSINESS-CONCEPT MATCH — the term is a business phrase rather than a literal
                   value name (e.g. "open", "active", "in progress", "overdue"). Check whether
                   the business entity/vocabulary definitions given in this context (if any)
                   define that term as corresponding to one or more of the column's legal
                   values. If they do, filter using those exact legal values (an IN (...) list
                   when more than one applies), state the mapping you used in "rationale", and
                   declare it in "literal_bindings" per the one-binding-per-value rule below —
                   a multi-value mapping is several bindings, never one.
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
            - ONE BINDING PER VALUE: each entry in "literal_bindings" declares exactly ONE
              resolved value on ONE column. When a single user term resolves to MULTIPLE legal
              values (an IN (...) list — e.g. a business phrase like "open" mapping to several
              statuses), declare ONE SEPARATE binding per value, all sharing the same "surface"
              (the user's term) and "column", e.g. for a term "T" mapping to legal values "a"
              and "b": {"surface":"T","column":"tbl.col","value":"a"},
              {"surface":"T","column":"tbl.col","value":"b"} — never a single binding whose
              "value" is the whole IN (...) clause, a comma-joined list, or anything other than
              one bare legal value exactly as listed in "Approved schema". A binding's "value"
              is always checked as a standalone literal, so any value that is not itself one
              exact legal value will be rejected even when every value the clause actually
              filters on is individually legal.
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

            Return JSON only (no markdown, no explanation) — the shape for whichever of
            (a)/(b)/(c)/done above applies to this turn:

            (a) SQL:
            {"done":false,"description":"one-line goal","sql":"SELECT ...","connection_key":"conn-xxxxxxxx","object_keys":"key1,key2","rationale":"why this step advances the investigation","literal_bindings":[{"surface":"TX","column":"stores.state_province","value":"Texas"}],"chart_hint":{"chart_type":"bar","category_key":"status","category_label":"Order Status","value_keys":["order_count"],"value_labels":["Order Count"],"metric_label":"Total Orders"}}
            ("connection_key" above is a placeholder shape only — always replace it with the
            real "connection_key: ..." value copied from the TABLE entry you queried, never the
            literal text "conn-xxxxxxxx". "chart_hint" is entirely optional — see the OPTIONAL
            VISUALIZATION HINT rule above; omit it completely for a step that doesn't warrant a
            chart of its own. "literal_bindings" above shows the single-value shape; for a term
            that resolves to several legal values — e.g. an IN (...) clause — declare one binding
            per value, per the ONE BINDING PER VALUE rule above, such as
            [{"surface":"T","column":"tbl.col","value":"a"},{"surface":"T","column":"tbl.col","value":"b"}],
            never one binding whose "value" is the whole clause.)

            (b) Metadata request (see the metadata-request rule above):
            {"done":false,"description":"one-line goal","requires_metadata":{"object":"table_name","metadataType":"columns"},"rationale":"why this table's columns are needed"}

            (c) Clarification (the LITERAL AUTHORITY RULE applies — a term you cannot
            authorize against an authoritative legal-values column):
            {"done":false,"clarification_question":"one clear question naming the term you could not resolve and listing the actual legal values so the user can choose","rationale":"why no legal value or authoritative mapping matched"}

            Done (no further queries needed):
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
            // Phase 2 Responses API migration: transport-only — same prompt/context, same JSON
            // extraction/parsing below. Phase 1 explicit prompt caching: identical request shape,
            // additionally attaching prompt_cache_key="zevra:planner:v1" (a pure cache-routing
            // hint). Phase 4 Structured Outputs: the response now carries an OpenAI strict
            // json_schema (see #plannerJsonSchema) guaranteeing the shape below — every field this
            // method already reads (done/requires_metadata/clarification_question/sql/
            // connection_key/literal_bindings/chart_hint/...) is now always present (possibly
            // null), never absent. The parsing/precedence logic itself is completely unchanged:
            // a schema-guaranteed null field is indistinguishable, to this code, from the same
            // field previously being absent from a loosely-formatted response.
            String raw    = aiClient.respondForPlanner(List.of(ChatMessage.user(prompt)), SYSTEM_PROMPT,
                    "planner_step", plannerJsonSchema());
            String json   = extractJson(raw);
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            if (Boolean.TRUE.equals(parsed.get("done"))) return null;

            // Missing-Column Metadata Request: the planner's own, explicit way to decline
            // generating SQL when it needs a column from a table whose detail was omitted (by
            // the presentation budget) or never shown — see the SYSTEM_PROMPT's metadata-request
            // rule. Checked before "sql"/clarification so a response carrying more than one of
            // these fields is still treated as a metadata request, never silently falls through
            // to SQL built on a table it was never given columns for.
            MetadataRequest metadataRequest = parseMetadataRequest(parsed.get("requires_metadata"));
            if (metadataRequest != null) {
                return StepPlan.metadataRequest(
                        strOr(parsed, "description", "Requesting column metadata"),
                        strOr(parsed, "rationale", ""),
                        metadataRequest.object(), metadataRequest.metadataType());
            }

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

            // Optional LLM-declared chart hint (see SYSTEM_PROMPT's OPTIONAL VISUALIZATION HINT
            // guidance) — parsed and relayed verbatim; Java performs no interpretation of it here.
            ChartHint chartHint = parseChartHint(parsed.get("chart_hint"));

            return StepPlan.sqlWithChartHint(
                    strOr(parsed, "description", "Investigation step " + (evidence.stepCount() + 1)),
                    sql.strip(),
                    connKey.strip(),
                    strOr(parsed, "object_keys", ""),
                    strOr(parsed, "rationale", ""),
                    parseLiteralBindings(parsed.get("literal_bindings")),
                    chartHint == null ? null : chartHint.chartType(),
                    chartHint == null ? null : chartHint.categoryKey(),
                    chartHint == null ? List.of() : chartHint.valueKeys(),
                    chartHint == null ? null : chartHint.categoryLabel(),
                    chartHint == null ? List.of() : chartHint.valueLabels(),
                    chartHint == null ? null : chartHint.metricLabel());
        } catch (Exception e) {
            log.warn("ReasoningPlanner failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * The strict JSON Schema for the SYSTEM_PROMPT's four response shapes — (a) SQL, (b) metadata
     * request, (c) clarification, (d) done — formalized as ONE FLAT OpenAI Structured-Outputs
     * strict schema (mirrors {@code ChatService#dataAnswerJsonSchema}'s established idiom: every
     * property required, genuine per-shape optionality expressed as nullable rather than absent).
     * A true discriminated union is not usable here — OpenAI strict mode forbids {@code anyOf} at
     * the schema root — so this single object carries every field any shape might populate, all
     * others left {@code null}; {@link #nextStep}'s own precedence checks (done →
     * requires_metadata → clarification_question → sql) are completely unchanged and already
     * treat a schema-guaranteed null exactly like a previously-absent key.
     *
     * <p>STRUCTURE enforcement only: which keys exist and their shape. Every field's actual
     * content — which shape the planner chooses, what SQL it writes, which literal bindings it
     * declares — remains entirely the model's judgment; nothing here moves reasoning into Java.
     *
     * <p>Package-private static seam — a pure function of no inputs, for direct unit testing.
     */
    static Map<String, Object> plannerJsonSchema() {
        Map<String, Object> metadataRequestProps = new LinkedHashMap<>();
        metadataRequestProps.put("object", Map.of("type", "string"));
        metadataRequestProps.put("metadataType", Map.of("type", List.of("string", "null")));
        Map<String, Object> metadataRequestSchema = new LinkedHashMap<>();
        metadataRequestSchema.put("type", "object");
        metadataRequestSchema.put("properties", metadataRequestProps);
        metadataRequestSchema.put("required", List.of("object", "metadataType"));
        metadataRequestSchema.put("additionalProperties", false);

        Map<String, Object> bindingProps = new LinkedHashMap<>();
        bindingProps.put("surface", Map.of("type", "string"));
        bindingProps.put("column", Map.of("type", "string"));
        bindingProps.put("value", Map.of("type", "string"));
        Map<String, Object> bindingSchema = new LinkedHashMap<>();
        bindingSchema.put("type", "object");
        bindingSchema.put("properties", bindingProps);
        bindingSchema.put("required", List.of("surface", "column", "value"));
        bindingSchema.put("additionalProperties", false);

        Map<String, Object> chartHintProps = new LinkedHashMap<>();
        chartHintProps.put("chart_type", Map.of("type", "string",
                "enum", List.of("stats", "bar", "area", "donut")));
        chartHintProps.put("category_key", Map.of("type", List.of("string", "null")));
        chartHintProps.put("value_keys", Map.of(
                "type", List.of("array", "null"),
                "items", Map.of("type", "string")));
        chartHintProps.put("category_label", Map.of("type", List.of("string", "null")));
        chartHintProps.put("value_labels", Map.of(
                "type", List.of("array", "null"),
                "items", Map.of("type", "string")));
        chartHintProps.put("metric_label", Map.of("type", List.of("string", "null")));
        Map<String, Object> chartHintSchema = new LinkedHashMap<>();
        chartHintSchema.put("type", "object");
        chartHintSchema.put("properties", chartHintProps);
        chartHintSchema.put("required", List.of("chart_type", "category_key", "value_keys",
                "category_label", "value_labels", "metric_label"));
        chartHintSchema.put("additionalProperties", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("done", Map.of("type", "boolean"));
        properties.put("description", Map.of("type", List.of("string", "null")));
        properties.put("sql", Map.of("type", List.of("string", "null")));
        properties.put("connection_key", Map.of("type", List.of("string", "null")));
        properties.put("object_keys", Map.of("type", List.of("string", "null")));
        properties.put("rationale", Map.of("type", List.of("string", "null")));
        properties.put("literal_bindings", Map.of(
                "type", List.of("array", "null"),
                "items", bindingSchema));
        chartHintSchema.put("type", List.of("object", "null"));
        properties.put("chart_hint", chartHintSchema);
        metadataRequestSchema.put("type", List.of("object", "null"));
        properties.put("requires_metadata", metadataRequestSchema);
        properties.put("clarification_question", Map.of("type", List.of("string", "null")));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("done", "description", "sql", "connection_key",
                "object_keys", "rationale", "literal_bindings", "chart_hint", "requires_metadata",
                "clarification_question"));
        schema.put("additionalProperties", false);
        return schema;
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
     * Parses the optional {@code requires_metadata} object from the planner's JSON — Missing-
     * Column Metadata Request. {@code null} when absent, malformed, or missing a non-blank
     * {@code object} — the field is a declaration hook, like {@code literal_bindings}, never a
     * reason to fail the step. Java performs no interpretation here beyond structural parsing:
     * whatever string the model put in {@code object} is passed through verbatim for {@link
     * ColumnMetadataRequestHandler} to validate by exact match.
     */
    private MetadataRequest parseMetadataRequest(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Object object = m.get("object");
        if (!(object instanceof String s) || s.isBlank()) return null;
        Object typeObj = m.get("metadataType");
        String metadataType = (typeObj instanceof String t && !t.isBlank()) ? t.trim() : "columns";
        return new MetadataRequest(s.trim(), metadataType);
    }

    /**
     * A Missing-Column Metadata Request: the planner has already decided {@code object} (a
     * physical table or business name copied verbatim from "Approved schema") is relevant, but
     * has not been shown its columns, and is asking Java to retrieve them rather than guessing.
     * Java's role is limited to validating {@code object} against the resolved/approved object
     * set and retrieving its authoritative columns — see {@link ColumnMetadataRequestHandler}.
     */
    public record MetadataRequest(String object, String metadataType) {}

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
     * Parses the optional {@code chart_hint} object — an entirely optional, LLM-declared
     * visualization intent for this step's own result (see SYSTEM_PROMPT's OPTIONAL
     * VISUALIZATION HINT guidance). {@code null} when absent, malformed, or {@code chart_type}
     * is blank — never a reason to fail the step. Java performs no interpretation here beyond
     * structural parsing: {@code chartType}/{@code categoryKey}/{@code valueKeys} are passed
     * through verbatim; whether the referenced column(s) actually exist in this step's own
     * result is checked later, mechanically, by {@code ResponseArtifactsBuilder} — never here.
     */
    private ChartHint parseChartHint(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Object typeObj = m.get("chart_type");
        if (!(typeObj instanceof String t) || t.isBlank()) return null;
        String categoryKey = null;
        Object catObj = m.get("category_key");
        if (catObj instanceof String c && !c.isBlank()) categoryKey = c.trim();
        List<String> valueKeys = new java.util.ArrayList<>();
        Object valsObj = m.get("value_keys");
        if (valsObj instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) valueKeys.add(o.toString().trim());
            }
        }
        // Optional, purely presentational LLM-authored labels — parsed and relayed verbatim, like
        // chartType/categoryKey/valueKeys above; Java performs no interpretation of what they mean.
        String categoryLabel = null;
        Object catLabelObj = m.get("category_label");
        if (catLabelObj instanceof String cl && !cl.isBlank()) categoryLabel = cl.trim();
        List<String> valueLabels = new java.util.ArrayList<>();
        Object valLabelsObj = m.get("value_labels");
        if (valLabelsObj instanceof List<?> list) {
            for (Object o : list) valueLabels.add(o == null ? "" : o.toString().trim());
        }
        String metricLabel = null;
        Object metricLabelObj = m.get("metric_label");
        if (metricLabelObj instanceof String ml && !ml.isBlank()) metricLabel = ml.trim();
        return new ChartHint(t.trim(), categoryKey, List.copyOf(valueKeys),
                categoryLabel, List.copyOf(valueLabels), metricLabel);
    }

    /** Parsed shape of the optional {@code chart_hint} — see {@link #parseChartHint}. {@code
     *  categoryLabel}/{@code valueLabels}/{@code metricLabel} are the optional, purely
     *  presentational LLM-authored labels (OPTIONAL HUMAN-READABLE LABELS guidance) — never
     *  Java-derived, never validated for correctness beyond structural parsing. */
    private record ChartHint(String chartType, String categoryKey, List<String> valueKeys,
                              String categoryLabel, List<String> valueLabels, String metricLabel) {}

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
            String clarificationQuestion,
            // Non-null ⇒ this step is a Missing-Column Metadata Request, not a SQL step or a
            // clarification. Null for every pre-existing caller/constructor below.
            MetadataRequest metadataRequest,
            // Optional LLM-declared chart-hint fields for THIS step's own result (see
            // SYSTEM_PROMPT's OPTIONAL VISUALIZATION HINT guidance and ReasoningPlanner#parseChartHint)
            // — additive, never inspected/validated for correctness beyond a later mechanical
            // existence check against this step's own row keys (see
            // ResponseArtifactsBuilder.evidence()). Null/empty for every pre-existing caller below
            // and for a step the model chose not to hint.
            String chartType,
            String categoryKey,
            List<String> valueKeys,
            // Optional, purely presentational LLM-authored labels for the chart hint fields above
            // — see ReasoningPlanner.SYSTEM_PROMPT's OPTIONAL HUMAN-READABLE LABELS guidance and
            // #parseChartHint. Never Java-derived; relayed verbatim. Null/empty for every
            // pre-existing caller/constructor below and for a step whose model-declared hint (if
            // any) didn't include labels.
            String categoryLabel,
            List<String> valueLabels,
            String metricLabel
    ) {
        /** Pre-label shape — chart hint fields present, no LLM-authored labels. */
        public StepPlan(String description, String sql, String connectionKey,
                        String objectKeys, String rationale, List<LiteralBinding> literalBindings,
                        String clarificationQuestion, MetadataRequest metadataRequest,
                        String chartType, String categoryKey, List<String> valueKeys) {
            this(description, sql, connectionKey, objectKeys, rationale, literalBindings,
                    clarificationQuestion, metadataRequest, chartType, categoryKey, valueKeys,
                    null, List.of(), null);
        }

        /** Pre-metadata-request shape — no metadata request, no chart hint. */
        public StepPlan(String description, String sql, String connectionKey,
                        String objectKeys, String rationale, List<LiteralBinding> literalBindings,
                        String clarificationQuestion, MetadataRequest metadataRequest) {
            this(description, sql, connectionKey, objectKeys, rationale, literalBindings,
                    clarificationQuestion, metadataRequest, null, null, List.of());
        }

        /** Pre-metadata-request shape — no metadata request. */
        public StepPlan(String description, String sql, String connectionKey,
                        String objectKeys, String rationale, List<LiteralBinding> literalBindings,
                        String clarificationQuestion) {
            this(description, sql, connectionKey, objectKeys, rationale, literalBindings,
                    clarificationQuestion, null);
        }

        /** Pre-clarification shape — no clarification (PRO-33). */
        public StepPlan(String description, String sql, String connectionKey,
                        String objectKeys, String rationale, List<LiteralBinding> literalBindings) {
            this(description, sql, connectionKey, objectKeys, rationale, literalBindings, null, null);
        }

        /** Pre-PRO-33 shape — no declared bindings, no clarification. */
        public StepPlan(String description, String sql, String connectionKey,
                        String objectKeys, String rationale) {
            this(description, sql, connectionKey, objectKeys, rationale, List.of(), null, null);
        }

        public boolean isClarification() {
            return clarificationQuestion != null && !clarificationQuestion.isBlank();
        }

        public boolean isMetadataRequest() {
            return metadataRequest != null;
        }

        /** True when the planner declared a usable chart hint for this step. Java's own later use
         *  of this is limited to a mechanical existence check — see ResponseArtifactsBuilder. */
        public boolean hasChartHint() {
            return chartType != null && !chartType.isBlank();
        }

        /** Constructs a metadata-request step — the only non-SQL, non-clarification StepPlan shape. */
        public static StepPlan metadataRequest(String description, String rationale,
                                               String object, String metadataType) {
            return new StepPlan(description, null, null, "", rationale, List.of(), null,
                    new MetadataRequest(object, metadataType));
        }

        /** Constructs a normal SQL step optionally carrying the planner's own chart hint (see
         *  SYSTEM_PROMPT's OPTIONAL VISUALIZATION HINT guidance) — used by ReasoningPlanner#nextStep's
         *  SQL-response parsing. {@code chartType}/{@code categoryKey}/{@code valueKeys} are null/
         *  empty when the planner didn't declare a hint for this step. */
        public static StepPlan sqlWithChartHint(String description, String sql, String connectionKey,
                String objectKeys, String rationale, List<LiteralBinding> literalBindings,
                String chartType, String categoryKey, List<String> valueKeys) {
            return sqlWithChartHint(description, sql, connectionKey, objectKeys, rationale, literalBindings,
                    chartType, categoryKey, valueKeys, null, List.of(), null);
        }

        /** As above, additionally carrying the planner's optional, purely presentational
         *  LLM-authored labels (see SYSTEM_PROMPT's OPTIONAL HUMAN-READABLE LABELS guidance) —
         *  null/empty when the planner didn't declare any. */
        public static StepPlan sqlWithChartHint(String description, String sql, String connectionKey,
                String objectKeys, String rationale, List<LiteralBinding> literalBindings,
                String chartType, String categoryKey, List<String> valueKeys,
                String categoryLabel, List<String> valueLabels, String metricLabel) {
            return new StepPlan(description, sql, connectionKey, objectKeys, rationale, literalBindings,
                    null, null, chartType, categoryKey, valueKeys == null ? List.of() : valueKeys,
                    categoryLabel, valueLabels == null ? List.of() : valueLabels, metricLabel);
        }
    }
}
