package com.example.myGithubAction.execution_engine.exception;

/**
 * Base execution exception.
 *
 * Parent class for all ExecutionEngine exceptions.
 */
public class ExecutionException extends RuntimeException {

    public ExecutionException(String message) {
        super(message);
    }

    public ExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
