package com.sei.nexus.semantic;

import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the TenantContext-loss fix in {@link SemanticLearningService}'s
 * {@code @Async} methods. Pure unit tests — no Spring context, no database — exercising the
 * actual production {@link TenantContextPropagatingTaskDecorator} directly, plus a real {@link
 * ThreadPoolTaskExecutor} (Spring's own class, not a fake) for the concurrency test, since that
 * is exactly the mechanism production wiring uses.
 */
class TenantContextPropagatingTaskDecoratorTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private final TenantContextPropagatingTaskDecorator decorator = new TenantContextPropagatingTaskDecorator();

    // ── Test 1 / 2 — tenant A / tenant B propagation ────────────────────────────────────────────

    @Test
    void propagatesTenantAIntoTheDecoratedTask() {
        TenantContext.set("tenant_a");
        AtomicReferenceHolder seen = new AtomicReferenceHolder();

        Runnable decorated = decorator.decorate(() -> seen.value = TenantContext.getSchema());
        decorated.run(); // simulates the worker thread executing what was captured on this thread

        assertEquals("tenant_a", seen.value, "the decorated task must see the schema captured from the submitting thread");
    }

    @Test
    void propagatesTenantBIntoTheDecoratedTask() {
        TenantContext.set("tenant_b");
        AtomicReferenceHolder seen = new AtomicReferenceHolder();

        Runnable decorated = decorator.decorate(() -> seen.value = TenantContext.getSchema());
        decorated.run();

        assertEquals("tenant_b", seen.value);
    }

    // ── Test 4 — missing context fails closed, never runs the task ─────────────────────────────

    @Test
    void missingTenantContextNeverExecutesTheTaskAndNeverFallsBackToPublic() {
        TenantContext.clear(); // no tenant context established at all
        AtomicBoolean taskRan = new AtomicBoolean(false);

        Runnable decorated = decorator.decorate(() -> taskRan.set(true));
        decorated.run(); // even if "run", the decorator must have swapped in a no-op

        assertFalse(taskRan.get(), "the wrapped learning task must never execute without a real tenant context");
    }

    // ── Test 5 — worker thread's TenantContext is cleared after the task completes ──────────────

    @Test
    void tenantContextIsClearedAfterTheDecoratedTaskCompletes() {
        TenantContext.set("tenant_a");
        Runnable decorated = decorator.decorate(() -> { });
        decorated.run();

        // The decorator's own set/run/clear happens entirely on whatever thread .run() is called
        // on — here, this test's own thread — so this directly proves the finally-clear behavior.
        assertFalse(TenantContext.isSet(), "TenantContext must be cleared after the decorated task runs, "
                + "so a reused pool thread never leaks tenant state into its next task");
    }

    @Test
    void tenantContextIsClearedEvenWhenTheWrappedTaskThrows() {
        TenantContext.set("tenant_a");
        Runnable decorated = decorator.decorate(() -> { throw new RuntimeException("simulated failure"); });

        assertThrows(RuntimeException.class, decorated::run);
        assertFalse(TenantContext.isSet(), "the finally-clear must run even when the wrapped task throws");
    }

    // ── Test 3 — concurrent tenant isolation, using a real Spring ThreadPoolTaskExecutor ────────

    @Test
    void concurrentTenantATenantBSubmissionsNeverCrossContaminate() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("decorator-test-");
        executor.setTaskDecorator(decorator);
        executor.initialize();

        try {
            int perTenant = 25;
            CountDownLatch latch = new CountDownLatch(perTenant * 2);
            ConcurrentLinkedQueue<String> mismatches = new ConcurrentLinkedQueue<>();
            Map<String, AtomicInteger> seenCounts = new ConcurrentHashMap<>();
            seenCounts.put("tenant_a", new AtomicInteger());
            seenCounts.put("tenant_b", new AtomicInteger());

            for (int i = 0; i < perTenant; i++) {
                // Each submission sets its own expected tenant on THIS (submitting) thread
                // immediately before execute() — exactly how a real request thread would.
                TenantContext.set("tenant_a");
                String expectedA = "tenant_a";
                executor.execute(() -> {
                    String actual = TenantContext.getSchema();
                    if (!expectedA.equals(actual)) mismatches.add("expected tenant_a, saw " + actual);
                    else seenCounts.get("tenant_a").incrementAndGet();
                    latch.countDown();
                });
                TenantContext.clear();

                TenantContext.set("tenant_b");
                String expectedB = "tenant_b";
                executor.execute(() -> {
                    String actual = TenantContext.getSchema();
                    if (!expectedB.equals(actual)) mismatches.add("expected tenant_b, saw " + actual);
                    else seenCounts.get("tenant_b").incrementAndGet();
                    latch.countDown();
                });
                TenantContext.clear();
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS), "all submitted tasks must complete");
            assertEquals(List.of(), List.copyOf(mismatches), "no task may ever see the other tenant's schema");
            assertEquals(perTenant, seenCounts.get("tenant_a").get());
            assertEquals(perTenant, seenCounts.get("tenant_b").get());
        } finally {
            executor.shutdown();
        }
    }

    private static class AtomicReferenceHolder {
        volatile String value;
    }
}
