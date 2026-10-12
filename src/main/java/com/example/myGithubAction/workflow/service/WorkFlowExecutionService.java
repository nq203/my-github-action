package com.example.myGithubAction.workflow.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.myGithubAction.auth.exception.BadRequestException;
import com.example.myGithubAction.auth.exception.ResourceNotFoundException;
import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.dto.WorkFlowExecutionRequest;
import com.example.myGithubAction.workflow.dto.WorkFlowExecutionResponse;
import com.example.myGithubAction.workflow.dto.WorkFlowExecutionStepResponse;
import com.example.myGithubAction.workflow.dto.ExecutionStatusUpdateRequest;
import com.example.myGithubAction.workflow.entity.*;
import com.example.myGithubAction.workflow.repository.WorkFlowRepository;
import com.example.myGithubAction.workflow.repository.StepRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import com.example.myGithubAction.execution_engine.core.ExecutionEngine;
import com.example.myGithubAction.execution_engine.core.ExecutionCancellationRegistry;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

@Service
public class WorkFlowExecutionService {
    private final WorkFlowExecutionRepository executionRepository;
    private final WorkFlowExecutionStepRepository executionStepRepository;
    private final WorkFlowRepository workflowRepository;
    private final StepRepository stepRepository;
    private final ExecutionEngine executionEngine;
    private final ExecutionCancellationRegistry cancellationRegistry;

    public WorkFlowExecutionService(
            WorkFlowExecutionRepository executionRepository,
            WorkFlowExecutionStepRepository executionStepRepository,
            WorkFlowRepository workflowRepository,
            StepRepository stepRepository,
            ExecutionEngine executionEngine,
            ExecutionCancellationRegistry cancellationRegistry) {
        this.executionRepository = executionRepository;
        this.executionStepRepository = executionStepRepository;
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.executionEngine = executionEngine;
        this.cancellationRegistry = cancellationRegistry;
    }

    // ==================== WORKFLOW EXECUTION METHODS ====================

