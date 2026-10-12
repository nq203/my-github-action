package com.example.myGithubAction.execution_engine.logging;

import org.springframework.stereotype.Component;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import java.time.LocalDateTime;

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

    private final WorkFlowExecutionStepRepository stepRepository;

    public LogManager(WorkFlowExecutionStepRepository stepRepository) {
        this.stepRepository = stepRepository;
    }

    /**
     * Appends a log line to step logs.
     *
     * Single atomic UPDATE (logs = logs || line), so concurrent appenders and
     * engine step-status writes can never overwrite each other's logs.
     *
     * @param stepId the step ID
     * @param logLine the log line to append
     */
    public void appendLog(Long stepId, String logLine) {
        try {
            stepRepository.appendLog(stepId, logLine + "\n", LocalDateTime.now());
        } catch (Exception e) {
            System.err.println("Failed to append log for step " + stepId + ": " + e.getMessage());
        }
    }

    /**
     * Flushes logs.
     *
     * MVP: No-op (logs already saved incrementally)
     */
    public void flush() {
    }
}
