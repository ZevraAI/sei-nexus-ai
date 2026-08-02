package com.sei.nexus.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production hardening — tenant-scoped data access must fail <b>closed</b>. A missing tenant
 * context must not silently fall back to the shared {@code public} schema for data operations.
 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void getSchemaStrictThrowsWhenNoContextIsSet() {
        TenantContext.clear();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                TenantContext::getSchemaStrict,
                "tenant-scoped access must fail closed when no context is established");
        assertFalse(ex.getMessage().toLowerCase().contains("returning"),
                "message should state the refusal, not a fallback");
        assertTrue(ex.getMessage().contains("public"),
                "message should make the refused public fallback explicit");
    }

    @Test
    void getSchemaStrictReturnsTheSchemaWhenSet() {
        TenantContext.set("tenant_maryland_corporations");
        assertEquals("tenant_maryland_corporations", TenantContext.getSchemaStrict());
    }

    @Test
    void getSchemaStillFallsBackToPublicForRegistryAndLoginPaths() {
        TenantContext.clear();
        assertEquals(TenantContext.PUBLIC_SCHEMA, TenantContext.getSchema(),
                "the lenient accessor keeps the public fallback for registry/login/migration");
    }
}
