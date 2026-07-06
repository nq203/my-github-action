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
     * Returns false for non-retryable errors.
     *
     * @param errorMessage the error message
     * @return true if should retry, false otherwise
     */
    public boolean shouldRetry(String errorMessage) {
        // TODO: Implement retry logic
        // 1. Check if errorMessage is null or empty → return false
        // 2. Convert to lowercase
        // 3. Check for retryable patterns
        // 4. Return true if any pattern matches
        // 5. Return false otherwise
        return false;
    }
}
