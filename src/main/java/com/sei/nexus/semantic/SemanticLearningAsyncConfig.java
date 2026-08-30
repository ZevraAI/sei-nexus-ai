package com.sei.nexus.semantic;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * A dedicated, named thread pool for {@link SemanticLearningService}'s {@code @Async} methods —
 * isolated from Spring's default {@code @Async} executor (the unbounded {@code
 * SimpleAsyncTaskExecutor}, per {@code onboarding.AsyncConfig}'s own documented rationale for why
 * that default is avoided elsewhere in this app) for the same reason {@code
 * onboardingJobExecutor} exists: a shared default executor with no {@link
 * TenantContextPropagatingTaskDecorator} silently loses {@code TenantContext} across the thread
 * hand-off, which is exactly the defect this bean fixes for learning specifically.
 *
 * <p>Deliberately scoped to this one feature — this is not a general-purpose async framework
 * change. {@link com.sei.nexus.governance.GovernanceAuditService}, {@link
 * com.sei.nexus.memory.DocumentMemoryService}, and {@link com.sei.nexus.query.QueryGovernanceService}
 * have the same class of exposure (see the design review this fix is based on) but are
 * explicitly out of scope here and remain on their existing executors, unmodified.
 */
@Configuration
public class SemanticLearningAsyncConfig {

    @Bean(name = "semanticLearningExecutor")
    public Executor semanticLearningExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("semantic-learning-");
        exec.setTaskDecorator(new TenantContextPropagatingTaskDecorator());
        exec.initialize();
        return exec;
    }
}
