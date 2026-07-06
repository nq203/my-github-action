package com.example.myGithubAction.execution_engine.exception;

/**
 * Resource not found exception.
 *
 * Thrown when a required resource (execution, step, etc.) is not found.
 */
public class ResourceNotFoundException extends ExecutionException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
