package com.sei.nexus.instruction;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Review of standing instructions. Submission happens in chat (/knowledge); review is ADMIN or DOMAIN_OWNER only. */
@RestController
@RequestMapping("/instructions")
public class InstructionController {

    private final InstructionService service;

    public InstructionController(InstructionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false, defaultValue = "PENDING") String status) {
        requireReviewer();
        return ResponseEntity.ok(service.list(status));
    }

    /** Body: {"text": optional edited wording, "alwaysApply": optional boolean}. */
    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable String id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        UserAccount reviewer = requireReviewer();
        String text = body != null && body.get("text") instanceof String s ? s : null;
        boolean always = body != null && Boolean.TRUE.equals(body.get("alwaysApply"));
        return ResponseEntity.ok(service.approve(id, text, always, reviewer.email()));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<?> archive(@PathVariable String id) {
        UserAccount reviewer = requireReviewer();
        service.archive(id, reviewer.email());
        return ResponseEntity.ok(Map.of("message", "Instruction archived", "instruction_id", id));
    }

    private UserAccount requireReviewer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserAccount account)) {
            throw new NexusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!"ADMIN".equals(account.role()) && !"DOMAIN_OWNER".equals(account.role())) {
            throw new NexusException(HttpStatus.FORBIDDEN, "Only ADMIN or DOMAIN_OWNER may review instructions");
        }
        return account;
    }
}
