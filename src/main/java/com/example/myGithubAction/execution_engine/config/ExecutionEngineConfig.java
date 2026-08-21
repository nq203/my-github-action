package com.example.myGithubAction.execution_engine.config;

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
     */
    @Bean(name = "workflowExecutor")
    public Executor workflowExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("workflow-exec-");
        executor.initialize();
        return executor;
    }
}
