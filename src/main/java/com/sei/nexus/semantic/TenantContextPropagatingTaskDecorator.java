package com.sei.nexus.semantic;

import com.sei.nexus.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskDecorator;

/**
 * Propagates {@link TenantContext} (a {@code ThreadLocal}) from the submitting request thread
 * into the {@code @Async} worker thread it will actually run on.
 *
 * <p>Root cause this fixes: {@link SemanticLearningService}'s {@code @Async} methods
 * ({@code learnFromRun}, {@code captureLiteralBinding}) execute on a Spring-managed worker
 * thread that does not inherit the request thread's {@code ThreadLocal} state. {@code
 * TenantAwareDataSource} resolves the schema to route every JDBC connection to from {@code
 * TenantContext.getSchema()}, which silently defaults to {@code "public"} when unset — and
 * {@code nexus_learned_mapping}/{@code nexus_correction} exist in {@code public} too, so the
 * write never fails, it just lands in the wrong tenant's data (confirmed against real historical
 * rows — see {@code docs/} / this investigation's own RCA chain). This decorator closes that gap
 * for exactly the one executor it is attached to ({@code semanticLearningExecutor}) — it is not
 * registered as a global {@code TaskDecorator} and has no effect on any other {@code @Async}
 * method in the application.
 *
 * <p><b>Capture happens on the submitting thread.</b> Spring's {@link
 * org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor#execute} calls {@link
 * #decorate} synchronously, on the calling thread, before handing the returned {@link Runnable}
 * to the worker thread — so {@link TenantContext#getSchemaStrict()} here reads the submitting
 * request's own, correctly-established schema, never the worker thread's (empty) state.
 *
 * <p><b>Fail closed, never {@code public}.</b> If the submitting thread has no tenant context at
 * all (should not happen for a real chat request, but must never be silently miscategorized as
 * "public"), {@link #decorate} returns a no-op {@link Runnable} — the learning task is simply
 * never scheduled. This is intentionally silent to the user (learning is fire-and-forget by
 * design), logged once at {@code warn} for operability, and never throws back into the caller.
 *
 * <p>The worker thread's {@code TenantContext} is always cleared in a {@code finally} block
 * after the wrapped task completes, so a reused pool thread never carries stale tenant state
 * into its next task.
 */
public class TenantContextPropagatingTaskDecorator implements TaskDecorator {

    private static final Logger log = LoggerFactory.getLogger(TenantContextPropagatingTaskDecorator.class);

    @Override
    public Runnable decorate(Runnable task) {
        final String schema;
        try {
            // Captured NOW, on the submitting thread — never on the worker thread.
            schema = TenantContext.getSchemaStrict();
        } catch (IllegalStateException e) {
            log.warn("Semantic learning task not scheduled — no tenant context on the requesting "
                    + "thread; refusing to run it against the shared 'public' schema: {}", e.getMessage());
            return () -> { }; // fail closed: never execute, never fall back to public
        }
        return () -> {
            TenantContext.set(schema);
            try {
                log.debug("Semantic learning task executing under tenant schema '{}'", schema);
                task.run();
            } finally {
                // Always clear, even on failure, so a reused pool thread never leaks tenant state
                // into whatever task it picks up next.
                TenantContext.clear();
            }
        };
    }
}
