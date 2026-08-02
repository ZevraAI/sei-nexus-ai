package com.sei.nexus.strategy;

import java.util.Locale;

/**
 * The canonical understanding of a request's <em>shape</em>, computed once at the front door and
 * reused downstream (rather than re-derived by the conversational pipeline). This is the axis the
 * decision engine previously emitted informally as a string; it is now a first-class type carried
 * on {@link RequestAnalysis}.
 */
public enum IntentType {
    OPERATIONAL_INVESTIGATION,
    ANALYTICAL,
    INFORMATIONAL,
    FOLLOW_UP,
    /** Used when the intent could not be determined or parsed — a neutral, safe default. */
    UNKNOWN;

    /** Parses an intent label from an LLM/JSON value; unknown or blank resolves to {@link #UNKNOWN}. */
    public static IntentType fromLabel(String raw) {
        if (raw == null || raw.isBlank()) return UNKNOWN;
        try {
            return IntentType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
