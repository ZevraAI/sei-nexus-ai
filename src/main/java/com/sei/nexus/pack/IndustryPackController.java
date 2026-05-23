package com.sei.nexus.pack;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for industry context packs.
 *
 * <pre>
 * GET  /industry-packs                     list available packs
 * GET  /industry-packs/{packKey}            get pack details
 * GET  /industry-packs/applied             list packs applied to this tenant
 * POST /industry-packs/{packKey}/preview   dry-run (no DB writes)
 * POST /industry-packs/{packKey}/apply     apply pack to this tenant
 * DELETE /industry-packs/applied/{packKey} disable an applied pack
 * GET  /industry-packs/recommend           recommend a pack for this tenant's schema
 * </pre>
 */
@RestController
@RequestMapping("/industry-packs")
public class IndustryPackController {

    private final IndustryPackService packService;

    public IndustryPackController(IndustryPackService packService) {
        this.packService = packService;
    }

    // ── Catalogue ─────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listPacks() {
        List<Map<String, Object>> result = packService.listPacks().stream()
                .map(this::toSummaryMap)
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{packKey}")
    public ResponseEntity<Map<String, Object>> getPack(@PathVariable String packKey) {
        return ResponseEntity.ok(toDetailMap(packService.getPack(packKey)));
    }

    // ── Applied packs ─────────────────────────────────────────────────────────

    @GetMapping("/applied")
    public ResponseEntity<List<Map<String, Object>>> listApplied() {
        List<Map<String, Object>> result = packService.listAppliedPacks().stream()
                .map(this::toTenantPackMap)
                .toList();
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/applied/{packKey}")
    public ResponseEntity<Void> removePack(@PathVariable String packKey) {
        packService.removePack(packKey);
        return ResponseEntity.noContent().build();
    }

    // ── Preview & Apply ───────────────────────────────────────────────────────

    /**
     * Dry-run: shows what would be created without making any changes.
     * Body: { "domainKey": "..." } — optional, defaults to first available domain.
     */
    @PostMapping("/{packKey}/preview")
    public ResponseEntity<Map<String, Object>> previewPack(
            @PathVariable String packKey,
            @RequestBody(required = false) Map<String, Object> body) {
        String domainKey = body != null ? (String) body.get("domainKey") : null;
        PackPreview preview = packService.previewPack(packKey, domainKey);
        return ResponseEntity.ok(toPreviewMap(preview));
    }

    /**
     * Apply a pack to the current tenant.
     * Body: { "domainKey": "..." } — required.
     */
    @PostMapping("/{packKey}/apply")
    public ResponseEntity<Map<String, Object>> applyPack(
            @PathVariable String packKey,
            @RequestBody Map<String, Object> body) {
        String userEmail  = currentUserEmail();
        String domainKey  = body != null ? (String) body.get("domainKey") : null;
        if (domainKey == null || domainKey.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "domainKey is required");
        }
        PackApplicationResult result = packService.applyPack(packKey, domainKey, userEmail);
        return ResponseEntity.status(HttpStatus.CREATED).body(toApplicationResultMap(result));
    }

    // ── Recommendation ────────────────────────────────────────────────────────

    /**
     * Recommends the best-fitting pack for this tenant's discovered schema.
     * Body: { "domainKey": "..." } — optional.
     */
    @GetMapping("/recommend")
    public ResponseEntity<Map<String, Object>> recommend(
            @RequestParam(required = false) String domainKey) {
        Optional<PackRecommendationService.PackRecommendation> rec =
                packService.recommendForCurrentTenant(domainKey);
        if (rec.isEmpty()) {
            return ResponseEntity.ok(Map.of("recommended", false,
                    "message", "No pack matches your schema with sufficient coverage (≥40%)."));
        }
        PackRecommendationService.PackRecommendation r = rec.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("recommended",     true);
        response.put("pack_key",         r.packKey());
        response.put("display_name",     r.displayName());
        response.put("coverage_score",   r.coverageScore());
        response.put("matched_tables",   r.matchedTables());
        return ResponseEntity.ok(response);
    }

    // ── Response serialisers ──────────────────────────────────────────────────

    private Map<String, Object> toSummaryMap(IndustryPack p) {
        var m = new LinkedHashMap<String, Object>();
        m.put("pack_id",       p.packId());
        m.put("industry",      p.industry());
        m.put("display_name",  p.displayName());
        m.put("version",       p.version());
        m.put("description",   p.description());
        m.put("entity_count",  p.entities()  != null ? p.entities().size()  : 0);
        m.put("vocab_count",   p.vocabulary() != null ? p.vocabulary().size() : 0);
        m.put("question_count", p.suggestedQuestions() != null ? p.suggestedQuestions().size() : 0);
        return m;
    }

    private Map<String, Object> toDetailMap(IndustryPack p) {
        var m = new LinkedHashMap<String, Object>(toSummaryMap(p));
        m.put("entities",            p.entities()          != null ? p.entities()          : List.of());
        m.put("vocabulary",          p.vocabulary()         != null ? p.vocabulary()         : List.of());
        m.put("suggested_questions", p.suggestedQuestions() != null ? p.suggestedQuestions() : List.of());
        m.put("kpi_definitions",     p.kpiDefinitions()    != null ? p.kpiDefinitions()    : List.of());
        m.put("alert_templates",     p.alertTemplates()    != null ? p.alertTemplates()    : List.of());
        return m;
    }

    private Map<String, Object> toTenantPackMap(TenantPack tp) {
        var m = new LinkedHashMap<String, Object>();
        m.put("pack_key",       tp.packKey());
        m.put("pack_version",   tp.packVersion());
        m.put("display_name",   tp.displayName());
        m.put("status",         tp.status());
        m.put("coverage_score", tp.coverageScore());
        m.put("entity_mapping", tp.entityMapping() != null ? tp.entityMapping() : Map.of());
        m.put("applied_at",     tp.appliedAt() != null ? tp.appliedAt().toString() : null);
        m.put("applied_by",     tp.appliedBy());
        return m;
    }

    private Map<String, Object> toPreviewMap(PackPreview p) {
        var m = new LinkedHashMap<String, Object>();
        m.put("pack_id",          p.packId());
        m.put("display_name",     p.displayName());
        m.put("coverage_score",   p.coverageScore());
        m.put("entity_mapping",   p.entityMapping());
        m.put("entities_unmatched", p.entitiesUnmatched());
        m.put("vocabulary_terms_to_add",   p.vocabularyTermCount());
        m.put("questions_to_add",          p.suggestedQuestionsCount());
        m.put("alert_templates",           p.alertTemplateCount());
        return m;
    }

    private Map<String, Object> toApplicationResultMap(PackApplicationResult r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("pack_key",               r.packKey());
        m.put("display_name",           r.displayName());
        m.put("entities_created",       r.entitiesCreated());
        m.put("vocabulary_terms_added", r.vocabularyTermsAdded());
        m.put("questions_added",        r.suggestedQuestionsAdded());
        m.put("coverage_score",         r.coverageScore());
        m.put("entity_mapping",         r.entityMapping());
        m.put("entities_unmatched",     r.entitiesUnmatched());
        return m;
    }

    private String currentUserEmail() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof UserAccount u) return u.email();
        throw new NexusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
    }
}
