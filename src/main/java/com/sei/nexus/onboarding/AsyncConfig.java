package com.sei.nexus.onboarding;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * A dedicated, bounded thread pool for onboarding analysis jobs.
 *
 * <p>{@code @EnableAsync} (declared at {@code ZevraApplication}) runs {@code @Async}
 * methods elsewhere in the app on Spring's default {@code SimpleAsyncTaskExecutor} —
 * unbounded, one new thread per task. Onboarding jobs run N tables concurrently per
 * job across potentially many concurrent jobs (one per tenant onboarding at once);
 * an unbounded pool here would just relocate the exact resource-exhaustion risk
 * this feature exists to fix. This bean gives that work its own bounded, named pool
 * instead.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "onboardingJobExecutor")
    public Executor onboardingJobExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(8);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("onboarding-job-");
        exec.initialize();
        return exec;
    }
}
