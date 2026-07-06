package com.sei.nexus.auth;

import com.sei.nexus.common.NexusException;
import com.sei.nexus.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth/users")
public class UserManagementController {

    private final UserManagementService userManagementService;

    public UserManagementController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    /** GET /auth/users — list all users in the current tenant. */
    @GetMapping
    public ResponseEntity<?> listUsers() {
        List<Map<String, Object>> users =
                userManagementService.listUsers(TenantContext.getSchema());
        return ResponseEntity.ok(users);
    }

    /** POST /auth/users/invite — ADMIN only. */
    @PostMapping("/invite")
    public ResponseEntity<?> inviteUser(@RequestBody Map<String, String> body) {
        requireAdmin();
        // Platform admins (tenant_schema=public) may specify the target tenant in the body.
        // Regular tenant admins always invite into their own tenant.
        String callerSchema = TenantContext.getSchema();
        String targetSchema = body.get("tenant_schema");
        String schema = (targetSchema != null && !targetSchema.isBlank()
                && "public".equals(callerSchema))
                ? targetSchema
                : callerSchema;

        userManagementService.inviteUser(
                body.get("email"),
                body.getOrDefault("role", "ANALYST"),
                body.get("display_name"),
                schema,
                currentUserEmail());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("invited", body.get("email")));
    }

    /** PATCH /auth/users/{email} — update role/status. ADMIN only. */
    @PatchMapping("/{email}")
    public ResponseEntity<?> updateUser(@PathVariable String email,
                                         @RequestBody Map<String, String> body) {
        requireAdmin();
        userManagementService.updateUser(email, body.get("role"), body.get("status"));
        return ResponseEntity.ok(Map.of("updated", email));
    }

    /** DELETE /auth/users/{email} — soft deactivate. ADMIN only. */
    @DeleteMapping("/{email}")
    public ResponseEntity<?> deactivateUser(@PathVariable String email) {
        requireAdmin();
        userManagementService.deactivateUser(email);
        return ResponseEntity.noContent().build();
    }

    /** POST /auth/users/{email}/resend-invite — resend Supabase invite. ADMIN only. */
    @PostMapping("/{email}/resend-invite")
    public ResponseEntity<?> resendInvite(@PathVariable String email) {
        requireAdmin();
        userManagementService.resendInvite(email);
        return ResponseEntity.ok(Map.of("resent", email));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void requireAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isAdmin)
            throw new NexusException(HttpStatus.FORBIDDEN, "Admin role required");
    }

    private String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserAccount ua)
            return ua.email();
        return null;
    }
}
