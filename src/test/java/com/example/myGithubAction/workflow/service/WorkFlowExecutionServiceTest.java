package com.example.myGithubAction.workflow.service;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.execution_engine.core.ExecutionCancellationRegistry;
import com.example.myGithubAction.execution_engine.core.ExecutionEngine;
import com.example.myGithubAction.workflow.dto.WorkFlowExecutionRequest;
import com.example.myGithubAction.workflow.entity.WorkFlow;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.repository.StepRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkFlowExecutionServiceTest {

    private WorkFlowExecutionRepository executionRepository;
    private WorkFlowExecutionStepRepository executionStepRepository;
    private WorkFlowRepository workflowRepository;
    private StepRepository stepRepository;
    private ExecutionEngine executionEngine;
    private WorkFlowExecutionService service;

    @BeforeEach
    void setUp() {
        executionRepository = mock(WorkFlowExecutionRepository.class);
        executionStepRepository = mock(WorkFlowExecutionStepRepository.class);
        workflowRepository = mock(WorkFlowRepository.class);
        stepRepository = mock(StepRepository.class);
        executionEngine = mock(ExecutionEngine.class);
        ExecutionCancellationRegistry cancellationRegistry = new ExecutionCancellationRegistry();

        service = new WorkFlowExecutionService(
            executionRepository, executionStepRepository, workflowRepository,
            stepRepository, executionEngine, cancellationRegistry);

        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void marksExecutionFailedWhenExecutorRejectsSubmission() {
        when(workflowRepository.findById(1L)).thenReturn(Optional.of(WorkFlow.builder().id(1L).build()));
        when(stepRepository.findByWorkflowId(1L)).thenReturn(new java.util.ArrayList<>());

        WorkFlowExecution savedExecution = WorkFlowExecution.builder()
            .id(5L).workflowId(1L).userId(2L).status(ExecutionState.PENDING).build();
        when(executionRepository.save(any(WorkFlowExecution.class))).thenReturn(savedExecution);
        when(executionRepository.findById(5L)).thenReturn(Optional.of(savedExecution));

        doThrow(new RejectedExecutionException("pool full")).when(executionEngine).startExecution(5L);

        WorkFlowExecutionRequest request = new WorkFlowExecutionRequest();
        request.setWorkflowId(1L);

        service.createExecution(2L, request);

        // Simulate the transaction committing: run the registered synchronization.
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());

        assertEquals(ExecutionState.FAILED, savedExecution.getStatus());
        assertNotNull(savedExecution.getErrorMessage());
        assertNotNull(savedExecution.getEndedAt());
        verify(executionRepository).save(savedExecution);
    }
}
