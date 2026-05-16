package com.sei.nexus.knowledge;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/knowledge-gaps")
public class KnowledgeGapController {

    private final KnowledgeGapRepository gapRepository;

    public KnowledgeGapController(KnowledgeGapRepository gapRepository) {
        this.gapRepository = gapRepository;
    }

    @GetMapping
    public ResponseEntity<?> listGaps(
            @RequestParam(required = false, defaultValue = "OPEN") String status,
            @RequestParam(name = "domain_key", required = false) String domainKey) {

        List<KnowledgeGap> gaps;
        if (domainKey != null && !domainKey.isBlank()) {
            gaps = gapRepository.findByDomainAndStatus(domainKey, status);
        } else {
            gaps = gapRepository.findByStatus(status);
        }
        return ResponseEntity.ok(gaps);
    }

    @PostMapping("/{gapKey}/resolve")
    public ResponseEntity<?> resolveGap(
            @PathVariable String gapKey,
            @RequestBody Map<String, String> body) {

        UserAccount currentUser = requireAuthenticatedUser();
        requireAdminOrDomainOwner(currentUser);

        String resolutionNote = body.get("resolution_note");
        if (resolutionNote == null || resolutionNote.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "resolution_note is required");
        }

        int updated = gapRepository.updateStatus(gapKey, "RESOLVED", currentUser.email(), resolutionNote);
        if (updated == 0) {
            throw new NexusException(HttpStatus.NOT_FOUND, "Knowledge gap not found: " + gapKey);
        }

        return ResponseEntity.ok(Map.of(
                "message", "Knowledge gap resolved",
                "gap_key", gapKey,
                "resolved_by", currentUser.email()
        ));
    }

    @PostMapping("/{gapKey}/dismiss")
    public ResponseEntity<?> dismissGap(
            @PathVariable String gapKey,
            @RequestBody(required = false) Map<String, String> body) {

        UserAccount currentUser = requireAuthenticatedUser();
        requireAdminOrDomainOwner(currentUser);

        String note = body != null ? body.getOrDefault("note", "Dismissed") : "Dismissed";

        int updated = gapRepository.updateStatus(gapKey, "DISMISSED", currentUser.email(), note);
        if (updated == 0) {
            throw new NexusException(HttpStatus.NOT_FOUND, "Knowledge gap not found: " + gapKey);
        }

        return ResponseEntity.ok(Map.of(
                "message", "Knowledge gap dismissed",
                "gap_key", gapKey,
                "dismissed_by", currentUser.email()
        ));
    }

    @PostMapping("/{gapKey}/resolve-source")
    public ResponseEntity<?> resolveSourceRequest(
            @PathVariable String gapKey,
            @RequestBody(required = false) Map<String, String> body) {

        UserAccount currentUser = requireAuthenticatedUser();
        requireAdminOrDomainOwner(currentUser);

        String note = body != null
                ? body.getOrDefault("note", "Source request resolved")
                : "Source request resolved";

        int updated = gapRepository.updateStatus(gapKey, "SOURCE_RESOLVED", currentUser.email(), note);
        if (updated == 0) {
            throw new NexusException(HttpStatus.NOT_FOUND, "Knowledge gap not found: " + gapKey);
        }

        return ResponseEntity.ok(Map.of(
                "message", "Source request marked as resolved",
                "gap_key", gapKey,
                "resolved_by", currentUser.email()
        ));
    }

    private UserAccount requireAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserAccount account)) {
            throw new NexusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return account;
    }

    private void requireAdminOrDomainOwner(UserAccount user) {
        String role = user.role();
        if (!"ADMIN".equals(role) && !"DOMAIN_OWNER".equals(role)) {
            throw new NexusException(HttpStatus.FORBIDDEN,
                    "Only ADMIN or DOMAIN_OWNER may perform this action");
        }
    }
}
