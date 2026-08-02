package com.sei.nexus.reasoning;

import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/reasoning")
public class ReasoningController {

    private final ReasoningRepository reasoningRepository;

    public ReasoningController(ReasoningRepository reasoningRepository) {
        this.reasoningRepository = reasoningRepository;
    }

    /** GET /reasoning/sessions[?conversationId=] — tenant-wide recent sessions when omitted. */
    @GetMapping("/sessions")
    public ResponseEntity<List<ReasoningSession>> findSessions(
            @RequestParam(required = false) String conversationId) {
        return ResponseEntity.ok(
                (conversationId == null || conversationId.isBlank())
                        ? reasoningRepository.findRecentSessions(50)
                        : reasoningRepository.findSessionsByConversation(conversationId));
    }

    /** GET /reasoning/sessions/{sessionKey} — session + steps + hypotheses */
    @GetMapping("/sessions/{sessionKey}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionKey) {
        // We look up by runKey? No — sessionKey is the PK. Use a direct query.
        // We need a findBySessionKey method. Use findSessionsByConversation would need conv.
        // Instead query directly via findStepsBySession and findHypothesesBySession,
        // and find the session via a helper.
        List<ReasoningStep> steps = reasoningRepository.findStepsBySession(sessionKey);
        List<Hypothesis> hypotheses = reasoningRepository.findHypothesesBySession(sessionKey);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_key", sessionKey);
        result.put("steps", steps);
        result.put("hypotheses", hypotheses);
        return ResponseEntity.ok(result);
    }

    /** GET /reasoning/findings[?domainKey=&status=] — tenant-wide (all domains/statuses) when domainKey omitted. */
    @GetMapping("/findings")
    public ResponseEntity<List<OperationalFinding>> getFindings(
            @RequestParam(required = false) String domainKey,
            @RequestParam(required = false) String status) {
        if (domainKey == null || domainKey.isBlank()) {
            // Homepage call: recent findings across every domain; null status → all statuses,
            // the client filters actionable vs observed.
            return ResponseEntity.ok(reasoningRepository.findRecentFindingsAllDomains(status, 50));
        }
        String s = (status == null || status.isBlank()) ? "ACTIVE" : status;
        return ResponseEntity.ok(reasoningRepository.findFindingsByDomain(domainKey, s));
    }

    /** GET /reasoning/findings/{findingKey} */
    @GetMapping("/findings/{findingKey}")
    public ResponseEntity<OperationalFinding> getFinding(@PathVariable String findingKey) {
        return ResponseEntity.ok(
                reasoningRepository.findFindingByKey(findingKey)
                        .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                                "Finding not found: " + findingKey)));
    }

    /** PATCH /reasoning/findings/{findingKey} */
    @PatchMapping("/findings/{findingKey}")
    public ResponseEntity<Map<String, String>> updateFinding(
            @PathVariable String findingKey,
            @RequestBody Map<String, Object> body) {
        String status = (String) body.get("status");
        if (status == null || status.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        String resolvedAtStr = (String) body.get("resolved_at");
        Instant resolvedAt = resolvedAtStr != null ? Instant.parse(resolvedAtStr) : null;
        if ("RESOLVED".equalsIgnoreCase(status) && resolvedAt == null) {
            resolvedAt = Instant.now();
        }
        reasoningRepository.updateFindingStatus(findingKey, status, resolvedAt);
        return ResponseEntity.ok(Map.of("finding_key", findingKey, "status", status));
    }
}
