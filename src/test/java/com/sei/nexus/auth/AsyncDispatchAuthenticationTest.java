package com.sei.nexus.auth;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the fix for a real production bug: the SSE progress-streaming endpoint
 * ({@code ReasoningStreamController}) resumes on Tomcat's ASYNC dispatch, which Spring
 * Security's filter chain re-runs. {@link org.springframework.web.filter.OncePerRequestFilter}
 * defaults {@code shouldNotFilterAsyncDispatch()} to {@code true} — skipping these auth filters
 * on that dispatch — and with {@code SessionCreationPolicy.STATELESS} (no session-backed
 * {@code SecurityContextRepository} to fall back on), the resumed thread has no authentication,
 * so Spring Security's {@code AuthorizationFilter} denies it (surfaced as
 * {@code AccessDeniedException}, "response already committed", once SSE bytes were already
 * streamed). Each auth filter must override this to {@code false} so it re-authenticates on
 * every dispatch, not just the original request.
 */
class AsyncDispatchAuthenticationTest {

    private boolean shouldNotFilterAsyncDispatch(Object filter) throws Exception {
        Method m = findDeclaredMethod(filter.getClass(), "shouldNotFilterAsyncDispatch");
        m.setAccessible(true);
        return (boolean) m.invoke(filter);
    }

    private Method findDeclaredMethod(Class<?> type, String name) throws NoSuchMethodException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    @Test
    void nexusAuthFilterReAuthenticatesOnAsyncDispatch() throws Exception {
        NexusAuthFilter filter = new NexusAuthFilter(null, null);
        assertFalse(shouldNotFilterAsyncDispatch(filter),
                "NexusAuthFilter must re-run on the SSE stream's async dispatch");
    }

    @Test
    void supabaseAuthFilterReAuthenticatesOnAsyncDispatch() throws Exception {
        SupabaseAuthFilter filter = new SupabaseAuthFilter(null, null,
                // valid base64url so the constructor's HS256 secret decode doesn't throw
                "c2VjcmV0", "https://example.supabase.co", new com.fasterxml.jackson.databind.ObjectMapper());
        assertFalse(shouldNotFilterAsyncDispatch(filter),
                "SupabaseAuthFilter (the primary auth path) must re-run on async dispatch");
    }

    @Test
    void impersonationFilterReAuthenticatesOnAsyncDispatch() throws Exception {
        ImpersonationFilter filter = new ImpersonationFilter(null);
        assertFalse(shouldNotFilterAsyncDispatch(filter),
                "ImpersonationFilter must re-run on async dispatch to keep TenantContext consistent");
    }
}
