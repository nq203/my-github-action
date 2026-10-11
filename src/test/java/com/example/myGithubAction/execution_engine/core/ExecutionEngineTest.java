package com.example.myGithubAction.execution_engine.core;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.execution_engine.dto.StepExecutionResult;
import com.example.myGithubAction.execution_engine.error.ErrorHandler;
import com.example.myGithubAction.execution_engine.executor.StepExecutor;
import com.example.myGithubAction.execution_engine.logging.LogManager;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

        when(stepRepository.save(any(WorkFlowExecutionStep.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(executionRepository.save(any(WorkFlowExecution.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private WorkFlowExecution execution(Long id) {
        WorkFlowExecution execution = new WorkFlowExecution();
        execution.setId(id);
        execution.setStatus(ExecutionState.PENDING);
        return execution;
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
        WorkFlowExecution execution = execution(executionId);
        WorkFlowExecutionStep step = step(10L, executionId, 0);

        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(stepRepository.findByExecutionIdOrderByStepOrder(executionId)).thenReturn(List.of(step));
        when(stepExecutor.execute(any())).thenReturn(StepExecutionResult.failure("connection timeout"));

        assertTimeoutPreemptively(ofSeconds(15), () -> engine.startExecution(executionId));

        // 1 initial attempt + 3 retries = 4 calls
        verify(stepExecutor, times(4)).execute(any());
        verify(workspaceManager).createWorkspace(executionId);
        verify(workspaceManager).deleteWorkspace(executionId);
        assertEquals(ExecutionState.FAILED, step.getStatus());
        assertEquals(ExecutionState.FAILED, execution.getStatus());
    }

    @Test
    void stepFailureStopsSubsequentSteps() throws Exception {
        Long executionId = 2L;
        WorkFlowExecution execution = execution(executionId);
        WorkFlowExecutionStep step1 = step(20L, executionId, 0);
        WorkFlowExecutionStep step2 = step(21L, executionId, 1);
        WorkFlowExecutionStep step3 = step(22L, executionId, 2);

        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
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
        assertEquals(ExecutionState.SUCCESS, step1.getStatus());
        assertEquals(ExecutionState.FAILED, step2.getStatus());
        assertEquals(ExecutionState.PENDING, step3.getStatus());
        assertEquals(ExecutionState.FAILED, execution.getStatus());
    }

    @Test
    void cancellationStopsRemainingStepsAndDoesNotOverwriteStatus() throws Exception {
        Long executionId = 3L;
        WorkFlowExecution execution = execution(executionId);
        execution.setStatus(ExecutionState.CANCELLED); // simulates cancelExecution() having already run
        WorkFlowExecutionStep step1 = step(30L, executionId, 0);
        WorkFlowExecutionStep step2 = step(31L, executionId, 1);

        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(stepRepository.findByExecutionIdOrderByStepOrder(executionId))
            .thenReturn(List.of(step1, step2));

        cancellationRegistry.register(executionId);
        cancellationRegistry.cancel(executionId);

        assertTimeoutPreemptively(ofSeconds(5), () -> engine.startExecution(executionId));

        verify(stepExecutor, never()).execute(any());
        verify(workspaceManager, never()).createWorkspace(executionId);
        verify(workspaceManager, never()).deleteWorkspace(executionId);
        assertEquals(ExecutionState.CANCELLED, execution.getStatus());
    }
}
