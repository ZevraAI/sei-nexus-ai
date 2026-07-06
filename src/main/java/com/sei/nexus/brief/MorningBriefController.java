package com.sei.nexus.brief;

import com.sei.nexus.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/brief")
public class MorningBriefController {

    private final MorningBriefService briefService;

    public MorningBriefController(MorningBriefService briefService) {
        this.briefService = briefService;
    }

    /**
     * GET /brief — returns today's brief for the current tenant, or null if not yet generated.
     * Frontend polls this until status = READY.
     */
    @GetMapping
    public ResponseEntity<?> getToday() {
        String schema = TenantContext.getSchema();
        Optional<MorningBrief> brief = briefService.getTodaysBrief(schema);
        return brief
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * POST /brief/generate — triggers on-demand generation (or regeneration).
     * Returns 202 Accepted immediately; poll GET /brief for status.
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generate() {
        String schema = TenantContext.getSchema();
        String briefId = briefService.requestGeneration(schema);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("briefId", briefId, "status", "GENERATING"));
    }

    /**
     * GET /brief/config — returns the tenant's schedule configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        String schema = TenantContext.getSchema();
        return briefService.getConfig(schema)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * PUT /brief/config — save or update the schedule configuration.
     * Body: { "schedule_time": "07:00", "timezone": "America/New_York",
     *         "enabled": true, "email_to": "ceo@acme.com" }
     */
    @PutMapping("/config")
    public ResponseEntity<?> saveConfig(@RequestBody Map<String, Object> body) {
        String schema = TenantContext.getSchema();
        @SuppressWarnings("unchecked")
        List<String> agentIds = body.containsKey("brief_agent_ids")
                ? (List<String>) body.get("brief_agent_ids")
                : List.of();

        MorningBriefConfig config = new MorningBriefConfig(
                schema,
                getStr(body, "schedule_time", "07:00"),
                getStr(body, "timezone", "UTC"),
                body.containsKey("enabled") ? Boolean.TRUE.equals(body.get("enabled")) : true,
                (String) body.get("email_to"),
                agentIds,
                null);
        return ResponseEntity.ok(briefService.saveConfig(config));
    }

    private String getStr(Map<String, Object> body, String key, String fallback) {
        Object v = body.get(key);
        return (v != null && !v.toString().isBlank()) ? v.toString() : fallback;
    }
}
