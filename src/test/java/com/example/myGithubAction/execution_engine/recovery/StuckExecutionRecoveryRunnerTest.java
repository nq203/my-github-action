package com.example.myGithubAction.execution_engine.recovery;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.execution_engine.core.WorkerIdentity;
import com.example.myGithubAction.execution_engine.core.WorkspaceManager;
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

    @BeforeEach
    void setUp() {
        executionRepository = mock(WorkFlowExecutionRepository.class);
        stepRepository = mock(WorkFlowExecutionStepRepository.class);
        workspaceManager = mock(WorkspaceManager.class);
    }

    private StuckExecutionRecoveryRunner runner(String configuredWorkerId) {
        return new StuckExecutionRecoveryRunner(
            executionRepository, stepRepository, workspaceManager, new WorkerIdentity(configuredWorkerId));
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
}
