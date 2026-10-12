package com.example.myGithubAction.execution_engine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * ExecutionEngine configuration.
 *
 * Wire up all dependencies for ExecutionEngine components.
 */
@Configuration
@EnableAsync
public class ExecutionEngineConfig {

    /**
     * Async executor for workflow execution.
     *
     * Provides a dedicated thread pool so workflow executions
     * don't starve the main HTTP thread pool.
     *
     * A ThreadPoolTaskExecutor only grows beyond its core size once the queue is full,
     * so core == max by default: the number of executions that really run at once
     * equals app.kafka.worker.max-concurrent-executions (default 4).
     */
    @Bean(name = "workflowExecutor")
    public Executor workflowExecutor(
            @Value("${execution.executor.core-pool-size:4}") int corePoolSize,
            @Value("${execution.executor.max-pool-size:4}") int maxPoolSize,
            @Value("${execution.executor.queue-capacity:10}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("workflow-exec-");
        executor.initialize();
        return executor;
    }
}
