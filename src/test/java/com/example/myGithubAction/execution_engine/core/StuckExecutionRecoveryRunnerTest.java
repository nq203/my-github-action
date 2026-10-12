package com.example.myGithubAction.execution_engine.core;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StuckExecutionRecoveryRunnerTest {

    private WorkFlowExecutionRepository executionRepository;
    private WorkFlowExecutionStepRepository stepRepository;
    private WorkspaceManager workspaceManager;
    private StuckExecutionRecoveryRunner runner;

    @BeforeEach
    void setUp() {
        executionRepository = mock(WorkFlowExecutionRepository.class);
        stepRepository = mock(WorkFlowExecutionStepRepository.class);
        workspaceManager = mock(WorkspaceManager.class);
        runner = new StuckExecutionRecoveryRunner(executionRepository, stepRepository, workspaceManager);
    }

    @Test
    void marksStuckRunningExecutionsAndStepsAsFailed() throws Exception {
        WorkFlowExecution stuckExecution = new WorkFlowExecution();
        stuckExecution.setId(1L);
        stuckExecution.setStatus(ExecutionState.RUNNING);

        WorkFlowExecutionStep stuckStep = new WorkFlowExecutionStep();
        stuckStep.setId(10L);
        stuckStep.setStatus(ExecutionState.RUNNING);

        when(executionRepository.findByStatus(ExecutionState.RUNNING)).thenReturn(List.of(stuckExecution));
        when(stepRepository.findByStatus(ExecutionState.RUNNING)).thenReturn(List.of(stuckStep));

        runner.run();

        verify(executionRepository).transition(eq(1L), eq(EnumSet.of(ExecutionState.RUNNING)),
            eq(ExecutionState.FAILED), any(), eq("Interrupted by application restart"));
        verify(stepRepository).transition(eq(10L), eq(EnumSet.of(ExecutionState.RUNNING)),
            eq(ExecutionState.FAILED), any(), isNull(), eq("Interrupted by application restart"));
        verify(workspaceManager).deleteWorkspace(1L);
        verify(executionRepository, never()).saveAll(any());
        verify(stepRepository, never()).saveAll(any());
    }

    @Test
    void doesNotTouchPendingExecutions() throws Exception {
        when(executionRepository.findByStatus(ExecutionState.RUNNING)).thenReturn(List.of());
        when(stepRepository.findByStatus(ExecutionState.RUNNING)).thenReturn(List.of());

        runner.run();

        verify(executionRepository, never()).findByStatus(ExecutionState.PENDING);
        verify(stepRepository, never()).findByStatus(ExecutionState.PENDING);
        verify(executionRepository, never()).transition(anyLong(), any(), any(), any(), any());
        verify(stepRepository, never()).transition(anyLong(), any(), any(), any(), any(), any());
    }
}
