package com.sei.nexus.auth;

import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String TOKEN_HEADER = "X-Nexus-Token";

    private final AuthService    authService;
    private final AuthRepository authRepository;

    public AuthController(AuthService authService, AuthRepository authRepository) {
        this.authService    = authService;
        this.authRepository = authRepository;
    }

    /**
     * POST /auth/signup
     * Creates a new user account within the specified tenant.
     *
     * <p>Body fields:
     * <pre>
     * {
     *   "email":        "user@acme.com",
     *   "password":     "SecurePass1!",
     *   "display_name": "Alice Smith",     // optional — defaults to email
     *   "tenant_slug":  "acme-corp"        // optional — defaults to "default"
     * }
     * </pre>
     */
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody Map<String, String> body) {
        String email       = body.get("email");
        String displayName = body.getOrDefault("display_name", email);
        String password    = body.get("password");
        String tenantSlug  = body.get("tenant_slug");   // null → AuthService defaults to "default"

        if (displayName == null || displayName.isBlank()) displayName = email;

        Map<String, Object> result = authService.signup(email, displayName, password, tenantSlug);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * POST /auth/login
     * Authenticates a user and returns a session token.
     *
     * <p>Body fields:
     * <pre>
     * {
     *   "email":       "user@acme.com",
     *   "password":    "SecurePass1!",
     *   "tenant_slug": "acme-corp"        // optional — defaults to "default"
     * }
     * </pre>
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String email      = body.get("email");
        String password   = body.get("password");
        String tenantSlug = body.get("tenant_slug");

        Map<String, Object> result = authService.login(email, password, tenantSlug);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /auth/logout
     * Invalidates the current session token in both the tenant session table
     * and the shared session index.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = TOKEN_HEADER, required = false) String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String schema = (auth != null && auth.getPrincipal() instanceof UserAccount ua)
                    ? resolveSchema(ua) : com.sei.nexus.tenant.TenantContext.getSchema();
            authService.logout(rawToken.trim(), schema);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /auth/me
     * Returns the authenticated user's profile.
     */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof UserAccount account)) {
            throw new NexusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("email",        account.email());
        user.put("display_name", account.displayName());
        user.put("role",         account.role());
        user.put("status",       account.status());
        user.put("created_at",   account.createdAt());
        user.put("tenant_schema", com.sei.nexus.tenant.TenantContext.getSchema());
        return ResponseEntity.ok(user);
    }

    /**
     * GET /auth/policy
     * Returns role definitions and counts — useful for the login page and onboarding.
     */
    @GetMapping("/policy")
    public ResponseEntity<?> policy() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserAccount currentUser = (auth != null && auth.getPrincipal() instanceof UserAccount ua)
                ? ua : null;

        Map<String, Object> roles = new LinkedHashMap<>();
        roles.put("ADMIN",        "Full system access: manage users, domains, agents, and all data");
        roles.put("ANALYST",      "Read and query access: run agents, view results, submit knowledge gaps");
        roles.put("DOMAIN_OWNER", "Domain-level management: manage domain agents, connections, and knowledge");

        int totalUsers = authRepository.countUsers();

        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("roles",             roles);
        policy.put("first_user_is_admin", totalUsers <= 1);
        policy.put("total_users",       totalUsers);
        if (currentUser != null) {
            policy.put("current_role",  currentUser.role());
            policy.put("current_email", currentUser.email());
        }
        return ResponseEntity.ok(policy);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String resolveSchema(UserAccount ua) {
        return com.sei.nexus.tenant.TenantContext.getSchema();
    }
}
