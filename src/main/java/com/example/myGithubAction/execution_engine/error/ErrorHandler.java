package com.example.myGithubAction.execution_engine.error;

import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.List;

/**
 * Basic error handling with retry logic.
 *
 * Determines if a failed step should be retried.
 * Only retries on transient/network errors.
 *
 * Retryable patterns:
 * - timeout
 * - connection
 * - temporarily
 * - unavailable
 * - io error
 *
 * Non-retryable:
 * - command not found
 * - permission denied
 * - file not found
 * - syntax error
 */
@Component
public class ErrorHandler {

    private static final List<String> RETRYABLE_PATTERNS = Arrays.asList(
        "timeout",
        "connection",
        "temporarily",
        "unavailable",
        "io error",
        "transient"
    );

    /**
     * Determines if an error should trigger a retry.
     *
     * Checks error message for retryable patterns.
     * Returns false for non-retryable errors, and false if the execution
     * has already been cancelled (no point retrying a cancelled run).
     *
     * @param errorMessage the error message
     * @param cancelled whether the owning execution has been cancelled
     * @return true if should retry, false otherwise
     */
    public boolean shouldRetry(String errorMessage, boolean cancelled) {
        if (cancelled) {
            return false;
        }

        // 1. Check if errorMessage is null or empty → return false
        if (errorMessage == null || errorMessage.trim().isEmpty()) {
            return false;
        }

        // 2. Convert to lowercase
        String lowerCase = errorMessage.toLowerCase();

        // 3. Check for retryable patterns
        for (String pattern : RETRYABLE_PATTERNS) {
            if (lowerCase.contains(pattern)) {
                // 4. Return true if any pattern matches
                return true;
            }
        }

        // 5. Return false otherwise
        return false;
    }
}
