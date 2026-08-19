package com.example.myGithubAction.workflow.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class WorkFlowExecutionService {
    private final WorkFlowExecutionRepository executionRepository;
    private final WorkFlowExecutionStepRepository executionStepRepository;
    private final WorkFlowRepository workflowRepository;
    private final StepRepository stepRepository;
    private final ExecutionEngine executionEngine;

    public WorkFlowExecutionService(
            WorkFlowExecutionRepository executionRepository,
            WorkFlowExecutionStepRepository executionStepRepository,
            WorkFlowRepository workflowRepository,
            StepRepository stepRepository,
            ExecutionEngine executionEngine) {
        this.executionRepository = executionRepository;
        this.executionStepRepository = executionStepRepository;
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.executionEngine = executionEngine;
    }

    // ==================== WORKFLOW EXECUTION METHODS ====================

    /**
     * Create a new workflow execution and trigger ExecutionEngine to run it.
     *
     * This method:
     * 1. Validates the workflow exists
     * 2. Creates a WorkFlowExecution record in PENDING status
     * 3. Creates WorkFlowExecutionStep records for each step
     * 4. Triggers ExecutionEngine to start the execution asynchronously
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

        // Trigger ExecutionEngine to run the execution
        executionEngine.startExecution(savedExecution.getId());

        return mapToExecutionResponse(savedExecution);
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
     * Update execution status
     */
    @Transactional
    public WorkFlowExecutionResponse updateExecutionStatus(Long executionId, ExecutionStatusUpdateRequest request) {
        WorkFlowExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found with id: " + executionId));

        // Parse status from request
        ExecutionState status = ExecutionState.valueOf(request.getStatus());
        execution.setStatus(status);
        execution.setLogs(request.getLogs());
        execution.setErrorMessage(request.getErrorMessage());

        // Set time based on status
        if (ExecutionState.RUNNING.equals(status)) {
            execution.setStartedAt(LocalDateTime.now());
        }
        if (ExecutionState.SUCCESS.equals(status) || ExecutionState.FAILED.equals(status) || ExecutionState.CANCELLED.equals(status)) {
            execution.setEndedAt(LocalDateTime.now());
        }

        WorkFlowExecution updatedExecution = executionRepository.save(execution);
        return mapToExecutionResponse(updatedExecution);
    }

    /**
     * Cancel an execution
     */
    @Transactional
    public WorkFlowExecutionResponse cancelExecution(Long executionId) {
        WorkFlowExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found with id: " + executionId));

        execution.setStatus(ExecutionState.CANCELLED);
        execution.setEndedAt(LocalDateTime.now());

        WorkFlowExecution updatedExecution = executionRepository.save(execution);
        return mapToExecutionResponse(updatedExecution);
    }

    /**
     * Delete an execution and all its steps
     */
    @Transactional
    public void deleteExecution(Long executionId) {
        WorkFlowExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found with id: " + executionId));

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
     * Update execution step status
     */
    @Transactional
    public WorkFlowExecutionStepResponse updateExecutionExecutionState(Long stepId, ExecutionStatusUpdateRequest request) {
        WorkFlowExecutionStep step = executionStepRepository.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution step not found with id: " + stepId));

        // Parse status from request
        ExecutionState status = ExecutionState.valueOf(request.getStatus());
        step.setStatus(status);
        step.setLogs(request.getLogs());
        step.setErrorMessage(request.getErrorMessage());

        // Set time based on status
        if (ExecutionState.RUNNING.equals(status)) {
            step.setStartedAt(LocalDateTime.now());
        }
        if (ExecutionState.SUCCESS.equals(status) || ExecutionState.FAILED.equals(status) || ExecutionState.CANCELLED.equals(status)) {
            step.setEndedAt(LocalDateTime.now());

            // Calculate duration if both times are set
            if (step.getStartedAt() != null && step.getEndedAt() != null) {
                step.setDuration(java.time.temporal.ChronoUnit.MILLIS.between(step.getStartedAt(), step.getEndedAt()));
            }
        }

        WorkFlowExecutionStep updatedStep = executionStepRepository.save(step);
        return mapToExecutionStepResponse(updatedStep);
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
