package com.sei.nexus.pack;

/**
 * Global Concept Resolution (Phase 1, read-only) — how strongly one piece of evidence
 * supports one candidate concept. Deliberately categorical, not numeric: no scoring
 * weights exist anywhere in this codebase to extend (confirmed by investigation), and
 * inventing arbitrary numbers here would manufacture false precision.
 */
public enum EvidenceStrength {
    /** A deterministic signal corroborated by at least one independent deterministic signal. */
    STRONG,
    /** Exactly one deterministic signal fired, uncorroborated. */
    MEDIUM,
    /** Only a loose/free-text signal fired (group_label overlap, description keyword overlap). */
    WEAK,
    /** No usable signal at all. */
    NONE
}
