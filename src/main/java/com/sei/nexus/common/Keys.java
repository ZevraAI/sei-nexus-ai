package com.sei.nexus.common;

import java.util.UUID;

public final class Keys {

    private Keys() {}

    /**
     * Converts a value to a lowercase, URL-safe key by replacing non-alphanumeric
     * characters with hyphens and trimming leading/trailing hyphens.
     */
    public static String key(String value) {
        return value.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    /**
     * Generates a unique key from a base string by appending an 8-character random hex suffix.
     *
     * <p>Sourced from {@link UUID#randomUUID()} (same entropy source as {@link #runKey()}/
     * {@link #conversationKey()}) — not {@code System.currentTimeMillis()} + {@code Math.random()}
     * as this previously was, which collided in practice under rapid same-millisecond calls
     * (observed directly in a test that fires several {@code uniqueKey(...)} calls back to back).
     */
    public static String uniqueKey(String base) {
        String k = key(base);
        return k + "-" + uniqueSuffix().substring(0, 8);
    }

    /**
     * Generates a unique run key.
     */
    public static String runKey() {
        return "run-" + uniqueSuffix();
    }

    /**
     * Generates a unique conversation key.
     */
    public static String conversationKey() {
        return "conversation-" + uniqueSuffix();
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
