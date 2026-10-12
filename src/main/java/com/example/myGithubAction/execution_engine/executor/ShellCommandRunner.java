package com.example.myGithubAction.execution_engine.executor;

import com.example.myGithubAction.execution_engine.core.ExecutionCancellationRegistry;
import com.example.myGithubAction.execution_engine.dto.StepExecutionResult;
import org.springframework.stereotype.Component;
import java.io.BufferedReader;
import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Executes bash commands via ProcessBuilder.
 *
 * Responsible for:
 * - Starting shell process
 * - Managing command execution
 * - Capturing stdout/stderr
 * - Handling timeout
 * - Returning execution result
 */
@Component
public class ShellCommandRunner {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ShellCommandRunner.class);
    private static final String SHELL = "/bin/bash";
    private static final String SHELL_FLAG = "-c";
    private static final long POLL_INTERVAL_MS = 300;

    private final ExecutionCancellationRegistry cancellationRegistry;

    public ShellCommandRunner(ExecutionCancellationRegistry cancellationRegistry) {
        this.cancellationRegistry = cancellationRegistry;
    }

    /**
     * Executes a bash command.
     *
     * Runs command with specified timeout.
     * Returns output and exit code.
     *
     * @param command the bash command to execute
     * @param timeoutMs timeout in milliseconds
     * @param executionId the workflow execution this command belongs to,
     *                     used to support cancellation mid-run
     * @return execution result with output and status
     */
    public StepExecutionResult execute(String command, long timeoutMs, Long executionId, Path workingDirectory) {
        return execute(command, timeoutMs, executionId, workingDirectory, line -> { });
    }

    /**
     * Executes a bash command, handing every output line (stdout and stderr merged) to onLine as
     * soon as it is read. The returned output is those lines joined with newlines.
     */
    public StepExecutionResult execute(String command, long timeoutMs, Long executionId, Path workingDirectory,
                                       Consumer<String> onLine) {
        try {
            // 1. Create ProcessBuilder with [/bin/bash, -c, command]
            ProcessBuilder processBuilder = new ProcessBuilder(SHELL, SHELL_FLAG, command);
            processBuilder.directory(workingDirectory.toFile());

            // 2. Merge stderr into stdout so a single reader sees lines in order
            processBuilder.redirectErrorStream(true);

            // 3. Start process
            Process process = processBuilder.start();
            cancellationRegistry.registerProcess(executionId, process);

            // 4. Single reader thread streams each line to the callback and collects the output
            StringBuilder outputBuffer = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (outputBuffer) {
                            if (outputBuffer.length() > 0) {
                                outputBuffer.append('\n');
                            }
                            outputBuffer.append(line);
                        }
                        try {
                            onLine.accept(line);
                        } catch (RuntimeException ignored) {
                            // a failing consumer must not stop the command's output collection
                        }
                    }
                } catch (IOException ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();

            // 6. Wait with timeout, polling so cancellation can interrupt mid-run
            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean finished = false;
            boolean cancelled = false;
            while (System.currentTimeMillis() < deadline) {
                finished = process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (finished) {
                    break;
                }
                if (cancellationRegistry.isCancelled(executionId)) {
                    cancelled = true;
                    break;
                }
            }

            if (cancelled) {
                process.destroyForcibly();
                cancellationRegistry.clearProcess(executionId);
                return StepExecutionResult.builder()
                    .exitCode(-1)
                    .errorMessage("Cancelled")
                    .success(false)
                    .build();
            }

            if (!finished) {
                // 7. Handle timeout case (destroy process)
                process.destroyForcibly();
                cancellationRegistry.clearProcess(executionId);
                return StepExecutionResult.builder()
                    .exitCode(-1)
                    .errorMessage("Command execution timed out after " + timeoutMs + "ms")
                    .success(false)
                    .build();
            }

            cancellationRegistry.clearProcess(executionId);

            // 8. Wait for the reader thread to drain the stream (with timeout)
            reader.join(1000);
            if (reader.isAlive()) {
                log.warn("Output reader still running after the command exited; the last output lines may be missing");
            }

            // 9. Output collected so far
            String combinedOutput;
            synchronized (outputBuffer) {
                combinedOutput = outputBuffer.toString();
            }

            // 10. Return StepExecutionResult with exit code and output
            int exitCode = process.exitValue();
            return StepExecutionResult.builder()
                .exitCode(exitCode)
                .output(combinedOutput)
                .success(exitCode == 0)
                .errorMessage(exitCode != 0 ? "Command failed with exit code " + exitCode : null)
                .build();

        } catch (Exception e) {
            return StepExecutionResult.builder()
                .exitCode(-1)
                .errorMessage("Command execution failed: " + e.getMessage())
                .success(false)
                .build();
        }
    }
}
