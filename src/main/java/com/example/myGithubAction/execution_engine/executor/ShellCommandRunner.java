package com.example.myGithubAction.execution_engine.executor;

import com.example.myGithubAction.execution_engine.dto.StepExecutionResult;
import org.springframework.stereotype.Component;
import java.io.BufferedReader;
import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    private static final String SHELL = "/bin/bash";
    private static final String SHELL_FLAG = "-c";

    /**
     * Executes a bash command.
     *
     * Runs command with specified timeout.
     * Returns output and exit code.
     *
     * @param command the bash command to execute
     * @param timeoutMs timeout in milliseconds
     * @return execution result with output and status
     */
    public StepExecutionResult execute(String command, long timeoutMs) {
        try {
            // 1. Create ProcessBuilder with [/bin/bash, -c, command]
            ProcessBuilder processBuilder = new ProcessBuilder(SHELL, SHELL_FLAG, command);

            // 2. Keep stderr separate to capture both streams concurrently
            processBuilder.redirectErrorStream(false);

            // 3. Start process
            Process process = processBuilder.start();

            // 4. Create synchronized buffer for output collection
            StringBuilder stdoutBuffer = new StringBuilder();
            StringBuilder stderrBuffer = new StringBuilder();

            // 5. Start concurrent reader threads for stdout and stderr
            Thread stdoutReader = new Thread(() -> {
                try {
                    String output = readOutput(process.getInputStream());
                    synchronized (stdoutBuffer) {
                        stdoutBuffer.append(output);
                    }
                } catch (java.io.IOException ignored) {
                }
            });
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            Thread stderrReader = new Thread(() -> {
                try {
                    String output = readOutput(process.getErrorStream());
                    synchronized (stderrBuffer) {
                        stderrBuffer.append(output);
                    }
                } catch (java.io.IOException ignored) {
                }
            });
            stderrReader.setDaemon(true);
            stderrReader.start();

            // 6. Wait with timeout
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

            if (!finished) {
                // 7. Handle timeout case (destroy process)
                process.destroyForcibly();
                return StepExecutionResult.builder()
                    .exitCode(-1)
                    .errorMessage("Command execution timed out after " + timeoutMs + "ms")
                    .success(false)
                    .build();
            }

            // 8. Wait for reader threads to complete (with timeout)
            stdoutReader.join(1000);
            stderrReader.join(1000);

            // 9. Combine output from both streams
            String combinedOutput = stdoutBuffer.toString();
            if (stderrBuffer.length() > 0) {
                if (combinedOutput.length() > 0) {
                    combinedOutput += "\n" + stderrBuffer.toString();
                } else {
                    combinedOutput = stderrBuffer.toString();
                }
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

    /**
     * Reads output from input stream.
     *
     * Reads all lines and joins them.
     *
     * @param inputStream the input stream
     * @return combined output string
     * @throws java.io.IOException on read error
     */
    private String readOutput(InputStream inputStream) throws IOException {
        // 1. Create BufferedReader with try-with-resources
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            // 2. Read all lines
            // 3. Join with newline
            String output = reader.lines().collect(Collectors.joining("\n"));

            // 4. Return combined output
            return output;
        }
    }
}
