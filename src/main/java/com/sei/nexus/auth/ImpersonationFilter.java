package com.sei.nexus.auth;

import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Runs after SupabaseAuthFilter and NexusAuthFilter. If the request carries an
 * {@code X-Nexus-Impersonate} header AND the authenticated caller is a platform
 * admin (role=ADMIN, schema=public), overrides {@link TenantContext} with the
 * impersonation session's target schema.
 *
 * <p>Security invariants:
 * <ul>
 *   <li>Only callers whose TenantContext is already {@code public} and whose role
 *       is {@code ADMIN} can activate impersonation — enforced here, not just at
 *       the token-issuance endpoint.</li>
 *   <li>The impersonation token is validated server-side against
 *       {@code public.nexus_session_index}; a forged or expired token is silently
 *       ignored.</li>
 *   <li>Impersonation is per-request (thread-local); it does not persist across
 *       requests.</li>
 * </ul>
 */
public class ImpersonationFilter extends OncePerRequestFilter {

    public static final String IMPERSONATE_HEADER = "X-Nexus-Impersonate";

    private static final Logger log = LoggerFactory.getLogger(ImpersonationFilter.class);

    private final TenantRepository tenantRepository;

    public ImpersonationFilter(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * Platform-admin endpoints always run in the caller's real (public) context.
     * Overriding TenantContext there would make {@code requireAdmin()} reject the
     * platform admin with 403 while impersonating — locking them out of listing
     * tenants, switching to another tenant, or ending the impersonation session.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getServletPath().startsWith("/admin/");
    }

    /**
     * Re-authenticate/re-derive TenantContext on Tomcat's async re-dispatch too (e.g. the SSE
     * progress-streaming endpoint, {@code ReasoningStreamController}) — see
     * {@code NexusAuthFilter}'s identical override for the full explanation.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         chain)
            throws ServletException, IOException {

        String rawToken = request.getHeader(IMPERSONATE_HEADER);
        if (rawToken != null && !rawToken.isBlank()) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && auth.getPrincipal() instanceof UserAccount caller
                    && "ADMIN".equals(caller.role())
                    && "public".equals(TenantContext.getSchema())) {
                try {
                    String hash = sha256Hex(rawToken.trim());
                    tenantRepository.findSessionIndex(hash).ifPresent(session -> {
                        TenantContext.set(session.tenantSchema());
                        log.debug("Impersonation active: {} → schema={}",
                                caller.email(), session.tenantSchema());
                    });
                } catch (Exception e) {
                    log.warn("Impersonation token lookup failed: {}", e.getMessage());
                }
            }
        }

        chain.doFilter(request, response);
    }

    /** Public so {@code TenantController} (different package) can reuse it. */
    public static String sha256Hex(String input) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
