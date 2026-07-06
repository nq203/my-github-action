package com.example.myGithubAction.execution_engine.logging;

import org.springframework.stereotype.Component;

/**
 * Simple logging manager for step execution.
 *
 * Responsible for:
 * - Appending logs to database
 * - Persisting output
 * - Flushing logs (no-op for MVP)
 *
 * Logs are saved incrementally to database.
 */
@Component
public class LogManager {

    /**
     * Appends a log line to step logs.
     *
     * Loads step from DB, appends line, saves back.
     * Simple append-only model.
     *
     * @param stepId the step ID
     * @param logLine the log line to append
     */
    public void appendLog(Long stepId, String logLine) {
        // TODO: Implement log appending logic
        // 1. Load step from repository by stepId
        // 2. Get current logs (or empty string)
        // 3. Append: currentLogs + logLine + "\n"
        // 4. Save step back to repository
        // 5. Handle exceptions gracefully
    }

    /**
     * Flushes logs.
     *
     * MVP: No-op (logs already saved incrementally)
     */
    public void flush() {
    }
}
