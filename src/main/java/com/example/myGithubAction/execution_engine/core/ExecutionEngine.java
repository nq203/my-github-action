package com.example.myGithubAction.execution_engine.core;

import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.execution_engine.executor.StepExecutor;
import com.example.myGithubAction.execution_engine.logging.LogManager;
import com.example.myGithubAction.auth.exception.ResourceNotFoundException;
import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.execution_engine.dto.StepExecutionResult;
import com.example.myGithubAction.execution_engine.error.ErrorHandler;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Main ExecutionEngine orchestrator.
 *
 * Responsible for:
 * - Managing workflow execution lifecycle
 * - Coordinating step execution
 * - Tracking status
 * - Handling errors
 *
 */
@Service
@Transactional
public class ExecutionEngine {

    private static final long RETRY_DELAY_MS = 2000;
    private static final long STEP_TIMEOUT_MS = 600000; // 10 minutes

    private final WorkFlowExecutionRepository workFlowExecutionRepository;
    private final WorkFlowExecutionStepRepository workFlowExecutionStepRepository;
    private final StepExecutor stepExecutor;
    private final LogManager logManager;
    private final ErrorHandler errorHandler;

    @Autowired
    public ExecutionEngine(
            WorkFlowExecutionRepository workFlowExecutionRepository,
            WorkFlowExecutionStepRepository workFlowExecutionStepRepository,
            StepExecutor stepExecutor,
            LogManager logManager,
            ErrorHandler errorHandler) {
        this.workFlowExecutionRepository = workFlowExecutionRepository;
        this.workFlowExecutionStepRepository = workFlowExecutionStepRepository;
        this.stepExecutor = stepExecutor;
        this.logManager = logManager;
        this.errorHandler = errorHandler;
    }

    /**
     * Starts execution of a workflow.
     *
     * Main entry point for workflow execution.
     * Performs sequential execution of all steps.
     *
     * @param executionId the execution ID from database
     * @throws ResourceNotFoundException if execution not found
     * @throws ExecutionException on execution failure
     */
    public void startExecution(Long executionId) {
        // TODO: Implement execution start logic
        // 1. Load execution from DB
        WorkFlowExecution execution = workFlowExecutionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found"));

        // 2. Set status → RUNNING
        execution.setStatus(ExecutionState.RUNNING);
        workFlowExecutionRepository.save(execution);

        // 3. Load steps in order
        List<WorkFlowExecutionStep> steps = workFlowExecutionStepRepository.findByExecutionIdOrderByStepOrder(executionId);

        // 4. Execute each step sequentially
        for (WorkFlowExecutionStep step : steps) {
            executeStep(execution, step);
        }

        // 5. Handle completion
        handleCompletion(execution);
    }

    /**
     * Executes a single step.
     *
     * Internal method for executing a single workflow step.
     * Handles retries and error capturing.
     *
     * @param execution the workflow execution
     * @param step the execution step
     */
    private void executeStep(WorkFlowExecution execution, WorkFlowExecutionStep step) {
        try {
            // 1. Set step status → RUNNING + timestamp
            step.setStatus(ExecutionState.RUNNING);
            step.setStartedAt(LocalDateTime.now());
            workFlowExecutionStepRepository.save(step);

            // 2. Call StepExecutor.execute() using injected instance
            StepExecutionResult result =
                this.stepExecutor.execute(step);

            // 3. Capture logs
            if (result.getOutput() != null && !result.getOutput().isEmpty()) {
                logManager.appendLog(step.getId(), result.getOutput());
            }
            if (result.getErrorMessage() != null && !result.getErrorMessage().isEmpty()) {
                logManager.appendLog(step.getId(), "[ERROR] " + result.getErrorMessage());
            }

            // 4. Update step status → SUCCESS
            step.setStatus(ExecutionState.SUCCESS);
            step.setEndedAt(LocalDateTime.now());

            // Calculate duration in milliseconds
            if (step.getStartedAt() != null) {
                long durationMs = java.time.Duration.between(
                    step.getStartedAt(),
                    step.getEndedAt()
                ).toMillis();
                step.setDuration(durationMs);
            }

            workFlowExecutionStepRepository.save(step);

        } catch (Exception e) {
            // 3. Check shouldRetry()
            if (errorHandler.shouldRetry(e.getMessage())) {
                // Get retry count, default to 0 if null
                int retryCount = step.getId() != null ? getRetryCount(step) : 0;
                int maxRetries = 3;

                if (retryCount < maxRetries) {
                    // Log retry attempt
                    logManager.appendLog(step.getId(),
                        String.format("Retry attempt %d/%d: %s",
                            retryCount + 1, maxRetries, e.getMessage()));

                    // Wait before retry
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }

                    // Recursive retry
                    executeStep(execution, step);
                    return;
                }
            }

            // 4. Update step status → FAILED
            step.setStatus(ExecutionState.FAILED);
            step.setErrorMessage(e.getMessage());
            step.setEndedAt(LocalDateTime.now());

            // Calculate duration in milliseconds
            if (step.getStartedAt() != null) {
                long durationMs = java.time.Duration.between(
                    step.getStartedAt(),
                    step.getEndedAt()
                ).toMillis();
                step.setDuration(durationMs);
            }

            // Capture error log
            logManager.appendLog(step.getId(), "[FAILED] " + e.getMessage());
            workFlowExecutionStepRepository.save(step);

            // 5. Handle workflow error
            handleError(execution, e);
        }
    }

    /**
     * Helper method to get retry count for a step.
     *
     * @param step the execution step
     * @return retry count from database
     */
    private int getRetryCount(WorkFlowExecutionStep step) {
        // Use a separate counter mechanism or store in logs
        // For now, retrieve from database to check how many times executed
        java.util.List<WorkFlowExecutionStep> steps =
            workFlowExecutionStepRepository.findByExecutionId(step.getExecutionId());
        return (int) steps.stream()
            .filter(s -> s.getStepId().equals(step.getStepId()))
            .filter(s -> s.getStatus() == ExecutionState.FAILED)
            .count();
    }

    /**
     * Handles completion of execution.
     *
     * Called after all steps are executed.
     * Determines final status and flushes logs.
     *
     * @param execution the workflow execution
     */
    private void handleCompletion(WorkFlowExecution execution) {
        // 1. Load all steps for this execution
        java.util.List<WorkFlowExecutionStep> steps = workFlowExecutionStepRepository
            .findByExecutionId(execution.getId());

        // 2. Determine final status - all steps must be SUCCESS
        boolean allSuccessful = steps.stream()
            .allMatch(s -> s.getStatus() == ExecutionState.SUCCESS);

        // 3. Set execution.status
        ExecutionState finalStatus = allSuccessful
            ? ExecutionState.SUCCESS
            : ExecutionState.FAILED;
        execution.setStatus(finalStatus);

        // 4. Set execution.endedAt
        execution.setEndedAt(LocalDateTime.now());

        // Save to database
        workFlowExecutionRepository.save(execution);

        // 5. Flush logs to database
        logManager.flush();
    }

    /**
     * Handles execution errors.
     *
     * Called when execution fails.
     * Persists error state to database.
     *
     * @param execution the workflow execution
     * @param error the exception that occurred
     */
    private void handleError(WorkFlowExecution execution, Exception error) {
        // 1. Set execution.status → FAILED
        execution.setStatus(ExecutionState.FAILED);

        // 2. Set error message
        execution.setErrorMessage(error.getMessage());

        // 3. Set endedAt timestamp
        execution.setEndedAt(LocalDateTime.now());

        // 4. Save to database
        workFlowExecutionRepository.save(execution);
    }
}
