package com.example.myGithubAction.execution_engine.recovery;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.execution_engine.core.WorkerIdentity;
import com.example.myGithubAction.execution_engine.core.WorkspaceManager;
import com.example.myGithubAction.messaging.ExecutionEventPublisher;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StuckExecutionRecoveryRunnerTest {

    private WorkFlowExecutionRepository executionRepository;
    private WorkFlowExecutionStepRepository stepRepository;
    private WorkspaceManager workspaceManager;
    private ExecutionEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        executionRepository = mock(WorkFlowExecutionRepository.class);
        stepRepository = mock(WorkFlowExecutionStepRepository.class);
        workspaceManager = mock(WorkspaceManager.class);
        eventPublisher = mock(ExecutionEventPublisher.class);
    }

    private StuckExecutionRecoveryRunner runner(String configuredWorkerId) {
        return new StuckExecutionRecoveryRunner(
            executionRepository, stepRepository, workspaceManager, new WorkerIdentity(configuredWorkerId), eventPublisher);
    }

    @Test
    void stableWorkerFailsItsOwnLeftoversAtStartup() {
        when(executionRepository.findRunningIdsByWorker("worker-1")).thenReturn(List.of(1L));

        runner("worker-1").run();

        verify(executionRepository).transition(eq(1L), eq(EnumSet.of(ExecutionState.RUNNING)),
            eq(ExecutionState.FAILED), any(), eq("Interrupted by application restart"));
        verify(stepRepository).failRunningSteps(eq(1L), any(), eq("Interrupted by application restart"));
        verify(workspaceManager).deleteWorkspace(1L);
    }

    @Test
    void workerWithoutStableIdDoesNotTouchAnyExecution() {
        // It cannot tell its own leftovers from another live worker's jobs; the reaper handles them.
        runner("").run();

        verifyNoInteractions(executionRepository, stepRepository, workspaceManager);
    }

    @Test
    void onlyQueriesExecutionsOwnedByItself() {
        runner("worker-1").run();

        verify(executionRepository).findRunningIdsByWorker("worker-1");
        verify(executionRepository, never()).findByStatus(any());
        verify(executionRepository, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void recoveryRunnerEmitsForEachFailedExecution() {
        when(executionRepository.findRunningIdsByWorker("worker-1")).thenReturn(List.of(1L, 2L));
        when(executionRepository.transition(eq(1L), any(), any(), any(), any())).thenReturn(1);
        when(executionRepository.transition(eq(2L), any(), any(), any(), any())).thenReturn(0);
        when(stepRepository.failRunningSteps(eq(1L), any(), any())).thenReturn(1);

        runner("worker-1").run();

        verify(eventPublisher).executionChanged(1L);
        verify(eventPublisher).stepsOfExecutionChanged(1L);
        verify(eventPublisher, never()).executionChanged(2L);
        verify(eventPublisher, never()).stepsOfExecutionChanged(2L);
    }
}
