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
     */
    public static String uniqueKey(String base) {
        String k = key(base);
        String suffix = Long.toHexString(System.currentTimeMillis()).substring(4)
                + Integer.toHexString((int) (Math.random() * 0xFFFF));
        return k + "-" + suffix.substring(0, Math.min(8, suffix.length()));
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
