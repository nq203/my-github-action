package com.example.myGithubAction.workflow.service;

import com.example.myGithubAction.auth.exception.ResourceNotFoundException;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import org.springframework.stereotype.Component;

/** Owner-only access to an execution. There is no admin bypass. */
@Component
public class ExecutionAccess {

    /** Same message for "missing" and "not yours", so a response never reveals that an id exists. */
    static final String NOT_FOUND = "Execution not found";

    private final WorkFlowExecutionRepository executions;

    public ExecutionAccess(WorkFlowExecutionRepository executions) {
        this.executions = executions;
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
