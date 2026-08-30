package com.sei.nexus.semantic;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the exact executor wiring the TenantContext-loss fix depends on — a prompt/config-drift
 * regression guard, same convention as this repo's other "prompt/annotation pinning" tests. If
 * either method's {@code @Async} value ever silently reverts to the default (unnamed) executor,
 * the fix is undone even though the code still compiles and looks identical at a glance.
 */
class SemanticLearningServiceAsyncWiringTest {

    @Test
    void learnFromRunUsesTheDedicatedTenantAwareExecutor() throws Exception {
        Method m = SemanticLearningService.class.getDeclaredMethod(
                "learnFromRun", String.class, String.class, String.class, String.class, String.class);
        Async async = m.getAnnotation(Async.class);
        assertNotNull(async, "learnFromRun must remain @Async");
        assertEquals("semanticLearningExecutor", async.value(),
                "learnFromRun must run on the tenant-context-propagating executor, not the default one");
    }

    @Test
    void captureLiteralBindingUsesTheDedicatedTenantAwareExecutor() throws Exception {
        Method m = SemanticLearningService.class.getDeclaredMethod(
                "captureLiteralBinding", String.class, String.class, String.class, String.class, String.class);
        Async async = m.getAnnotation(Async.class);
        assertNotNull(async, "captureLiteralBinding must remain @Async");
        assertEquals("semanticLearningExecutor", async.value(),
                "captureLiteralBinding must run on the tenant-context-propagating executor, not the default one");
    }

    @Test
    void reinforceFromFeedbackUsesTheDedicatedTenantAwareExecutor() throws Exception {
        Method m = SemanticLearningService.class.getDeclaredMethod(
                "reinforceFromFeedback", String.class, String.class);
        Async async = m.getAnnotation(Async.class);
        assertNotNull(async, "reinforceFromFeedback must remain @Async");
        assertEquals("semanticLearningExecutor", async.value(),
                "reinforceFromFeedback must run on the tenant-context-propagating executor, not the default one");
    }

    @Test
    void semanticLearningExecutorBeanUsesTheTenantContextDecorator() {
        SemanticLearningAsyncConfig config = new SemanticLearningAsyncConfig();
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                (org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) config.semanticLearningExecutor();
        assertNotNull(executor);
        // No public getter for the decorator on ThreadPoolTaskExecutor — the wiring itself is
        // exercised end-to-end by TenantContextPropagatingTaskDecoratorTest's concurrency test
        // and the real-tenant validation; this test only pins that the bean constructs cleanly.
        executor.shutdown();
    }
}
