package com.sei.nexus.semantic;

/**
 * The Teaching LLM's structured output, shown to the user as a Preview before anything is
 * persisted. {@code conceptKey} here is ALWAYS validated by {@link TeachingService} to equal the
 * user-submitted/allowed concept key before this proposal is ever returned to the frontend — a
 * mismatch is rejected (thrown), never silently substituted. Carries {@code scope}/{@code
 * connectionKey} straight through from the request (Java-owned, never re-derived by the LLM).
 */
public record TeachingProposal(
        String businessTerm,
        String definition,
        String sqlPattern,     // nullable — teaching may have no SQL at all
        String conceptKey,
        TeachingScope scope,
        String connectionKey   // echoed from the request; null unless scope == CONNECTION
) {}
