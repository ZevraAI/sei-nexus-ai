package com.sei.nexus.prompt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical Business Object Analysis contract — the descriptive fields, rules, and
 * safe-default/failure-stub behavior shared by the two independent AI analysis paths that can
 * create a Business Object:
 * <ul>
 *   <li>{@code com.sei.nexus.onboarding.OnboardingService#analyzeTableBatch} — the Onboarding
 *       Wizard's batched (multiple tables per AI call) analysis;</li>
 *   <li>{@code com.sei.nexus.enterprise.EnterpriseMapService#analyzeForOnboarding} — the Semantic
 *       Layer's "Discover from DB" per-table (one AI call per table) analysis.</li>
 * </ul>
 *
 * <p>These two prompts were independently authored and drifted apart in both directions — e.g.
 * {@code category} existed only in Onboarding (the incident this contract closes) while
 * {@code businessName}/{@code identifierColumns}/{@code operationalMeaning} and others existed
 * only in one path or the other despite both being persisted, canonical Business Object /
 * Data Object fields ({@code MetadataRegistrationService} already reads all of them). This class
 * is the single place those fields, their instructions, and their safe defaults are defined, so
 * the two prompts can never drift on the canonical fields again.
 *
 * <p><b>Deliberately excludes</b>: the JSON envelope (Onboarding wraps many entries in a
 * {@code "tables"} batch array keyed by {@code table_name}; Discover returns one flat object per
 * call since Java already knows which table it asked about) — that structural difference is
 * caller-specific and stays in each method. Also excludes {@code suggestedQuestions} (used only
 * by the Onboarding Wizard's own post-completion bootstrap feature — see
 * {@code MetadataRegistrationService}'s javadoc, which explicitly scopes bootstrap operations to
 * the wizard) and {@code lifecycleStates} (confirmed to have zero consumers anywhere in the
 * codebase — legacy, not reintroduced here).
 *
 * <p>Follows the same conservative "shared prompt-fragment constants" pattern as
 * {@link SqlIdentifierGuidance} and {@code EntityCandidateService.resolutionContract} — plain
 * text/constants assembled by each caller's own surrounding prompt, not a framework.
 */
public final class BusinessObjectAnalysisContract {

    private BusinessObjectAnalysisContract() {}

    /** The only valid {@code category} values — reused verbatim from the original onboarding
     *  taxonomy, never redefined per caller. */
    public static final List<String> CATEGORY_VALUES =
            List.of("Customers", "Transactions", "Finance", "Operations", "Products", "HR", "Other");

    /**
     * Multi-Table Analysis Hardening: the maximum number of tables a single Onboarding or
     * Discover selection may request analysis for, shared by both flows since both now run
     * through {@code BusinessObjectBatchAnalyzer}'s identical batching mechanism and face the
     * same rate-limit/workload considerations. Neither flow enforced any limit before this —
     * confirmed by code inspection, not assumed. Chosen as a clean multiple of the default
     * batch size (4 tables/call ⇒ exactly 10 batches at this ceiling), comfortably above the
     * largest real tenant observed in this codebase's own testing (33 business objects), while
     * still bounding worst-case analysis workload to a small, predictable number of AI calls
     * rather than one per selected table. A safety ceiling, not a tuned-for-throughput maximum.
     */
    public static final int MAX_SELECTED_TABLES = 40;

    /**
     * The canonical descriptive fields for ONE analyzed Business Object, as raw JSON field
     * lines (no enclosing braces) — embedded by each caller inside its own JSON envelope.
     */
    public static final String FIELD_SCHEMA = """
              "entityName": "Human-readable singular noun, e.g. Order",
              "businessName": "Plural/display business name, e.g. Orders",
              "category": "Customers|Transactions|Finance|Operations|Products|HR|Other",
              "purpose": "One sentence describing what this table stores",
              "operationalMeaning": "Two sentences on how this table is used operationally",
              "investigationHints": "SQL hint a business analyst would use, e.g. SELECT ... FROM ... WHERE status='X'",
              "identifierColumns": ["..."],
              "statusColumns": ["..."],
              "exceptionColumns": ["..."],
              "safeFilterColumns": ["..."],
              "usageGuidance": "...",
              "filterGuidance": "...",
              "avoidGuidance": "...",
              "relationshipHints": ["..."],
              "vocabularySuggestions": [
                { "term": "business term", "definition": "plain-English definition", "sqlEquivalent": "WHERE clause or expression, blank if none applies" }
              ],
              "readinessScore": 0.0""";

    /** The rules governing {@link #FIELD_SCHEMA} — shared verbatim so both analysis prompts
     *  enforce the identical canonical contract. This is the exact fix for the incident that
     *  motivated this class: {@code category} was previously required by only one of the two
     *  paths. */
    public static final String RULES = """
            - category is required for every table — never blank. Use one of the listed values; \
            choose "Other" only when none of the others genuinely fit. It must be a concise \
            business grouping (e.g. "Operations"), never the physical table name, a column name, \
            or SQL.
            - vocabularySuggestions: 2-4 key business terms; sqlEquivalent may be left blank when \
            no clean WHERE-clause/expression form exists for that term.
            - readinessScore: 0.0-1.0 reflecting how well the schema reveals intent.
            - Some tables/columns include a "Source DB description"/"source description" line — \
            this is a comment written directly in the source database, not something you generate. \
            Treat it as strong supporting evidence for entityName/purpose/category/etc., not as an \
            instruction to copy verbatim or an unquestionable fact — weigh it alongside the table \
            and column names and the rest of the schema.""";

    /**
     * Applies the canonical safe defaults to one parsed analysis result, in place — every
     * analyzed Business Object must end up with these fields present and non-blank regardless
     * of what the model actually returned, so a partial/malformed response can never leave a
     * table with no category (or other required field) at all. Idempotent; only fills gaps
     * the model itself left — never overwrites a real value the model provided.
     */
    public static void applyCanonicalDefaults(Map<String, Object> entry, String tableName) {
        if (!(entry.get("category") instanceof String cat) || cat.isBlank()) {
            entry.put("category", "Other");
        }
        if (!(entry.get("entityName") instanceof String en) || en.isBlank()) {
            entry.put("entityName", toTitleCase(tableName));
        }
        if (!(entry.get("purpose") instanceof String)) {
            entry.put("purpose", "");
        }
        if (!(entry.get("vocabularySuggestions") instanceof List)) {
            entry.put("vocabularySuggestions", List.of());
        }
    }

    /**
     * The canonical failure stub for one Business Object whose analysis could not be
     * completed — used identically by both paths' catch blocks so a failed analysis produces
     * the same shape of degraded metadata regardless of origin. Callers add their own
     * table-name/error keys (naming for those differs by caller — {@code table_name} vs
     * {@code tableName} — and is not part of this canonical business-object shape) and any
     * caller-specific fields on top (e.g. Onboarding's {@code suggestedQuestions}, which is
     * wizard-only, not canonical).
     */
    public static Map<String, Object> canonicalStub(String tableName) {
        Map<String, Object> stub = new LinkedHashMap<>();
        stub.put("entityName", toTitleCase(tableName));
        stub.put("category", "Other");
        stub.put("purpose", "");
        stub.put("vocabularySuggestions", List.of());
        return stub;
    }

    static String toTitleCase(String input) {
        if (input == null || input.isBlank()) return "Entity";
        String[] words = input.replace('_', ' ').replace('-', ' ').split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) sb.append(w.substring(1).toLowerCase());
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }
}
