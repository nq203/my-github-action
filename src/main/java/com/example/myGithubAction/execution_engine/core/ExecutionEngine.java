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
import java.util.concurrent.CompletableFuture;

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
    private final WorkerIdentity workerIdentity;

    public ExecutionEngine(
            WorkFlowExecutionRepository workFlowExecutionRepository,
            WorkFlowExecutionStepRepository workFlowExecutionStepRepository,
            StepExecutor stepExecutor,
            LogManager logManager,
            ErrorHandler errorHandler,
            ExecutionCancellationRegistry cancellationRegistry,
            WorkspaceManager workspaceManager,
            WorkerIdentity workerIdentity) {
        this.workFlowExecutionRepository = workFlowExecutionRepository;
        this.workFlowExecutionStepRepository = workFlowExecutionStepRepository;
        this.stepExecutor = stepExecutor;
        this.logManager = logManager;
        this.errorHandler = errorHandler;
        this.cancellationRegistry = cancellationRegistry;
        this.workspaceManager = workspaceManager;
        this.workerIdentity = workerIdentity;
    }

    /**
     * Claims an execution (atomic PENDING → RUNNING, owned by this worker).
     *
     * Synchronous so a queue consumer can acknowledge its message only after it
     * owns the execution. Returns false if the claim was lost: cancelled while
     * PENDING, or already claimed (duplicate delivery / another worker).
     *
     * A won claim is registered immediately, so the heartbeat covers the execution
     * even while it waits for a free executor thread.
     */
    public boolean claim(Long executionId) {
        boolean claimed = workFlowExecutionRepository
            .claimPending(executionId, workerIdentity.id(), LocalDateTime.now()) > 0;
        if (claimed) {
            cancellationRegistry.register(executionId);
        }
        return claimed;
    }

    /**
     * Claims and runs an execution on the workflow executor (in-process dispatch).
     *
     * Runs in a separate thread so the calling HTTP request
     * can return immediately with the execution ID.
     * If the claim affects 0 rows this call does nothing at all.
     *
     * @param executionId the execution ID from database
     */
    @Async("workflowExecutor")
    public void startExecution(Long executionId) {
        if (!claim(executionId)) {
            return;
        }
        try {
            runClaimedExecution(executionId);
        } finally {
            cancellationRegistry.unregister(executionId);
        }
    }

    /**
     * Runs an execution this caller has already claimed via {@link #claim(Long)}.
     *
     * The returned future completes when the execution has finished, so the caller
     * can release whatever capacity it reserved for it.
     */
    @Async("workflowExecutor")
    public CompletableFuture<Void> runClaimed(Long executionId) {
        try {
            runClaimedExecution(executionId);
            return CompletableFuture.completedFuture(null);
        } finally {
            cancellationRegistry.unregister(executionId);
        }
    }

    /** Fails a claimed (RUNNING) execution that could not be run. No-op if it already finished or was cancelled. */
    public void failClaimed(Long executionId, String message) {
        LocalDateTime now = LocalDateTime.now();
        workFlowExecutionRepository.transition(executionId, EXECUTION_RUNNING, ExecutionState.FAILED, now, message);
        workFlowExecutionStepRepository.failRunningSteps(executionId, now, message);
        cancellationRegistry.unregister(executionId);
    }

    private void runClaimedExecution(Long executionId) {
        boolean workspaceCreated = false;
        try {
            try {
                workspaceManager.createWorkspace(executionId);
                workspaceCreated = true;
            } catch (Exception e) {
                handleError(executionId, e);
                return;
            }

            // Load steps in order
            List<WorkFlowExecutionStep> steps = workFlowExecutionStepRepository.findByExecutionIdOrderByStepOrder(executionId);

            // Execute each step sequentially, stopping on cancel or failure
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

            handleCompletion(executionId);
        } finally {
            if (workspaceCreated) {
                workspaceManager.deleteWorkspace(executionId);
            }
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

                // 3. Capture logs (command output was already streamed line by line by StepExecutor)
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
