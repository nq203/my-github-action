package com.example.myGithubAction.messaging;

/**
 * Message payloads, JSON on the wire. {@code version} lets API and worker be deployed
 * at different times: consumers ignore fields they do not know.
 */
public final class ExecutionEvents {

    public static final int VERSION = 1;

    private ExecutionEvents() {
    }

    public record ExecutionRequested(int version, Long executionId, long requestedAtEpochMs) {
        public static ExecutionRequested of(Long executionId) {
            return new ExecutionRequested(VERSION, executionId, System.currentTimeMillis());
        }
    }

    public record ExecutionCancel(int version, Long executionId, long requestedAtEpochMs) {
        public static ExecutionCancel of(Long executionId) {
            return new ExecutionCancel(VERSION, executionId, System.currentTimeMillis());
        }
    }
}
