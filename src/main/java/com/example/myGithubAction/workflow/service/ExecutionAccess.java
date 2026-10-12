package com.example.myGithubAction.workflow.service;

import com.example.myGithubAction.auth.exception.ResourceNotFoundException;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.springframework.stereotype.Component;

/** Owner-only access to an execution. There is no admin bypass. */
@Component
public class ExecutionAccess {

    /** Same message for "missing" and "not yours", so a response never reveals that an id exists. */
    static final String NOT_FOUND = "Execution not found";

    private final WorkFlowExecutionRepository executions;

    private final WorkFlowExecutionStepRepository executionSteps;

    public ExecutionAccess(WorkFlowExecutionRepository executions, WorkFlowExecutionStepRepository executionSteps) {
        this.executions = executions;
        this.executionSteps = executionSteps;
    }

    /**
     * Same as {@link #requireOwner} for a step id: a missing step and a step of someone else's execution
     * give the identical 404.
     */
    public void requireStepOwner(Long stepId, Long userId) {
        if (stepId == null) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        Long executionId = executionSteps.findById(stepId)
                .map(step -> step.getExecutionId())
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        requireOwner(executionId, userId);
    }

    /** @throws ResourceNotFoundException (404) when the execution is missing or owned by someone else */
    public WorkFlowExecution requireOwner(Long executionId, Long userId) {
        if (executionId == null || userId == null) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        return executions.findById(executionId)
                .filter(e -> userId.equals(e.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }
}
