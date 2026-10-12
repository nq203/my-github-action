package com.example.myGithubAction.execution_engine.core;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.execution_engine.dto.StepExecutionResult;
import com.example.myGithubAction.execution_engine.error.ErrorHandler;
import com.example.myGithubAction.execution_engine.executor.StepExecutor;
import com.example.myGithubAction.execution_engine.logging.LogManager;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static java.time.Duration.ofSeconds;

class ExecutionEngineTest {

    private WorkFlowExecutionRepository executionRepository;
    private WorkFlowExecutionStepRepository stepRepository;
    private StepExecutor stepExecutor;
    private LogManager logManager;
    private ErrorHandler errorHandler;
    private ExecutionCancellationRegistry cancellationRegistry;
    private WorkspaceManager workspaceManager;
    private ExecutionEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        executionRepository = mock(WorkFlowExecutionRepository.class);
        stepRepository = mock(WorkFlowExecutionStepRepository.class);
        stepExecutor = mock(StepExecutor.class);
        logManager = mock(LogManager.class);
        errorHandler = new ErrorHandler();
        cancellationRegistry = new ExecutionCancellationRegistry();
        workspaceManager = mock(WorkspaceManager.class);
        doReturn(Path.of(System.getProperty("java.io.tmpdir"), "workspace-1"))
            .when(workspaceManager).createWorkspace(anyLong());
        engine = new ExecutionEngine(
            executionRepository, stepRepository, stepExecutor, logManager,
            errorHandler, cancellationRegistry, workspaceManager);

