package com.sei.nexus.semantic;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST surface for /TeachZevra (Part B — Explicit Teaching). Same controller-per-domain,
 * tenant-implicit-via-TenantContext convention as {@link SemanticController}/{@code
 * ConnectionController} — no request carries a tenant id; the request's own auth-filter-set
 * {@code TenantContext} scopes every repository call this controller triggers.
 *
 * <p>Base path: /teaching
 */
@RestController
@RequestMapping("/teaching")
public class TeachingController {

    private final TeachingService teachingService;

    public TeachingController(TeachingService teachingService) {
        this.teachingService = teachingService;
    }

    /** GET /teaching/concepts — tenant-scoped canonical concept catalog for the form's Concept
     *  dropdown. Identical source to SemanticController's /semantic/concepts. */
    @GetMapping("/concepts")
    public ResponseEntity<List<Map<String, String>>> listConcepts() {
        return ResponseEntity.ok(teachingService.listConceptOptions());
    }

    /** GET /teaching/connections — the current tenant's authorized connections, for the form's
     *  Connection dropdown (shown only when scope = Specific connection). */
    @GetMapping("/connections")
    public ResponseEntity<List<Map<String, String>>> listConnections() {
        return ResponseEntity.ok(teachingService.listConnectionOptions());
    }

    /** POST /teaching/proposal — form fields → Teaching LLM → structured Learning Proposal.
     *  NOT persisted; the frontend shows this as a Preview before Save/Teach Zevra. */
    @PostMapping("/proposal")
    public ResponseEntity<TeachingProposal> propose(@RequestBody TeachingProposalRequest request) {
        return ResponseEntity.ok(teachingService.buildProposal(request));
    }

    /** POST /teaching/confirm — user clicked "Save / Teach Zevra" on the Preview screen.
     *  Persists the (re-validated) proposal into nexus_learned_mapping. */
    @PostMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@RequestBody TeachingProposal proposal) {
        LearnedMapping saved = teachingService.confirmTeaching(proposal);
        return ResponseEntity.ok(Map.of(
                "mappingKey", saved.mappingKey(),
                "businessTerm", saved.businessTerm(),
                "conceptKey", saved.conceptKey() != null ? saved.conceptKey() : "",
                "source", saved.source()));
    }
}
