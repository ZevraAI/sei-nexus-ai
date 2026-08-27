package com.sei.nexus.pack;

/** Global Concept Resolution (Phase 1, read-only) — the four possible outcomes for one
 *  tenant Business Object evaluated against its connection's assigned Pack. */
public enum ResolutionOutcome {
    /** Exactly one candidate reached STRONG, with no other candidate at MEDIUM or above. */
    CLEAR,
    /** Two or more plausible candidates (MEDIUM or above), no two of them both STRONG. */
    AMBIGUOUS,
    /** Two or more candidates each independently reached STRONG — genuine disagreement, never auto-resolved. */
    CONFLICTING,
    /** No candidate reached even a confidently-reportable level — including the case of exactly
     *  one candidate that only reached MEDIUM/WEAK with nothing to compare it against; a lone
     *  weak signal is not the same as a clear one. */
    UNRESOLVED
}
