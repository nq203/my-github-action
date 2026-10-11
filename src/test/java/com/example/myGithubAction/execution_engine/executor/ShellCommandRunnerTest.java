package com.example.myGithubAction.execution_engine.executor;

import com.example.myGithubAction.execution_engine.core.ExecutionCancellationRegistry;
import com.example.myGithubAction.execution_engine.dto.StepExecutionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ShellCommandRunnerTest {

    @Test
    void cancellingMidRunReturnsQuicklyWithoutWaitingOutTimeout() throws InterruptedException {
        assumeTrue(new java.io.File("/bin/bash").exists(), "requires /bin/bash");

        ExecutionCancellationRegistry registry = new ExecutionCancellationRegistry();
        ShellCommandRunner runner = new ShellCommandRunner(registry);
        Long executionId = 1L;
        registry.register(executionId);

        StepExecutionResult[] resultHolder = new StepExecutionResult[1];
        long start = System.currentTimeMillis();

        Thread runnerThread = new Thread(() ->
            resultHolder[0] = runner.execute("sleep 5", 10000, executionId));
        runnerThread.start();

        Thread.sleep(300);
        registry.cancel(executionId);
        runnerThread.join(3000);

        long elapsed = System.currentTimeMillis() - start;

        assertFalse(runnerThread.isAlive(), "runner thread should have finished after cancel");
        assertNotNull(resultHolder[0]);
        assertFalse(resultHolder[0].isSuccess());
        assertTrue(elapsed < 3000, "should return well before the 5s sleep or 10s timeout, took " + elapsed + "ms");
    }

    @Test
    void successfulCommandReturnsOutput() {
        ExecutionCancellationRegistry registry = new ExecutionCancellationRegistry();
        ShellCommandRunner runner = new ShellCommandRunner(registry);
        registry.register(2L);

        StepExecutionResult result = runner.execute("echo hello", 5000, 2L);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getExitCode());
        assertTrue(result.getOutput().contains("hello"));
    }
}
