package com.sei.nexus.tenant;

/**
 * Thread-local holder for the current request's tenant schema name.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link com.sei.nexus.auth.NexusAuthFilter} resolves the schema from the
 *       session index and calls {@link #set(String)}.</li>
 *   <li>Every subsequent JDBC call in the same thread acquires a connection
 *       from {@link TenantAwareDataSource}, which reads this value and executes
 *       {@code SET search_path = <schema>, public}.</li>
 *   <li>{@link com.sei.nexus.auth.NexusAuthFilter} calls {@link #clear()} in
 *       its {@code finally} block to prevent context leaks across requests.</li>
 * </ol>
 *
 * <p>When the context is not set (unauthenticated requests such as login),
 * {@link #getSchema()} returns {@code "public"} so the shared registry tables
 * ({@code nexus_tenant}, {@code nexus_session_index}) are always accessible.
 */
public final class TenantContext {

    private static final ThreadLocal<String> SCHEMA = new ThreadLocal<>();

    /** Default schema used when no tenant context has been established. */
    public static final String PUBLIC_SCHEMA = "public";

    private TenantContext() {}

    public static void set(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            throw new IllegalArgumentException("Tenant schema name must not be blank");
        }
        SCHEMA.set(schemaName);
    }

    /**
     * Returns the current tenant schema, or {@code "public"} if no tenant has
     * been resolved for this thread (e.g., during unauthenticated requests or
     * Flyway migrations that run at application startup).
     */
    public static String getSchema() {
        String schema = SCHEMA.get();
        return (schema != null && !schema.isBlank()) ? schema : PUBLIC_SCHEMA;
    }

    public static boolean isSet() {
        return SCHEMA.get() != null;
    }

    /**
     * Returns the current tenant schema, or throws when no tenant context has been established.
     *
     * <p>Unlike {@link #getSchema()}, this never falls back to {@code public}. Tenant-scoped data
     * access must call this so a missing context fails <b>closed</b> (a clear error) rather than
     * silently reading the shared {@code public} schema. Registry/login/migration paths that
     * legitimately run without a tenant keep using {@link #getSchema()}.
     */
    public static String getSchemaStrict() {
        String schema = SCHEMA.get();
        if (schema == null || schema.isBlank()) {
            throw new IllegalStateException(
                    "No tenant context established for this request; refusing to fall back to 'public'");
        }
        return schema;
    }

    /** Must be called in a {@code finally} block after every request to prevent leaks. */
    public static void clear() {
        SCHEMA.remove();
    }
}
