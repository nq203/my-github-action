package com.example.myGithubAction.execution_engine.core;

import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.execution_engine.executor.StepExecutor;
import com.example.myGithubAction.execution_engine.logging.LogManager;
import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.execution_engine.dto.StepExecutionResult;
import com.example.myGithubAction.execution_engine.error.ErrorHandler;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Main ExecutionEngine orchestrator.
 *
 * Responsible for:
 * - Managing workflow execution lifecycle
 * - Coordinating step execution
 * - Tracking status
 * - Handling errors
 *
 * All execution/step state changes are compare-and-set updates (see
 * ExecutionState for the allowed transitions): the engine never save()s a
 * loaded entity, so a concurrent cancel can never be overwritten.
 */
@Service
public class ExecutionEngine {

    private static final long RETRY_DELAY_MS = 2000;
    private static final long STEP_TIMEOUT_MS = 600000; // 10 minutes
    private static final Set<ExecutionState> EXECUTION_RUNNING = EnumSet.of(ExecutionState.RUNNING);
    private static final Set<ExecutionState> STEP_RUNNING = EnumSet.of(ExecutionState.RUNNING);

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
     *
     * The execution is first claimed with an atomic PENDING → RUNNING update.
     * If the claim affects 0 rows (cancelled while PENDING, already claimed by
     * another worker, duplicate delivery) this call does nothing at all.
     *
     * @param executionId the execution ID from database
     */
    @Async("workflowExecutor")
    public void startExecution(Long executionId) {
        cancellationRegistry.register(executionId);
        boolean workspaceCreated = false;
        try {
            if (cancellationRegistry.isCancelled(executionId)) {
                // Cancelled before this async task even started; DB is already CANCELLED.
                return;
            }

            // 1. Claim: PENDING → RUNNING (+ startedAt). Loses the race → nothing to do.
            if (workFlowExecutionRepository.claimPending(executionId, LocalDateTime.now()) == 0) {
                return;
            }

            try {
                workspaceManager.createWorkspace(executionId);
                workspaceCreated = true;
            } catch (Exception e) {
                handleError(executionId, e);
                return;
            }

            // 2. Load steps in order
            List<WorkFlowExecutionStep> steps = workFlowExecutionStepRepository.findByExecutionIdOrderByStepOrder(executionId);

            // 3. Execute each step sequentially, stopping on cancel or failure
            for (WorkFlowExecutionStep step : steps) {
                if (cancellationRegistry.isCancelled(executionId)) {
                    // DB status is already CANCELLED (set by cancelExecution) - nothing to write
                    return;
                }

                boolean success = executeStep(executionId, step);
                if (!success) {
                    // Fail-fast: execution already moved to FAILED (or CANCELLED) by handleError
                    return;
                }
            }

            // 4. Handle completion
            handleCompletion(executionId);
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
     * @param executionId the owning workflow execution
     * @param step the execution step
     * @return true if the step ended in SUCCESS, false if it ended in FAILED
     */
    private boolean executeStep(Long executionId, WorkFlowExecutionStep step) {
        final Long stepId = step.getId();
        int maxRetries = 3;
        int attempt = 0;

        while (true) {
            LocalDateTime startedAt = LocalDateTime.now();
            try {
                // 1. Step → RUNNING + timestamp (targeted update, logs untouched)
                workFlowExecutionStepRepository.markRunning(stepId, startedAt);

                // 2. Call StepExecutor.execute() using injected instance
                StepExecutionResult result = this.stepExecutor.execute(step);

                // 3. Capture logs
                if (result.getOutput() != null && !result.getOutput().isEmpty()) {
                    logManager.appendLog(stepId, result.getOutput());
                }
                if (result.getErrorMessage() != null && !result.getErrorMessage().isEmpty()) {
                    logManager.appendLog(stepId, "[ERROR] " + result.getErrorMessage());
                }

                // 4. Update step status based on result
                if (!result.isSuccess()) {
                    String errorMsg = result.getErrorMessage() != null
                        ? result.getErrorMessage()
                        : "Step execution failed with exit code " + result.getExitCode();
                    throw new RuntimeException(errorMsg);
                }

                LocalDateTime endedAt = LocalDateTime.now();
                workFlowExecutionStepRepository.transition(stepId, STEP_RUNNING, ExecutionState.SUCCESS,
                    endedAt, Duration.between(startedAt, endedAt).toMillis(), null);
                return true;

            } catch (Exception e) {
                boolean cancelled = cancellationRegistry.isCancelled(executionId);

                // Check shouldRetry()
                if (attempt < maxRetries && errorHandler.shouldRetry(e.getMessage(), cancelled)) {
                    attempt++;

                    // Log retry attempt
                    logManager.appendLog(stepId,
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

                // Capture error log, then step → FAILED
                logManager.appendLog(stepId, "[FAILED] " + e.getMessage());
                LocalDateTime endedAt = LocalDateTime.now();
                workFlowExecutionStepRepository.transition(stepId, STEP_RUNNING, ExecutionState.FAILED,
                    endedAt, Duration.between(startedAt, endedAt).toMillis(), e.getMessage());

                // Handle workflow error
                handleError(executionId, e);
                return false;
            }
        }
    }

    /**
     * Handles completion of execution.
     *
     * Called after all steps are executed. RUNNING → SUCCESS/FAILED is a
     * compare-and-set, so it is a no-op if the execution was cancelled meanwhile.
     */
    private void handleCompletion(Long executionId) {
        // 1. Load all steps for this execution
        List<WorkFlowExecutionStep> steps = workFlowExecutionStepRepository.findByExecutionId(executionId);

        // 2. Determine final status - all steps must be SUCCESS
        boolean allSuccessful = steps.stream()
            .allMatch(s -> s.getStatus() == ExecutionState.SUCCESS);
        ExecutionState finalStatus = allSuccessful ? ExecutionState.SUCCESS : ExecutionState.FAILED;

        // 3. Atomically finish (status + endedAt)
        workFlowExecutionRepository.transition(
            executionId, EXECUTION_RUNNING, finalStatus, LocalDateTime.now(), null);

        // 4. Flush logs to database
        logManager.flush();
    }

    /**
     * Handles execution errors: RUNNING → FAILED with the error message.
     * Affects 0 rows (and so changes nothing) if a cancel already won.
     */
    private void handleError(Long executionId, Exception error) {
        workFlowExecutionRepository.transition(
            executionId, EXECUTION_RUNNING, ExecutionState.FAILED, LocalDateTime.now(), error.getMessage());
    }
}
