package com.sei.nexus.common;

/**
 * pgvector text literal for a float array — {@code [0.1,0.2,0.3]} — to be bound as a parameter and
 * cast with {@code ?::vector}. Shared by every repository that stores or queries an embedding so
 * the format exists in exactly one place.
 */
public final class PgVector {

    private PgVector() {}

    public static String toLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}
