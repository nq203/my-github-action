package com.example.myGithubAction.workflow.service;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.messaging.ExecutionDispatcher;
import com.example.myGithubAction.workflow.dto.WorkFlowExecutionRequest;
import com.example.myGithubAction.workflow.entity.Step;
import com.example.myGithubAction.workflow.entity.WorkFlow;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.repository.StepRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * The after-commit bookkeeping runs in a REAL transaction here (the unit test with mocks
 * could not see that a plain @Transactional call inside afterCommit fails).
 */
@SpringBootTest
class ExecutionDispatchRejectionTest {

    @MockBean
    private ExecutionDispatcher dispatcher;
    @Autowired
    private WorkFlowExecutionService service;
    @Autowired
    private WorkFlowRepository workflowRepository;
    @Autowired
    private StepRepository stepRepository;
    @Autowired
    private WorkFlowExecutionRepository executionRepository;

    private Long workflowWithStep() {
        WorkFlow workflow = workflowRepository.save(WorkFlow.builder().userId(1L).name("wf").build());
        stepRepository.save(Step.builder().workflowId(workflow.getId()).name("s").stepOrder(0).command("echo hi").build());
        return workflow.getId();
    }

    private Long create(Long workflowId) {
        return service.createExecution(1L, WorkFlowExecutionRequest.builder().workflowId(workflowId).build()).getId();
    }

    @Test
    void rejectedDispatchMarksTheCommittedExecutionFailed() {
        doThrow(new RejectedExecutionException("broker down")).when(dispatcher).dispatch(anyLong());

        Long executionId = create(workflowWithStep());

        WorkFlowExecution stored = executionRepository.findById(executionId).orElseThrow();
        assertEquals(ExecutionState.FAILED, stored.getStatus());
        assertEquals("Execution rejected: broker down", stored.getErrorMessage());
        assertNotNull(stored.getEndedAt());
        assertNull(stored.getLastDispatchedAt());
    }

    @Test
    void acceptedDispatchRecordsWhenItWasPublished() {
        Long executionId = create(workflowWithStep());

        WorkFlowExecution stored = executionRepository.findById(executionId).orElseThrow();
        assertEquals(ExecutionState.PENDING, stored.getStatus());
        assertNotNull(stored.getLastDispatchedAt());
    }
}