    /**
     * Create a new workflow execution and trigger ExecutionEngine to run it.
     *
     * This method:
     * 1. Validates the workflow exists
     * 2. Creates a WorkFlowExecution record in PENDING status
     * 3. Creates WorkFlowExecutionStep records for each step
     * 4. After transaction commits, triggers ExecutionEngine asynchronously
     *
     * The startExecution call is deferred to after-commit so the
     * ExecutionEngine always sees the persisted execution and steps.
     *
     * @param userId the user ID who triggered the execution
     * @param request the execution request containing workflowId
     * @return the created execution response
     * @throws ResourceNotFoundException if workflow not found
     */
    @Transactional
    public WorkFlowExecutionResponse createExecution(Long userId, WorkFlowExecutionRequest request) {
        // Verify workflow exists
        workflowRepository.findById(request.getWorkflowId())
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found with id: " + request.getWorkflowId()));

        // Create execution
        WorkFlowExecution execution = WorkFlowExecution.builder()
                .workflowId(request.getWorkflowId())
                .userId(userId)
                .status(ExecutionState.PENDING)
                .build();

        WorkFlowExecution savedExecution = executionRepository.save(execution);

        // Create execution steps from workflow steps
        List<Step> steps = stepRepository.findByWorkflowId(request.getWorkflowId());
        steps.sort((s1, s2) -> Integer.compare(s1.getStepOrder(), s2.getStepOrder()));

        for (Step step : steps) {
            WorkFlowExecutionStep executionStep = WorkFlowExecutionStep.builder()
                    .executionId(savedExecution.getId())
                    .stepId(step.getId())
                    .status(ExecutionState.PENDING)
                    .stepOrder(step.getStepOrder())
                    .build();
            executionStepRepository.save(executionStep);
        }

        // Trigger ExecutionEngine AFTER the transaction commits.
        // This guarantees the execution and steps are visible when
        // the async engine reads them from the database.
        final Long executionId = savedExecution.getId();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        executionEngine.startExecution(executionId);
                    } catch (RejectedExecutionException e) {
                        markExecutionRejected(executionId);
                    }
                }
            }
        );

        return mapToExecutionResponse(savedExecution);
    }

    /**
     * Marks an execution FAILED when the workflow executor's thread pool
     * and queue are both full, so a rejected submission doesn't leave the
     * execution silently stuck PENDING forever.
     *
     * @param executionId the execution that could not be submitted
     */
    private void markExecutionRejected(Long executionId) {
        executionRepository.transition(
                executionId, EnumSet.of(ExecutionState.PENDING), ExecutionState.FAILED,
                LocalDateTime.now(), "Execution rejected: thread pool and queue are full");
    }

    /**
     * Get execution by ID with all steps
     */
    public WorkFlowExecutionResponse getExecution(Long id) {
        WorkFlowExecution execution = executionRepository.findByIdWithSteps(id)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found with id: " + id));
        return mapToExecutionResponse(execution);
    }

    /**
     * Get all executions for a workflow with all steps
     */
    public List<WorkFlowExecutionResponse> getExecutionsByWorkflowId(Long workflowId) {
        List<WorkFlowExecution> executions = executionRepository.findByWorkflowIdWithSteps(workflowId);
        return executions.stream()
                .map(this::mapToExecutionResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get all executions for a user with all steps
     */
    public List<WorkFlowExecutionResponse> getExecutionsByUserId(Long userId) {
        List<WorkFlowExecution> executions = executionRepository.findByUserIdWithSteps(userId);
        return executions.stream()
                .map(this::mapToExecutionResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get executions by status with all steps
     */
    public List<WorkFlowExecutionResponse> getExecutionsByStatus(ExecutionState status) {
        List<WorkFlowExecution> executions = executionRepository.findByStatusWithSteps(status);
        return executions.stream()
                .map(this::mapToExecutionResponse)
                .collect(Collectors.toList());
    }

    /**
     * Update execution status.
     *
     * The requested transition must be valid per {@link ExecutionState} and is applied
     * with a compare-and-set, so it can never overwrite a state the engine or a cancel
     * has moved the execution to in the meantime.
     */
    @Transactional
    public WorkFlowExecutionResponse updateExecutionStatus(Long executionId, ExecutionStatusUpdateRequest request) {
        WorkFlowExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found with id: " + executionId));

        ExecutionState current = execution.getStatus();
        ExecutionState target = parseState(request.getStatus());
        if (!current.canTransitionTo(target)) {
            throw new BadRequestException("Invalid status transition: " + current + " -> " + target);
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = ExecutionState.RUNNING.equals(target)
                ? executionRepository.claimPending(executionId, now)
                : executionRepository.transition(
                        executionId, EnumSet.of(current), target, now, request.getErrorMessage());
        if (updated == 0) {
            throw new BadRequestException("Execution status changed concurrently, reload and retry");
        }
        if (request.getLogs() != null) {
            executionRepository.updateLogs(executionId, request.getLogs(), now);
        }

        return getExecution(executionId);
    }

    /**
     * Cancel an execution.
     *
     * PENDING|RUNNING → CANCELLED as a compare-and-set. If the execution already reached a
     * terminal state the call is an idempotent no-op that returns the current state
     * (a finished execution is never overwritten with CANCELLED).
     */
    @Transactional
    public WorkFlowExecutionResponse cancelExecution(Long executionId) {
        executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found with id: " + executionId));

        int updated = executionRepository.transition(
                executionId, ExecutionState.CANCELLABLE, ExecutionState.CANCELLED, LocalDateTime.now(), null);
        if (updated > 0) {
            // Signal the in-memory registry only once CANCELLED is committed, so the engine
            // never observes the flag before the DB state.
            runAfterCommit(() -> cancellationRegistry.cancel(executionId));
        }
        return getExecution(executionId);
    }

    /**
     * Delete an execution and all its steps.
     *
     * Only terminal executions can be deleted. The row lock makes the status check
     * race-free: a concurrent claim/transition waits until this transaction ends.
     */
    @Transactional
    public void deleteExecution(Long executionId) {
        WorkFlowExecution execution = executionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found with id: " + executionId));

        if (!execution.getStatus().isTerminal()) {
            throw new BadRequestException(
                    "Cannot delete execution in status " + execution.getStatus() + ", cancel it first");
        }

        // Delete all execution steps
        List<WorkFlowExecutionStep> executionSteps = executionStepRepository.findByExecutionId(executionId);
        executionStepRepository.deleteAll(executionSteps);

        // Delete execution
        executionRepository.delete(execution);
    }

    // ==================== EXECUTION STEP METHODS ====================

    /**
     * Get execution steps for an execution
     */
    public List<WorkFlowExecutionStepResponse> getExecutionSteps(Long executionId) {
        List<WorkFlowExecutionStep> steps = executionStepRepository.findByExecutionIdOrderByStepOrder(executionId);
        return steps.stream()
                .map(this::mapToExecutionStepResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get a specific execution step
     */
    public WorkFlowExecutionStepResponse getExecutionStep(Long stepId) {
        WorkFlowExecutionStep step = executionStepRepository.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution step not found with id: " + stepId));
        return mapToExecutionStepResponse(step);
    }

    /**
     * Update execution step status (validated, compare-and-set; see updateExecutionStatus).
     */
    @Transactional
    public WorkFlowExecutionStepResponse updateExecutionExecutionState(Long stepId, ExecutionStatusUpdateRequest request) {
        WorkFlowExecutionStep step = executionStepRepository.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution step not found with id: " + stepId));

        ExecutionState current = step.getStatus();
        ExecutionState target = parseState(request.getStatus());
        if (!current.canTransitionTo(target)) {
            throw new BadRequestException("Invalid status transition: " + current + " -> " + target);
        }

        LocalDateTime now = LocalDateTime.now();
        int updated;
        if (ExecutionState.RUNNING.equals(target)) {
            updated = executionStepRepository.markRunning(stepId, now);
        } else {
            Long duration = step.getStartedAt() != null
                    ? java.time.temporal.ChronoUnit.MILLIS.between(step.getStartedAt(), now)
                    : null;
            updated = executionStepRepository.transition(
                    stepId, EnumSet.of(current), target, now, duration, request.getErrorMessage());
        }
        if (updated == 0) {
            throw new BadRequestException("Execution step status changed concurrently, reload and retry");
        }
        if (request.getLogs() != null) {
            executionStepRepository.updateLogs(stepId, request.getLogs(), now);
        }

        return getExecutionStep(stepId);
    }

    /**
     * Get execution steps by status
     */
    public List<WorkFlowExecutionStepResponse> getExecutionStepsByStatus(ExecutionState status) {
        List<WorkFlowExecutionStep> steps = executionStepRepository.findByStatus(status);
        return steps.stream()
                .map(this::mapToExecutionStepResponse)
                .collect(Collectors.toList());
    }

    // ==================== HELPER METHODS ====================

    private ExecutionState parseState(String status) {
        try {
            return ExecutionState.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Unknown execution status: " + status);
        }
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private WorkFlowExecutionResponse mapToExecutionResponse(WorkFlowExecution execution) {
        return mapToExecutionResponse(execution, true);
    }

    private WorkFlowExecutionResponse mapToExecutionResponse(WorkFlowExecution execution, boolean includeSteps) {
        WorkFlowExecutionResponse.WorkFlowExecutionResponseBuilder builder = WorkFlowExecutionResponse.builder()
                .id(execution.getId())
                .workflowId(execution.getWorkflowId())
                .userId(execution.getUserId())
                .status(execution.getStatus().toString())
                .startedAt(execution.getStartedAt())
                .endedAt(execution.getEndedAt())
                .logs(execution.getLogs())
                .errorMessage(execution.getErrorMessage())
                .createdAt(execution.getCreatedAt())
                .updatedAt(execution.getUpdatedAt());

        if (includeSteps) {
            // Steps are already loaded via FETCH JOIN or from entity relationship
            List<WorkFlowExecutionStepResponse> stepResponses = new ArrayList<>();
            if (execution.getExecutionSteps() != null) {
                stepResponses = execution.getExecutionSteps().stream()
                        .map(this::mapToExecutionStepResponse)
                        .collect(Collectors.toList());
            }
            builder.steps(stepResponses);
        }

        return builder.build();
    }

    private WorkFlowExecutionStepResponse mapToExecutionStepResponse(WorkFlowExecutionStep step) {
        return WorkFlowExecutionStepResponse.builder()
                .id(step.getId())
                .executionId(step.getExecutionId())
                .stepId(step.getStepId())
                .status(step.getStatus().toString())
                .stepOrder(step.getStepOrder())
                .startedAt(step.getStartedAt())
                .endedAt(step.getEndedAt())
                .logs(step.getLogs())
                .errorMessage(step.getErrorMessage())
                .duration(step.getDuration())
                .createdAt(step.getCreatedAt())
                .updatedAt(step.getUpdatedAt())
                .build();
    }
}
