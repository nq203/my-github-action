package com.example.myGithubAction.execution_engine.config;

import com.example.myGithubAction.execution_engine.core.ExecutionEngine;
import com.example.myGithubAction.execution_engine.executor.StepExecutor;
import com.example.myGithubAction.execution_engine.executor.ShellCommandRunner;
import com.example.myGithubAction.execution_engine.logging.LogManager;
import com.example.myGithubAction.execution_engine.error.ErrorHandler;
import org.springframework.context.annotation.Configuration;

/**
 * ExecutionEngine configuration.
 *
 * Wire up all dependencies for ExecutionEngine components.
 *
 */
@Configuration
public class ExecutionEngineConfig {

    // Beans are auto-wired via @Component and @Service annotations
    // No manual bean registration needed for MVP

    // TODO: Add configuration properties if needed in future
    // TODO: Add bean definitions if manual control needed
}
