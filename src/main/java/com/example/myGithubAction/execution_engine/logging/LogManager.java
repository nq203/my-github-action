package com.example.myGithubAction.execution_engine.logging;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import java.util.Optional;

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

    @Autowired
    public LogManager(WorkFlowExecutionStepRepository stepRepository) {
        this.stepRepository = stepRepository;
    }

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
        // 1. Load step from repository by stepId
        Optional<WorkFlowExecutionStep> optionalStep = stepRepository.findById(stepId);

        if (optionalStep.isEmpty()) {
            // Log not found, skip
            return;
        }

        WorkFlowExecutionStep step = optionalStep.get();

        // 2. Get current logs (or empty string)
        String currentLogs = step.getLogs() != null ? step.getLogs() : "";

        // 3. Append: currentLogs + logLine + "\n"
        String updatedLogs = currentLogs + logLine + "\n";

        // 4. Save step back to repository
        step.setLogs(updatedLogs);

        try {
            stepRepository.save(step);
        } catch (Exception e) {
            // 5. Handle exceptions gracefully
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
