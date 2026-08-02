package com.sei.nexus.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for the NexusRun duplicate-key fix: every generated run key must be
 * globally unique so consecutive requests, and repeated conversations, never reuse an
 * existing {@code run_key} (which would violate {@code nexus_run_pkey}).
 */
class KeysRunKeyUniquenessTest {

    @Test
    void consecutiveRunKeysAreAlwaysDistinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(seen.add(Keys.runKey()), "Keys.runKey() produced a duplicate");
        }
        assertEquals(10_000, seen.size(), "all generated run keys are unique");
    }

    @Test
    void runKeyHasStableRunPrefix() {
        assertTrue(Keys.runKey().startsWith("run-"), "run keys carry the 'run-' prefix");
        assertNotEquals(Keys.runKey(), Keys.runKey(), "two calls never return the same key");
    }
}
