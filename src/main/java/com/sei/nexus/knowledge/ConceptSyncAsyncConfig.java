package com.sei.nexus.knowledge;

import com.sei.nexus.semantic.TenantContextPropagatingTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * A dedicated, named thread pool for {@link ConceptKnowledgeSynchronizationService}'s {@code
 * @Async} trigger — isolated from Spring's default {@code @Async} executor for the exact same
 * reason {@code semanticLearningExecutor} exists (see {@code
 * com.sei.nexus.semantic.SemanticLearningAsyncConfig}'s own javadoc): a shared default executor
 * with no {@link TenantContextPropagatingTaskDecorator} silently loses {@code TenantContext}
 * across the async thread hand-off.
 *
 * <p>Reuses {@link TenantContextPropagatingTaskDecorator} directly rather than duplicating it —
 * that class is already a generic, tenant-agnostic mechanism (capture-on-submitting-thread,
 * fail-closed, clear-in-finally); nothing about it is specific to semantic learning. Deliberately
 * scoped to this one feature's executor bean only — not a change to the default {@code @Async}
 * executor, and not a global {@code TaskDecorator} registration.
 */
@Configuration
public class ConceptSyncAsyncConfig {

    @Bean(name = "conceptSyncExecutor")
    public Executor conceptSyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(2);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("concept-sync-");
        exec.setTaskDecorator(new TenantContextPropagatingTaskDecorator());
        exec.initialize();
        return exec;
    }
}
