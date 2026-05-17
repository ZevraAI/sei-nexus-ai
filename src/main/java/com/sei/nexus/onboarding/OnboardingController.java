package com.sei.nexus.onboarding;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.connection.ConnectionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the self-serve onboarding wizard.
 * Base path: /api/v1/onboarding
 *
 * <p>Flow:
 * <pre>
 *   GET  /onboarding/status    — check current step (wizard decides which screen to show)
 *   POST /onboarding/scan      — list tables from a connected database
 *   POST /onboarding/analyze   — AI analysis of selected tables
 *   POST /onboarding/apply     — bulk-save approved entities + vocabulary
 *   POST /onboarding/complete  — mark onboarding done, return suggested questions
 * </pre>
 */
@RestController
@RequestMapping("/onboarding")
public class OnboardingController {

    private final OnboardingService      onboardingService;
    private final ConnectionRepository   connectionRepository;

    public OnboardingController(OnboardingService onboardingService,
                                 ConnectionRepository connectionRepository) {
        this.onboardingService    = onboardingService;
        this.connectionRepository = connectionRepository;
    }

    /**
     * GET /onboarding/status
     * Returns the current onboarding state for the authenticated tenant.
     *
     * <pre>
     * Response:
     * {
     *   "complete":          false,
     *   "step":              "CONNECT_DATABASE | SELECT_TABLES | COMPLETE",
     *   "connection_count":  0,
     *   "suggested_questions": []   // populated when complete = true
     * }
     * </pre>
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        // Platform admins (public schema) never need onboarding — return complete immediately.
        String schema = com.sei.nexus.tenant.TenantContext.getSchema();
        if ("public".equals(schema)) {
            return ResponseEntity.ok(java.util.Map.of(
                    "complete", true,
                    "step", "COMPLETE",
                    "connection_count", 0,
                    "suggested_questions", java.util.List.of()));
        }
        return ResponseEntity.ok(onboardingService.getStatus());
    }

    /**
     * POST /onboarding/recommend
     * AI-powered table recommendation for large databases.
     *
     * <p>Fetches all table metadata in one SQL query, sends the full schema
     * to the AI in one prompt, and returns the top 10-15 recommended tables
     * with reasoning. Result is cached in tenant settings so re-renders are free.
     *
     * <pre>
     * Request:  { "connectionKey": "...", "schemaName": "public" }
     * Response: {
     *   "recommended": [
     *     { "table_name": "orders", "reason": "...", "category": "Orders", "priority": 1 }
     *   ],
     *   "total_tables": 487,
     *   "cached": false
     * }
     * </pre>
     */
    @PostMapping("/recommend")
    public ResponseEntity<Map<String, Object>> recommend(@RequestBody Map<String, Object> body) {
        String connectionKey = require(body, "connectionKey");
        String schemaName    = resolveSchema(body, connectionKey);
        return ResponseEntity.ok(onboardingService.recommendTables(connectionKey, schemaName));
    }

    /**
     * POST /onboarding/scan
     * Lists all tables in a schema of the given connection.
     *
     * <pre>
     * Request:  { "connectionKey": "...", "schemaName": "public" }
     * Response: { "tables": [{ "table_name": "orders", "column_count": 12 }, ...] }
     * </pre>
     */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(@RequestBody Map<String, Object> body) {
        String connectionKey = require(body, "connectionKey");
        String schemaName    = resolveSchema(body, connectionKey);

        List<Map<String, Object>> tables =
                onboardingService.scanTables(connectionKey, schemaName);

        return ResponseEntity.ok(Map.of("tables", tables));
    }

    /**
     * POST /onboarding/analyze
     * AI-analyzes selected tables and returns entity / vocabulary suggestions
     * plus 3 suggested first questions per table.
     *
     * <pre>
     * Request:
     * {
     *   "connectionKey": "...",
     *   "schemaName":    "public",
     *   "domainKey":     "PLATFORM",
     *   "tableNames":    ["orders", "customers", "products"]
     * }
     * Response: { "tables": [...] }
     * </pre>
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, Object> body) {
        String connectionKey = require(body, "connectionKey");
        String schemaName    = (String) body.getOrDefault("schemaName", "public");
        String domainKey     = (String) body.getOrDefault("domainKey",  "PLATFORM");

        @SuppressWarnings("unchecked")
        List<String> tableNames = (List<String>) body.get("tableNames");
        if (tableNames == null || tableNames.isEmpty()) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "tableNames is required and must not be empty");
        }

        List<Map<String, Object>> tables =
                onboardingService.analyzeTables(connectionKey, schemaName, domainKey, tableNames);

        return ResponseEntity.ok(Map.of("tables", tables));
    }

    /**
     * POST /onboarding/apply
     * Bulk-saves approved entities and vocabulary. Also persists suggested questions.
     *
     * <pre>
     * Request:
     * {
     *   "connectionKey": "...",
     *   "schemaName":    "public",
     *   "domainKey":     "PLATFORM",
     *   "entities": [
     *     {
     *       "approved":            true,
     *       "tableName":           "orders",
     *       "entityKey":           "order",
     *       "entityName":          "Order",
     *       "purpose":             "...",
     *       "operationalMeaning":  "...",
     *       "investigationHints":  "...",
     *       "suggestedQuestions":  ["...", "...", "..."],
     *       "vocabulary": [
     *         { "approved": true, "term": "...", "definition": "...", "sqlEquivalent": "..." }
     *       ]
     *     }
     *   ]
     * }
     * Response:
     * {
     *   "entities_created":     3,
     *   "vocab_terms_created":  8,
     *   "data_objects_created": 3,
     *   "suggested_questions":  ["...", "...", "..."]
     * }
     * </pre>
     */
    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> apply(@RequestBody Map<String, Object> body) {
        String userEmail = currentUserEmail();
        Map<String, Object> result = onboardingService.applySelections(body, userEmail);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /onboarding/complete
     * Marks onboarding as complete for this tenant.
     * Returns the stored suggested first questions.
     *
     * <pre>
     * Response: { "status": "COMPLETE", "suggested_questions": ["...", "...", "..."] }
     * </pre>
     */
    @PostMapping("/complete")
    public ResponseEntity<Map<String, Object>> complete() {
        return ResponseEntity.ok(onboardingService.complete());
    }

    /**
     * POST /onboarding/reset
     * Wipes all onboarding-generated data for this tenant so the wizard
     * can be re-run from scratch. Useful for testing and re-onboarding.
     * System-seeded rows (created_by = 'system') are preserved.
     *
     * <pre>
     * Response: { "status": "RESET", "entities_deleted": 3, ... }
     * </pre>
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        return ResponseEntity.ok(onboardingService.reset());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the schema to use for a scan/recommend operation.
     * Priority: explicit schemaName in request body → connection's allowedSchemas → "public".
     */
    private String resolveSchema(Map<String, Object> body, String connectionKey) {
        String explicit = (String) body.get("schemaName");
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        return connectionRepository.findByKey(connectionKey)
                .map(c -> {
                    String s = c.allowedSchemas();
                    if (s == null || s.isBlank()) return "public";
                    // allowedSchemas may be comma-separated; use the first entry
                    return s.split(",")[0].trim();
                })
                .orElse("public");
    }

    private String require(Map<String, Object> body, String field) {
        Object val = body.get(field);
        if (val == null || val.toString().isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Field '" + field + "' is required");
        }
        return val.toString().trim();
    }

    private String currentUserEmail() {
        Object principal = SecurityContextHolder.getContext()
                                                .getAuthentication()
                                                .getPrincipal();
        if (principal instanceof UserAccount u) return u.email();
        throw new NexusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
    }
}
