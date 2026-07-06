package com.example.myGithubAction.execution_engine.core;

/**
 * Execution state machine - 4 states only for MVP.
 *
 * Flow: PENDING → RUNNING → SUCCESS (all steps OK) or FAILED (any step failed)
 */
public enum ExecutionState {
    PENDING("Waiting to execute"),
    RUNNING("Currently executing"),
    SUCCESS("Completed successfully"),
    FAILED("Execution failed");

    private final String description;

    ExecutionState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
