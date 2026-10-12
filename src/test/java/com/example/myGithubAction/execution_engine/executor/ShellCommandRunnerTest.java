package com.example.myGithubAction.execution_engine.executor;

import com.example.myGithubAction.execution_engine.core.ExecutionCancellationRegistry;
import com.example.myGithubAction.execution_engine.dto.StepExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ShellCommandRunnerTest {

    @TempDir
    Path workspace;

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
            resultHolder[0] = runner.execute("sleep 5", 10000, executionId, workspace));
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

        StepExecutionResult result = runner.execute("echo hello", 5000, 2L, workspace);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getExitCode());
        assertTrue(result.getOutput().contains("hello"));
    }

    @Test
    void commandRunsInsideProvidedWorkingDirectory() throws Exception {
        ExecutionCancellationRegistry registry = new ExecutionCancellationRegistry();
        ShellCommandRunner runner = new ShellCommandRunner(registry);
        registry.register(3L);

        StepExecutionResult result = runner.execute("pwd", 5000, 3L, workspace);

        assertTrue(result.isSuccess());
        assertEquals(workspace.toRealPath().toString(), result.getOutput());
    }

    private List<String> runCollecting(String command, long id, StepExecutionResult[] out) {
        ExecutionCancellationRegistry registry = new ExecutionCancellationRegistry();
        ShellCommandRunner runner = new ShellCommandRunner(registry);
        registry.register(id);
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        out[0] = runner.execute(command, 5000, id, workspace, lines::add);
        return lines;
    }

    @Test
    void callbackReceivesStdoutAndStderrInOrder() {
        StepExecutionResult[] r = new StepExecutionResult[1];
        List<String> lines = runCollecting("echo a; echo b >&2; echo c", 10L, r);

        assertEquals(List.of("a", "b", "c"), lines);
        assertEquals("a\nb\nc", r[0].getOutput());
    }

    @Test
    void lastLineWithoutNewlineIsDelivered() {
        StepExecutionResult[] r = new StepExecutionResult[1];
        List<String> lines = runCollecting("printf x", 11L, r);

        assertEquals(List.of("x"), lines);
        assertEquals("x", r[0].getOutput());
    }

    @Test
    void emptyOutputCommandCallsNoCallbackAndSucceeds() {
        StepExecutionResult[] r = new StepExecutionResult[1];
        List<String> lines = runCollecting("true", 12L, r);

        assertTrue(lines.isEmpty());
        assertTrue(r[0].isSuccess());
    }

    @Test
    void linesArriveBeforeCommandEnds() {
        ExecutionCancellationRegistry registry = new ExecutionCancellationRegistry();
        ShellCommandRunner runner = new ShellCommandRunner(registry);
        registry.register(13L);
        long start = System.currentTimeMillis();
        long[] firstAt = {-1};

        runner.execute("echo first; sleep 1; echo second", 5000, 13L, workspace, line -> {
            if (line.equals("first")) {
                firstAt[0] = System.currentTimeMillis() - start;
            }
        });

        assertTrue(firstAt[0] >= 0 && firstAt[0] < 800, "first line seen at " + firstAt[0] + "ms");
    }

    @Test
    void burstAtTheEndIsFullyDeliveredThroughSlowStore() throws Exception {
        com.example.myGithubAction.execution_engine.logging.StepLogStore store =
            org.mockito.Mockito.mock(com.example.myGithubAction.execution_engine.logging.StepLogStore.class);
        List<String> stored = Collections.synchronizedList(new ArrayList<>());
        org.mockito.Mockito.when(store.append(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyList()))
            .thenAnswer(inv -> {
                Thread.sleep(50);
                List<String> lines = new ArrayList<>(inv.<List<String>>getArgument(1));
                stored.addAll(lines);
                return java.util.Optional.of(
                    new com.example.myGithubAction.execution_engine.logging.StepLogStore.Appended(1, lines));
            });
        com.example.myGithubAction.messaging.ExecutionEventPublisher publisher =
            org.mockito.Mockito.mock(com.example.myGithubAction.messaging.ExecutionEventPublisher.class);
        com.example.myGithubAction.execution_engine.logging.LogManager logManager =
            new com.example.myGithubAction.execution_engine.logging.LogManager(store, publisher);
        try {
            ExecutionCancellationRegistry registry = new ExecutionCancellationRegistry();
            ShellCommandRunner runner = new ShellCommandRunner(registry);
            registry.register(14L);

            StepExecutionResult result;
            try (com.example.myGithubAction.execution_engine.logging.StepLogSink sink = logManager.openSink(5L)) {
                result = runner.execute("for i in $(seq 1 3000); do echo line$i; done", 20000, 14L, workspace,
                    sink::accept);
            }

            List<String> expected = new ArrayList<>();
            for (int i = 1; i <= 3000; i++) {
                expected.add("line" + i);
            }
            assertEquals(expected, new ArrayList<>(stored));
            assertEquals(String.join("\n", expected), result.getOutput());
        } finally {
            logManager.shutdown();
        }
    }
}
