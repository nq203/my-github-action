package com.example.myGithubAction.execution_engine.worker;

import com.example.myGithubAction.messaging.ExecutionDispatcher;
import com.example.myGithubAction.execution_engine.core.ExecutionCancellationRegistry;
import com.example.myGithubAction.execution_engine.core.ExecutionEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;

/** Runs executions on this JVM's workflow executor. Used when app.kafka.enabled=false. */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "false")
public class InProcessExecutionDispatcher implements ExecutionDispatcher {

    private final ExecutionEngine executionEngine;
    private final ExecutionCancellationRegistry cancellationRegistry;

    public InProcessExecutionDispatcher(
            ExecutionEngine executionEngine, ExecutionCancellationRegistry cancellationRegistry) {
        this.executionEngine = executionEngine;
        this.cancellationRegistry = cancellationRegistry;
    }

    @Override
    public void dispatch(Long executionId) {
        try {
            executionEngine.startExecution(executionId);
        } catch (RejectedExecutionException e) {
            throw new RejectedExecutionException("thread pool and queue are full", e);
        }
    }

    @Override
    public void requestCancel(Long executionId) {
        cancellationRegistry.cancel(executionId);
    }
}
