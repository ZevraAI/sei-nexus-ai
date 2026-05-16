package com.sei.nexus.auth;

import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles all authentication operations: signup, login, session creation.
 *
 * <p><strong>Tenant resolution:</strong> every authentication request must
 * identify the target tenant via a {@code tenantSlug}.  The service looks
 * up the tenant in {@code public.nexus_tenant}, validates it is ACTIVE, sets
 * {@link TenantContext} to the tenant's schema name, and then performs all
 * further user/session operations inside that tenant schema.
 *
 * <p>After a successful login or signup, the token hash is also written to
 * {@code public.nexus_session_index} so that the {@link NexusAuthFilter}
 * can resolve the tenant schema on subsequent requests without scanning all
 * tenant schemas.
 *
 * <p>The {@link TenantContext} set here is cleared by {@link NexusAuthFilter}'s
 * {@code finally} block once the request completes — callers must not clear it
 * themselves.
 */
@Service
public class AuthService {

    @Value("${nexus.security.session-expiry-hours:24}")
    private int sessionExpiryHours;

    /** Slug used when the client does not supply one — maps to the public schema. */
    private static final String DEFAULT_TENANT_SLUG = "default";

    private final AuthRepository   authRepository;
    private final TenantRepository tenantRepository;
    private final SecureRandom     secureRandom = new SecureRandom();

    public AuthService(AuthRepository authRepository,
                       TenantRepository tenantRepository) {
        this.authRepository   = authRepository;
        this.tenantRepository = tenantRepository;
    }

    // ── Signup ────────────────────────────────────────────────────────────────

    /**
     * Registers a new user in the specified tenant.
     * The first user registered in a tenant automatically receives the ADMIN role.
     *
     * @param email       user email (unique within the tenant schema)
     * @param displayName display name shown in the UI
     * @param password    plain-text password — min 8 chars — stored as bcrypt hash
     * @param tenantSlug  tenant identifier; defaults to {@value #DEFAULT_TENANT_SLUG}
     */
    public Map<String, Object> signup(String email, String displayName,
                                       String password, String tenantSlug) {
        if (email == null || email.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        if (password == null || password.length() < 8) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters");
        }

        String schema = resolveTenantSchema(tenantSlug);
        TenantContext.set(schema);

        authRepository.findByEmail(email.toLowerCase()).ifPresent(u -> {
            throw new NexusException(HttpStatus.CONFLICT, "Email already registered in this tenant");
        });

        boolean isFirstUser = authRepository.countUsers() == 0;
        String  role         = isFirstUser ? "ADMIN" : "ANALYST";
        String  passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(12));

        UserAccount account = new UserAccount(
                email.toLowerCase(), displayName, passwordHash, role, "ACTIVE",
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC));
        authRepository.create(account);

        String rawToken = createSession(account.email(), schema);
        return buildAuthResponse(rawToken, account, schema);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    /**
     * Authenticates a user against the specified tenant's user store.
     *
     * @param email      user email
     * @param password   plain-text password
     * @param tenantSlug tenant identifier; defaults to {@value #DEFAULT_TENANT_SLUG}
     */
    public Map<String, Object> login(String email, String password, String tenantSlug) {
        if (email == null || email.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        if (password == null || password.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Password is required");
        }

        String schema = resolveTenantSchema(tenantSlug);
        TenantContext.set(schema);

        UserAccount account = authRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new NexusException(HttpStatus.UNAUTHORIZED,
                        "Invalid email or password"));

        if (!"ACTIVE".equals(account.status())) {
            throw new NexusException(HttpStatus.FORBIDDEN, "Account is not active");
        }

        if (!BCrypt.checkpw(password, account.passwordHash())) {
            throw new NexusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        String rawToken = createSession(account.email(), schema);
        return buildAuthResponse(rawToken, account, schema);
    }

    // ── Session ───────────────────────────────────────────────────────────────

    /**
     * Creates a new session for the given user within the given tenant schema.
     * Writes the session to both:
     * <ul>
     *   <li>{@code {schema}.nexus_user_session} — the tenant's own session table</li>
     *   <li>{@code public.nexus_session_index}  — the shared lookup index used by
     *       {@link NexusAuthFilter} for routing</li>
     * </ul>
     *
     * @return the raw (unhashed) token to return to the client
     */
    public String createSession(String userEmail, String tenantSchema) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawToken = HexFormat.of().formatHex(randomBytes);

        String        tokenHash  = sha256Hex(rawToken);
        String        sessionKey = Keys.uniqueKey("session");
        OffsetDateTime expiresAt  = OffsetDateTime.now(ZoneOffset.UTC).plusHours(sessionExpiryHours);

        // Write to tenant schema's session table
        UserSession session = new UserSession(
                sessionKey, userEmail, tokenHash, expiresAt,
                OffsetDateTime.now(ZoneOffset.UTC));
        authRepository.createSession(session);

        // Write to shared session index for fast auth routing on future requests.
        // Temporarily clear tenant context so the index write lands in public schema.
        String savedSchema = TenantContext.getSchema();
        TenantContext.clear();
        try {
            tenantRepository.writeSessionIndex(
                    tokenHash, tenantSchema, userEmail,
                    Instant.ofEpochSecond(expiresAt.toEpochSecond()));
        } finally {
            TenantContext.set(savedSchema);
        }

        return rawToken;
    }

    /**
     * Invalidates a session by token hash — removes from both the tenant session
     * table and the shared session index.
     */
    public void logout(String rawToken, String tenantSchema) {
        String tokenHash = sha256Hex(rawToken);

        // Remove from tenant session table
        authRepository.deleteSession(tokenHash);

        // Remove from shared index (switch to public schema briefly)
        String savedSchema = TenantContext.getSchema();
        TenantContext.clear();
        try {
            tenantRepository.deleteSessionIndex(tokenHash);
        } finally {
            if (savedSchema != null) TenantContext.set(savedSchema);
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves a tenant slug to its schema name.
     * If slug is null/blank, falls back to {@value #DEFAULT_TENANT_SLUG}.
     */
    private String resolveTenantSchema(String tenantSlug) {
        String slug = (tenantSlug == null || tenantSlug.isBlank())
                ? DEFAULT_TENANT_SLUG : tenantSlug.trim().toLowerCase();

        return tenantRepository.findBySlug(slug)
                .filter(t -> "ACTIVE".equals(t.status()))
                .map(com.sei.nexus.tenant.Tenant::schemaName)
                .orElseThrow(() -> new NexusException(HttpStatus.UNAUTHORIZED,
                        "Tenant not found or inactive: " + slug));
    }

    private Map<String, Object> buildAuthResponse(String rawToken,
                                                    UserAccount account,
                                                    String tenantSchema) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", rawToken);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("email", account.email());
        user.put("display_name", account.displayName());
        user.put("role", account.role());
        user.put("status", account.status());
        user.put("tenant_schema", tenantSchema);
        response.put("user", user);
        return response;
    }
}