        // Default: this worker wins the claim and every transition applies.
        when(executionRepository.claimPending(anyLong(), any())).thenReturn(1);
        when(executionRepository.transition(anyLong(), any(), any(), any(), any())).thenReturn(1);
        when(stepRepository.transition(anyLong(), any(), any(), any(), any(), any())).thenReturn(1);
        when(stepRepository.markRunning(anyLong(), any())).thenReturn(1);
    }

    private WorkFlowExecutionStep step(Long id, Long executionId, int order) {
        WorkFlowExecutionStep step = new WorkFlowExecutionStep();
        step.setId(id);
        step.setExecutionId(executionId);
        step.setStepId(id);
        step.setStepOrder(order);
        step.setStatus(ExecutionState.PENDING);
        return step;
    }

    @Test
    void retriesAtMostThreeTimesThenFails() throws Exception {
        Long executionId = 1L;
        WorkFlowExecutionStep step = step(10L, executionId, 0);

        when(stepRepository.findByExecutionIdOrderByStepOrder(executionId)).thenReturn(List.of(step));
        when(stepExecutor.execute(any())).thenReturn(StepExecutionResult.failure("connection timeout"));

        assertTimeoutPreemptively(ofSeconds(15), () -> engine.startExecution(executionId));

        // 1 initial attempt + 3 retries = 4 calls
        verify(stepExecutor, times(4)).execute(any());
        verify(stepRepository, times(4)).markRunning(eq(10L), any());
        verify(workspaceManager).createWorkspace(executionId);
        verify(workspaceManager).deleteWorkspace(executionId);
        verify(stepRepository).transition(eq(10L), any(), eq(ExecutionState.FAILED), any(), any(), any());
        verify(executionRepository).transition(eq(executionId), any(), eq(ExecutionState.FAILED), any(), any());
    }

    @Test
    void stepFailureStopsSubsequentSteps() throws Exception {
        Long executionId = 2L;
        WorkFlowExecutionStep step1 = step(20L, executionId, 0);
        WorkFlowExecutionStep step2 = step(21L, executionId, 1);
        WorkFlowExecutionStep step3 = step(22L, executionId, 2);

        when(stepRepository.findByExecutionIdOrderByStepOrder(executionId))
            .thenReturn(List.of(step1, step2, step3));
        when(stepExecutor.execute(step1)).thenReturn(StepExecutionResult.success("ok"));
        when(stepExecutor.execute(step2)).thenReturn(StepExecutionResult.failure("command not found"));

        assertTimeoutPreemptively(ofSeconds(5), () -> engine.startExecution(executionId));

        verify(stepExecutor, times(1)).execute(step1);
        verify(stepExecutor, times(1)).execute(step2);
        verify(stepExecutor, never()).execute(step3);
        verify(workspaceManager).createWorkspace(executionId);
        verify(workspaceManager).deleteWorkspace(executionId);
        verify(stepRepository).transition(eq(20L), any(), eq(ExecutionState.SUCCESS), any(), any(), any());
        verify(stepRepository).transition(eq(21L), any(), eq(ExecutionState.FAILED), any(), any(), any());
        verify(stepRepository, never()).markRunning(eq(22L), any());
        verify(executionRepository).transition(eq(executionId), any(), eq(ExecutionState.FAILED), any(), any());
        verify(executionRepository, never()).transition(anyLong(), any(), eq(ExecutionState.SUCCESS), any(), any());
    }

    @Test
    void allStepsSucceedMarksExecutionSuccess() throws Exception {
        Long executionId = 4L;
        WorkFlowExecutionStep step1 = step(40L, executionId, 0);
        WorkFlowExecutionStep step2 = step(41L, executionId, 1);

        when(stepRepository.findByExecutionIdOrderByStepOrder(executionId)).thenReturn(List.of(step1, step2));
        when(stepExecutor.execute(any())).thenReturn(StepExecutionResult.success("ok"));
        // handleCompletion re-reads the persisted step states
        WorkFlowExecutionStep done1 = step(40L, executionId, 0);
        WorkFlowExecutionStep done2 = step(41L, executionId, 1);
        done1.setStatus(ExecutionState.SUCCESS);
        done2.setStatus(ExecutionState.SUCCESS);
        when(stepRepository.findByExecutionId(executionId)).thenReturn(List.of(done1, done2));

        assertTimeoutPreemptively(ofSeconds(5), () -> engine.startExecution(executionId));

        verify(executionRepository).transition(eq(executionId), eq(EnumSet.of(ExecutionState.RUNNING)),
            eq(ExecutionState.SUCCESS), any(), isNull());
    }

    @Test
    void cancellationStopsRemainingStepsAndDoesNotTouchStatus() throws Exception {
        Long executionId = 3L;
        WorkFlowExecutionStep step1 = step(30L, executionId, 0);
        WorkFlowExecutionStep step2 = step(31L, executionId, 1);

        when(stepRepository.findByExecutionIdOrderByStepOrder(executionId))
            .thenReturn(List.of(step1, step2));

        cancellationRegistry.register(executionId);
        cancellationRegistry.cancel(executionId);

        assertTimeoutPreemptively(ofSeconds(5), () -> engine.startExecution(executionId));

        verify(executionRepository, never()).claimPending(anyLong(), any());
        verify(executionRepository, never()).transition(anyLong(), any(), any(), any(), any());
        verify(stepExecutor, never()).execute(any());
        verify(workspaceManager, never()).createWorkspace(executionId);
        verify(workspaceManager, never()).deleteWorkspace(executionId);
    }

    @Test
    void lostClaimDoesNothing() throws Exception {
        Long executionId = 5L;
        // Cancelled while PENDING in the DB, or already claimed by another worker.
        when(executionRepository.claimPending(eq(executionId), any())).thenReturn(0);

        assertTimeoutPreemptively(ofSeconds(5), () -> engine.startExecution(executionId));

        verify(stepExecutor, never()).execute(any());
        verify(stepRepository, never()).findByExecutionIdOrderByStepOrder(anyLong());
        verify(executionRepository, never()).transition(anyLong(), any(), any(), any(), any());
        // Must not delete a workspace that belongs to the worker that did win the claim.
        verify(workspaceManager, never()).createWorkspace(executionId);
        verify(workspaceManager, never()).deleteWorkspace(executionId);
    }

    @Test
    void workspaceFailureMarksExecutionFailed() throws Exception {
        Long executionId = 6L;
        doThrow(new java.io.IOException("disk full")).when(workspaceManager).createWorkspace(executionId);

        assertTimeoutPreemptively(ofSeconds(5), () -> engine.startExecution(executionId));

        verify(executionRepository).transition(eq(executionId), any(), eq(ExecutionState.FAILED), any(), eq("disk full"));
        verify(stepExecutor, never()).execute(any());
        verify(workspaceManager, never()).deleteWorkspace(executionId);
    }
}
