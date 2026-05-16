package com.sei.nexus.auth;

import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Stateless authentication filter for every inbound request.
 *
 * <p>Authentication flow (per request):
 * <ol>
 *   <li>Extract the raw token from the {@code X-Nexus-Token} header.</li>
 *   <li>Hash the token and look up the hash in {@code public.nexus_session_index}.
 *       Because {@link TenantContext} has not been set yet, the
 *       {@link com.sei.nexus.tenant.TenantAwareDataSource} routes this query to
 *       the {@code public} schema — exactly where the session index lives.</li>
 *   <li>On a hit, extract the {@code tenant_schema} and {@code user_email}.</li>
 *   <li>Call {@link TenantContext#set(String)} with the tenant schema so that
 *       all subsequent JDBC calls in this request thread target the correct
 *       tenant schema.</li>
 *   <li>Look up the {@link UserAccount} in {@code {tenant_schema}.nexus_user_account}.</li>
 *   <li>Set the Spring Security {@link org.springframework.security.core.Authentication}
 *       so controllers can read the current user via {@code SecurityContextHolder}.</li>
 *   <li>In the {@code finally} block, unconditionally call {@link TenantContext#clear()}
 *       to prevent context leaks across pooled request threads.</li>
 * </ol>
 *
 * <p>Unauthenticated requests (e.g., {@code /auth/login}, {@code /auth/signup}) pass
 * through with no authentication set and no tenant context — which is correct, because
 * the login handler itself resolves the tenant from the request body.
 */
public class NexusAuthFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-Nexus-Token";

    private final AuthRepository   authRepository;
    private final TenantRepository tenantRepository;

    public NexusAuthFilter(AuthRepository authRepository,
                            TenantRepository tenantRepository) {
        this.authRepository   = authRepository;
        this.tenantRepository = tenantRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         filterChain)
            throws ServletException, IOException {

        try {
            String rawToken = request.getHeader(TOKEN_HEADER);
            if (rawToken != null && !rawToken.isBlank()) {
                resolveAuthentication(rawToken.trim());
            }
            filterChain.doFilter(request, response);
        } finally {
            // Always clear — prevents tenant context leaking across pooled threads
            TenantContext.clear();
        }
    }

    private void resolveAuthentication(String rawToken) {
        String tokenHash = AuthService.sha256Hex(rawToken);

        // Step 1: look up session index in public schema
        // (TenantContext is empty → TenantAwareDataSource uses public)
        tenantRepository.findSessionIndex(tokenHash).ifPresent(sessionIndex -> {

            // Step 2: route all subsequent queries to the tenant schema
            TenantContext.set(sessionIndex.tenantSchema());

            // Step 3: load user account from tenant schema
            authRepository.findByEmail(sessionIndex.userEmail()).ifPresent(account -> {
                if (!"ACTIVE".equals(account.status())) return;

                List<SimpleGrantedAuthority> authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + account.role()));

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(account, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        });
    }
}
