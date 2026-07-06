package com.example.myGithubAction.execution_engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Step execution result container.
 *
 * Holds result of step execution:
 * - Exit code
 * - Output (stdout)
 * - Error message (stderr)
 * - Success flag
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepExecutionResult {

    private int exitCode;
    private String output;
    private String errorMessage;
    private boolean success;

    /**
     * Creates a success result.
     *
     * @param output the command output
     * @return success result
     */
    public static StepExecutionResult success(String output) {
        return StepExecutionResult.builder()
            .exitCode(0)
            .output(output)
            .success(true)
            .build();
    }

    /**
     * Creates a failure result.
     *
     * @param errorMessage the error message
     * @return failure result
     */
    public static StepExecutionResult failure(String errorMessage) {
        return StepExecutionResult.builder()
            .exitCode(-1)
            .errorMessage(errorMessage)
            .success(false)
            .build();
    }
}
