package com.example.myGithubAction.common;

/**
 * Execution state machine.
 *
 * Flow: PENDING → RUNNING → SUCCESS (all steps OK) or FAILED (any step failed)
 *
 * Allowed transitions:
 * - PENDING → RUNNING (worker claim), CANCELLED, FAILED (rejected / never started)
 * - RUNNING → SUCCESS, FAILED, CANCELLED
 * - SUCCESS, FAILED, CANCELLED are terminal and immutable
 *
 * Persistence must enforce these with compare-and-set updates
 * (UPDATE ... WHERE status IN (:from)), never by overwriting a loaded entity.
 */
public enum ExecutionState {
    PENDING("Waiting to execute"),
    RUNNING("Currently executing"),
    SUCCESS("Completed successfully"),
    FAILED("Execution failed"),
    CANCELLED("Execution cancelled");

    private final String description;

    ExecutionState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(ExecutionState target) {
        return switch (this) {
            case PENDING -> target == RUNNING || target == CANCELLED || target == FAILED;
            case RUNNING -> target == SUCCESS || target == FAILED || target == CANCELLED;
            case SUCCESS, FAILED, CANCELLED -> false;
        };
    }

    /** States from which a cancel request can still take effect. */
    public static final java.util.Set<ExecutionState> CANCELLABLE = java.util.EnumSet.of(PENDING, RUNNING);
}
