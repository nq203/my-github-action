package com.example.myGithubAction.execution_engine.exception;

/**
 * Step execution exception.
 *
 * Thrown when a step fails to execute.
 */
public class StepExecutionException extends ExecutionException {

    private Long stepId;

    public StepExecutionException(String message) {
        super(message);
    }

    public StepExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public StepExecutionException(String message, Long stepId) {
        super(message);
        this.stepId = stepId;
    }

    public Long getStepId() {
        return stepId;
    }

    public void setStepId(Long stepId) {
        this.stepId = stepId;
    }
}
