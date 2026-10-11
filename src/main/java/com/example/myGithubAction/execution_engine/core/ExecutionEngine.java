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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
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
public class ExecutionEngine {

    private static final long RETRY_DELAY_MS = 2000;
    private static final long STEP_TIMEOUT_MS = 600000; // 10 minutes

    private final WorkFlowExecutionRepository workFlowExecutionRepository;
    private final WorkFlowExecutionStepRepository workFlowExecutionStepRepository;
    private final StepExecutor stepExecutor;
    private final LogManager logManager;
    private final ErrorHandler errorHandler;
    private final ExecutionCancellationRegistry cancellationRegistry;
    private final WorkspaceManager workspaceManager;

    public ExecutionEngine(
            WorkFlowExecutionRepository workFlowExecutionRepository,
            WorkFlowExecutionStepRepository workFlowExecutionStepRepository,
            StepExecutor stepExecutor,
            LogManager logManager,
            ErrorHandler errorHandler,
            ExecutionCancellationRegistry cancellationRegistry,
            WorkspaceManager workspaceManager) {
        this.workFlowExecutionRepository = workFlowExecutionRepository;
        this.workFlowExecutionStepRepository = workFlowExecutionStepRepository;
        this.stepExecutor = stepExecutor;
        this.logManager = logManager;
        this.errorHandler = errorHandler;
        this.cancellationRegistry = cancellationRegistry;
        this.workspaceManager = workspaceManager;
    }

    /**
     * Starts execution of a workflow asynchronously.
     *
     * Main entry point for workflow execution.
     * Runs in a separate thread so the calling HTTP request
     * can return immediately with the execution ID.
     * Each step is executed in its own transaction so partial
     * progress is persisted even if a later step fails.
     *
     * @param executionId the execution ID from database
     * @throws ResourceNotFoundException if execution not found
     */
    @Async("workflowExecutor")
    public void startExecution(Long executionId) {
        cancellationRegistry.register(executionId);
        boolean workspaceCreated = false;
        try {
            // 1. Load execution from DB
            WorkFlowExecution execution = workFlowExecutionRepository.findById(executionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Execution not found"));

            if (cancellationRegistry.isCancelled(executionId)) {
                // Cancelled before this async task even started (e.g. while PENDING) -
                // DB status is already CANCELLED, don't overwrite it with RUNNING.
                return;
            }

            try {
                workspaceManager.createWorkspace(executionId);
                workspaceCreated = true;
            } catch (Exception e) {
                if (!cancellationRegistry.isCancelled(executionId)) {
                    handleError(execution, e);
                }
                return;
            }

            // 2. Set status → RUNNING and set startedAt
            execution.setStatus(ExecutionState.RUNNING);
            execution.setStartedAt(LocalDateTime.now());
            workFlowExecutionRepository.save(execution);

            // 3. Load steps in order
            List<WorkFlowExecutionStep> steps = workFlowExecutionStepRepository.findByExecutionIdOrderByStepOrder(executionId);

            // 4. Execute each step sequentially, stopping on cancel or failure
            for (WorkFlowExecutionStep step : steps) {
                if (cancellationRegistry.isCancelled(executionId)) {
                    // DB status is already CANCELLED (set by cancelExecution) - don't overwrite it
                    return;
                }

                boolean success = executeStep(execution, step);
                if (!success) {
                    // Fail-fast: status already set to FAILED by executeStep/handleError
                    return;
                }
            }

            // 5. Handle completion
            handleCompletion(execution);
        } finally {
            if (workspaceCreated) {
                workspaceManager.deleteWorkspace(executionId);
            }
            cancellationRegistry.unregister(executionId);
        }
    }

    /**
     * Executes a single step, retrying on transient errors up to a fixed
     * number of attempts.
     *
     * @param execution the workflow execution
     * @param step the execution step
     * @return true if the step ended in SUCCESS, false if it ended in FAILED
     */
    private boolean executeStep(WorkFlowExecution execution, WorkFlowExecutionStep step) {
        int maxRetries = 3;
        int attempt = 0;

        while (true) {
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

                // 4. Update step status based on result
                if (result.isSuccess()) {
                    step.setStatus(ExecutionState.SUCCESS);
                } else {
                    String errorMsg = result.getErrorMessage() != null
                        ? result.getErrorMessage()
                        : "Step execution failed with exit code " + result.getExitCode();
                    throw new RuntimeException(errorMsg);
                }
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
                return true;

            } catch (Exception e) {
                boolean cancelled = cancellationRegistry.isCancelled(execution.getId());

                // Check shouldRetry()
                if (attempt < maxRetries && errorHandler.shouldRetry(e.getMessage(), cancelled)) {
                    attempt++;

                    // Log retry attempt
                    logManager.appendLog(step.getId(),
                        String.format("Retry attempt %d/%d: %s",
                            attempt, maxRetries, e.getMessage()));

                    // Wait before retry
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }

                    continue;
                }

                // Update step status → FAILED
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

                // Handle workflow error
                handleError(execution, e);
                return false;
            }
        }
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
