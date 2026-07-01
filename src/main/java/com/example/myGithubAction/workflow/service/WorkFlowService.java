package com.example.myGithubAction.workflow.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.myGithubAction.auth.exception.ResourceNotFoundException;
import com.example.myGithubAction.auth.exception.BadRequestException;
import com.example.myGithubAction.workflow.dto.CreateWorkFlowRequest;
import com.example.myGithubAction.workflow.dto.CreateWorkFlowStepRequest;
import com.example.myGithubAction.workflow.dto.WorkFlowResponse;
import com.example.myGithubAction.workflow.dto.CreateStepRequest;
import com.example.myGithubAction.workflow.dto.StepResponse;
import com.example.myGithubAction.workflow.entity.WorkFlow;
import com.example.myGithubAction.workflow.entity.Step;
import com.example.myGithubAction.workflow.repository.StepRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowRepository;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class WorkFlowService {
    private final WorkFlowRepository workFlowRepository;
    private final StepRepository stepRepository;

    public WorkFlowService(WorkFlowRepository workFlowRepository, StepRepository stepRepository) {
        this.workFlowRepository = workFlowRepository;
        this.stepRepository = stepRepository;
    }

    // ==================== WORKFLOW METHODS ====================

    /**
     * Create a new workflow
     */
    @Transactional
    public WorkFlowResponse createWorkflow(Long userId, CreateWorkFlowRequest request) {
        WorkFlow workflow = WorkFlow.builder()
                .userId(userId)
                .name(request.getName())
                .description(request.getDescription())
                .build();
        WorkFlow savedWorkflow = workFlowRepository.save(workflow);
        System.out.println("Saved workflow: " + savedWorkflow);
        // create steps for workflow
        if (request.getWorkFlowSteps() != null) {
            for (CreateWorkFlowStepRequest workFlowStepRequest : request.getWorkFlowSteps()) {
                Step step = Step.builder()
                        .workflowId(savedWorkflow.getId())
                        .name(workFlowStepRequest.getName())
                        .stepOrder(workFlowStepRequest.getStepOrder())
                        .type(workFlowStepRequest.getType())
                        .command(workFlowStepRequest.getCommand())
                        .build();
                stepRepository.save(step);
            }
        }
        return mapToWorkFlowResponse(savedWorkflow);
    }

    /**
     * Get workflow by ID
     */
    public WorkFlowResponse getWorkflow(Long id) {
        Optional<WorkFlow> workflow = workFlowRepository.findById(id);
        if (workflow.isEmpty()) {
            throw new ResourceNotFoundException("Workflow not found with id: " + id);
        }

        return mapToWorkFlowResponse(workflow.get());
    }

    /**
     * Get all workflows for a user
     */
    public List<WorkFlowResponse> getWorkflowsByUserId(Long userId) {
        List<WorkFlow> workflows = workFlowRepository.findByUserId(userId);
        return workflows.stream()
                .map(this::mapToWorkFlowResponse)
                .collect(Collectors.toList());
    }

    /**
     * Update a workflow
     */
    @Transactional
    public WorkFlowResponse updateWorkflow(Long id, CreateWorkFlowRequest request) {
        WorkFlow workflow = workFlowRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found with id: " + id));

        workflow.setName(request.getName());
        workflow.setDescription(request.getDescription());

        WorkFlow updatedWorkflow = workFlowRepository.save(workflow);
        return mapToWorkFlowResponse(updatedWorkflow);
    }

    /**
     * Delete a workflow and all its steps
     */
    @Transactional
    public void deleteWorkflow(Long id) {
        WorkFlow workflow = workFlowRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found with id: " + id));

        // Delete all steps associated with this workflow
        List<Step> steps = stepRepository.findByWorkflowId(id);
        stepRepository.deleteAll(steps);

        // Delete workflow
        workFlowRepository.delete(workflow);
    }

    // ==================== STEP METHODS ====================

    /**
     * Add a step to a workflow
     */
    @Transactional
    public StepResponse addStep(CreateStepRequest request) {
        // Verify workflow exists
        workFlowRepository.findById(request.getWorkflowId())
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found with id: " + request.getWorkflowId()));

        Step step = Step.builder()
                .workflowId(request.getWorkflowId())
                .name(request.getName())
                .stepOrder(request.getStepOrder())
                .type(request.getType())
                .command(request.getCommand())
                .build();

        Step savedStep = stepRepository.save(step);
        return mapToStepResponse(savedStep);
    }

    /**
     * Get all steps for a workflow
     */
    public List<StepResponse> getStepsByWorkflowId(Long workflowId) {
        List<Step> steps = stepRepository.findByWorkflowId(workflowId);
        return steps.stream()
                .map(this::mapToStepResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get a specific step
     */
    public StepResponse getStep(Long stepId) {
        Step step = stepRepository.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Step not found with id: " + stepId));
        return mapToStepResponse(step);
    }

    /**
     * Update a step
     */
    @Transactional
    public StepResponse updateStep(Long stepId, CreateStepRequest request) {
        Step step = stepRepository.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Step not found with id: " + stepId));

        step.setName(request.getName());
        step.setStepOrder(request.getStepOrder());
        step.setType(request.getType());
        step.setCommand(request.getCommand());

        Step updatedStep = stepRepository.save(step);
        return mapToStepResponse(updatedStep);
    }

    /**
     * Remove (delete) a step
     */
    @Transactional
    public void removeStep(Long stepId) {
        Step step = stepRepository.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Step not found with id: " + stepId));
        stepRepository.delete(step);
    }

    /**
     * Move a step to a new position
     */
    @Transactional
    public List<StepResponse> moveStep(Long stepId, Integer newPosition) {
        Step step = stepRepository.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Step not found with id: " + stepId));

        Long workflowId = step.getWorkflowId();
        List<Step> allSteps = stepRepository.findByWorkflowId(workflowId);

        // Ensure new position is valid
        if (newPosition < 0 || newPosition >= allSteps.size()) {
            throw new BadRequestException("Invalid position: " + newPosition);
        }

        // Update step orders
        allSteps.sort((s1, s2) -> Integer.compare(s1.getStepOrder(), s2.getStepOrder()));

        // Remove step from current position
        allSteps.remove(step);

        // Insert at new position
        allSteps.add(newPosition, step);

        // Reorder all steps
        for (int i = 0; i < allSteps.size(); i++) {
            allSteps.get(i).setStepOrder(i);
        }

        // Save all steps
        stepRepository.saveAll(allSteps);

        return allSteps.stream()
                .map(this::mapToStepResponse)
                .collect(Collectors.toList());
    }

    /**
     * Clone a step
     */
    @Transactional
    public StepResponse cloneStep(Long stepId) {
        Step originalStep = stepRepository.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Step not found with id: " + stepId));

        // Get the max order for this workflow and add 1
        List<Step> allSteps = stepRepository.findByWorkflowId(originalStep.getWorkflowId());
        Integer maxOrder = allSteps.stream()
                .map(Step::getStepOrder)
                .max(Integer::compareTo)
                .orElse(-1);

        Step clonedStep = Step.builder()
                .workflowId(originalStep.getWorkflowId())
                .name(originalStep.getName() + " (copy)")
                .stepOrder(maxOrder + 1)
                .type(originalStep.getType())
                .command(originalStep.getCommand())
                .build();

        Step savedStep = stepRepository.save(clonedStep);
        return mapToStepResponse(savedStep);
    }

    // ==================== HELPER METHODS ====================

    private WorkFlowResponse mapToWorkFlowResponse(WorkFlow workflow) {
        return WorkFlowResponse.builder()
                .id(workflow.getId())
                .userId(workflow.getUserId())
                .name(workflow.getName())
                .description(workflow.getDescription())
                .createdAt(workflow.getCreatedAt())
                .updatedAt(workflow.getUpdatedAt())
                .build();
    }

    private StepResponse mapToStepResponse(Step step) {
        return StepResponse.builder()
                .id(step.getId())
                .workflowId(step.getWorkflowId())
                .name(step.getName())
                .type(step.getType())
                .command(step.getCommand())
                .createdAt(step.getCreatedAt())
                .updatedAt(step.getUpdatedAt())
                .build();
    }
}
