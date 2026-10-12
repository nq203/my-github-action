package com.example.myGithubAction.workflow.service;

import com.example.myGithubAction.auth.exception.BadRequestException;
import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.execution_engine.core.ExecutionCancellationRegistry;
import com.example.myGithubAction.execution_engine.core.ExecutionEngine;
import com.example.myGithubAction.workflow.dto.ExecutionStatusUpdateRequest;
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

import java.util.EnumSet;
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
    private ExecutionCancellationRegistry cancellationRegistry;
    private WorkFlowExecutionService service;

    @BeforeEach
    void setUp() {
        executionRepository = mock(WorkFlowExecutionRepository.class);
        executionStepRepository = mock(WorkFlowExecutionStepRepository.class);
        workflowRepository = mock(WorkFlowRepository.class);
        stepRepository = mock(StepRepository.class);
        executionEngine = mock(ExecutionEngine.class);
        cancellationRegistry = new ExecutionCancellationRegistry();

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

        doThrow(new RejectedExecutionException("pool full")).when(executionEngine).startExecution(5L);

        WorkFlowExecutionRequest request = new WorkFlowExecutionRequest();
        request.setWorkflowId(1L);

        service.createExecution(2L, request);

        // Simulate the transaction committing: run the registered synchronization.
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());

        verify(executionRepository).transition(eq(5L), eq(EnumSet.of(ExecutionState.PENDING)),
            eq(ExecutionState.FAILED), any(), contains("rejected"));
    }

    // ---------- cancel / status / delete race guards ----------

    private WorkFlowExecution stored(Long id, ExecutionState status) {
        WorkFlowExecution execution = WorkFlowExecution.builder()
            .id(id).workflowId(1L).userId(2L).status(status).build();
        when(executionRepository.findById(id)).thenReturn(Optional.of(execution));
        when(executionRepository.findByIdWithSteps(id)).thenReturn(Optional.of(execution));
        when(executionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(execution));
        return execution;
    }

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
    }

    @Test
    void cancelSignalsRegistryOnlyAfterCommit() {
        stored(7L, ExecutionState.RUNNING);
        when(executionRepository.transition(eq(7L), eq(ExecutionState.CANCELLABLE),
            eq(ExecutionState.CANCELLED), any(), isNull())).thenReturn(1);

        service.cancelExecution(7L);

        assertFalse(cancellationRegistry.isCancelled(7L), "must not signal before CANCELLED is committed");
        commit();
        assertTrue(cancellationRegistry.isCancelled(7L));
    }

    @Test
    void cancelOfFinishedExecutionIsNoOp() {
        stored(8L, ExecutionState.SUCCESS);
        when(executionRepository.transition(eq(8L), any(), eq(ExecutionState.CANCELLED), any(), any())).thenReturn(0);

        var response = service.cancelExecution(8L);
        commit();

        assertEquals("SUCCESS", response.getStatus());
        assertFalse(cancellationRegistry.isCancelled(8L));
    }

    @Test
    void invalidStatusTransitionIsRejected() {
        stored(9L, ExecutionState.SUCCESS);
        ExecutionStatusUpdateRequest request = new ExecutionStatusUpdateRequest();
        request.setStatus("RUNNING");

        assertThrows(BadRequestException.class, () -> service.updateExecutionStatus(9L, request));
        verify(executionRepository, never()).transition(anyLong(), any(), any(), any(), any());
        verify(executionRepository, never()).claimPending(anyLong(), any());
    }

    @Test
    void statusUpdateThatLosesTheRaceIsRejected() {
        stored(10L, ExecutionState.RUNNING);
        when(executionRepository.transition(eq(10L), any(), eq(ExecutionState.SUCCESS), any(), any())).thenReturn(0);
        ExecutionStatusUpdateRequest request = new ExecutionStatusUpdateRequest();
        request.setStatus("SUCCESS");

        assertThrows(BadRequestException.class, () -> service.updateExecutionStatus(10L, request));
    }

    @Test
    void cannotDeleteActiveExecution() {
        stored(11L, ExecutionState.RUNNING);

        assertThrows(BadRequestException.class, () -> service.deleteExecution(11L));
        verify(executionRepository, never()).delete(any(WorkFlowExecution.class));
        verify(executionStepRepository, never()).deleteAll(any());
    }

    @Test
    void canDeleteTerminalExecution() {
        WorkFlowExecution execution = stored(12L, ExecutionState.FAILED);

        service.deleteExecution(12L);

        verify(executionRepository).delete(execution);
    }
}
