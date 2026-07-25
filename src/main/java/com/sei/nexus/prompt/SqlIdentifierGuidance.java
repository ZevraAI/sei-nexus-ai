package com.sei.nexus.prompt;

/**
 * Shared identifier-fidelity guidance for every prompt that grounds an LLM in a fixed SQL schema
 * and then asks it to write SQL. Centralised so the autonomous-agent path
 * ({@code com.sei.nexus.agentbrain.PromptAssembler}) and the conversational planner
 * ({@code com.sei.nexus.reasoning.ReasoningPlanner}) present the model one consistent,
 * authoritative contract — instead of each hard-coding its own wording.
 *
 * <p>These strings target the observed failure where GPT substitutes a canonical ERP column
 * name (e.g. {@code quantity_on_hand}) for the grounded physical column ({@code on_hand_qty}):
 * the authority statement demotes the model's schema priors, and the rules forbid renaming,
 * substituting, or inventing identifiers. Each renderer still formats the schema listing itself
 * (backticks, table headers, etc.) — only the shared instructional text lives here.
 */
public final class SqlIdentifierGuidance {

    private SqlIdentifierGuidance() {}

    /** Declares the provided schema complete and authoritative; suppresses the model's schema priors. */
    public static final String SCHEMA_AUTHORITY =
            "The following is the COMPLETE and AUTHORITATIVE database schema available to you. "
            + "No tables or columns exist beyond those listed here. Ignore any prior knowledge of ERP, "
            + "retail, inventory, or generic SQL schemas — the identifiers in this schema are the ONLY "
            + "valid SQL identifiers you may use.";

    /** Absolute rules that stop identifier renaming/substitution/invention during SQL generation. */
    public static final String IDENTIFIER_RULES =
            "IDENTIFIER RULES (absolute):\n"
            + "• The table and column names in the schema are literal SQL identifiers, not examples or "
            + "descriptions. Copy each one character-for-character.\n"
            + "• Never rename, normalize, translate, abbreviate, pluralize, reorder, or substitute an "
            + "identifier, even if a more familiar name exists. If the schema lists `on_hand_qty`, write "
            + "`on_hand_qty` exactly — never a conventional variant like quantity_on_hand.\n"
            + "• Use only the tables and columns listed in the schema. If a column you need is not listed, "
            + "do not invent one — state that the schema does not contain the requested field instead of "
            + "guessing.";
}
