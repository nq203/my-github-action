package com.example.myGithubAction.workflow.service;

import com.example.myGithubAction.auth.exception.BadRequestException;
import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.messaging.ExecutionDispatcher;
import com.example.myGithubAction.messaging.ExecutionEventPublisher;
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
import org.springframework.transaction.PlatformTransactionManager;
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
    private ExecutionDispatcher executionDispatcher;
    private ExecutionEventPublisher eventPublisher;
    private WorkFlowExecutionService service;

    @BeforeEach
    void setUp() {
        executionRepository = mock(WorkFlowExecutionRepository.class);
        executionStepRepository = mock(WorkFlowExecutionStepRepository.class);
        workflowRepository = mock(WorkFlowRepository.class);
        stepRepository = mock(StepRepository.class);
        executionDispatcher = mock(ExecutionDispatcher.class);

        eventPublisher = mock(ExecutionEventPublisher.class);
        service = new WorkFlowExecutionService(
            executionRepository, executionStepRepository, workflowRepository,
            stepRepository, executionDispatcher, eventPublisher, mock(PlatformTransactionManager.class));

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

        doThrow(new RejectedExecutionException("pool full")).when(executionDispatcher).dispatch(5L);

        WorkFlowExecutionRequest request = new WorkFlowExecutionRequest();
        request.setWorkflowId(1L);

        service.createExecution(2L, request);

        // Simulate the transaction committing: run the registered synchronization.
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());

        verify(executionRepository).transition(eq(5L), eq(EnumSet.of(ExecutionState.PENDING)),
            eq(ExecutionState.FAILED), any(), contains("pool full"));
        verify(executionRepository, never()).markDispatched(anyLong(), any());
    }

    @Test
    void dispatchesOnlyAfterCommit() {
        when(workflowRepository.findById(1L)).thenReturn(Optional.of(WorkFlow.builder().id(1L).build()));
        when(stepRepository.findByWorkflowId(1L)).thenReturn(new java.util.ArrayList<>());
        when(executionRepository.save(any(WorkFlowExecution.class))).thenReturn(
            WorkFlowExecution.builder().id(6L).workflowId(1L).userId(2L).status(ExecutionState.PENDING).build());
        WorkFlowExecutionRequest request = new WorkFlowExecutionRequest();
        request.setWorkflowId(1L);

        service.createExecution(2L, request);

        verify(executionDispatcher, never()).dispatch(anyLong());
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(executionDispatcher).dispatch(6L);
        verify(executionRepository).markDispatched(eq(6L), any());
        verify(executionRepository, never()).transition(anyLong(), any(), any(), any(), any());
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

        verify(executionDispatcher, never()).requestCancel(anyLong());
        commit();
        verify(executionDispatcher).requestCancel(7L);
    }

    @Test
    void cancelOfFinishedExecutionIsNoOp() {
        stored(8L, ExecutionState.SUCCESS);
        when(executionRepository.transition(eq(8L), any(), eq(ExecutionState.CANCELLED), any(), any())).thenReturn(0);

        var response = service.cancelExecution(8L);
        commit();

        assertEquals("SUCCESS", response.getStatus());
        verify(executionDispatcher, never()).requestCancel(anyLong());
    }

    @Test
    void invalidStatusTransitionIsRejected() {
        stored(9L, ExecutionState.SUCCESS);
        ExecutionStatusUpdateRequest request = new ExecutionStatusUpdateRequest();
        request.setStatus("RUNNING");

        assertThrows(BadRequestException.class, () -> service.updateExecutionStatus(9L, request));
        verify(executionRepository, never()).transition(anyLong(), any(), any(), any(), any());
        verify(executionRepository, never()).claimPending(anyLong(), any(), any());
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

    // ---------- live-monitor events ----------

    @Test
    void cancelEmitsAfterCommitOnlyWhenUpdated() {
        stored(13L, ExecutionState.RUNNING);
        when(executionRepository.transition(eq(13L), eq(ExecutionState.CANCELLABLE),
            eq(ExecutionState.CANCELLED), any(), isNull())).thenReturn(1);

        service.cancelExecution(13L);

        verifyNoInteractions(eventPublisher);
        commit();
        verify(eventPublisher).executionChanged(13L);
    }

    @Test
    void cancelThatUpdatedNothingEmitsNothing() {
        stored(14L, ExecutionState.SUCCESS);
        when(executionRepository.transition(eq(14L), any(), eq(ExecutionState.CANCELLED), any(), any())).thenReturn(0);

        service.cancelExecution(14L);
        commit();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void patchStatusEmitsEvents() {
        stored(15L, ExecutionState.RUNNING);
        when(executionRepository.transition(eq(15L), any(), eq(ExecutionState.SUCCESS), any(), any())).thenReturn(1);
        ExecutionStatusUpdateRequest request = new ExecutionStatusUpdateRequest();
        request.setStatus("SUCCESS");

        service.updateExecutionStatus(15L, request);

        verifyNoInteractions(eventPublisher);
        commit();
        verify(eventPublisher).executionChanged(15L);
    }

    @Test
    void patchStatusLosingTheRaceEmitsNothing() {
        stored(16L, ExecutionState.RUNNING);
        when(executionRepository.transition(eq(16L), any(), eq(ExecutionState.SUCCESS), any(), any())).thenReturn(0);
        ExecutionStatusUpdateRequest request = new ExecutionStatusUpdateRequest();
        request.setStatus("SUCCESS");

        assertThrows(BadRequestException.class, () -> service.updateExecutionStatus(16L, request));
        commit();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void patchStepStatusEmitsStepsOfExecutionChanged() {
        var step = new com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep();
        step.setId(30L);
        step.setExecutionId(17L);
        step.setStatus(ExecutionState.RUNNING);
        when(executionStepRepository.findById(30L)).thenReturn(Optional.of(step));
        when(executionStepRepository.transition(eq(30L), any(), eq(ExecutionState.SUCCESS), any(), any(), any()))
            .thenReturn(1);
        ExecutionStatusUpdateRequest request = new ExecutionStatusUpdateRequest();
        request.setStatus("SUCCESS");

        service.updateExecutionExecutionState(30L, request);

        verifyNoInteractions(eventPublisher);
        commit();
        verify(eventPublisher).stepsOfExecutionChanged(17L);
    }
}
