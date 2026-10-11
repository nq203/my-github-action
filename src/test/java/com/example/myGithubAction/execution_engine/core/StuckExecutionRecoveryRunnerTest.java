package com.example.myGithubAction.execution_engine.core;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StuckExecutionRecoveryRunnerTest {

    private WorkFlowExecutionRepository executionRepository;
    private WorkFlowExecutionStepRepository stepRepository;
    private StuckExecutionRecoveryRunner runner;

    @BeforeEach
    void setUp() {
        executionRepository = mock(WorkFlowExecutionRepository.class);
        stepRepository = mock(WorkFlowExecutionStepRepository.class);
        runner = new StuckExecutionRecoveryRunner(executionRepository, stepRepository);
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

        assertEquals(ExecutionState.FAILED, stuckExecution.getStatus());
        assertNotNull(stuckExecution.getErrorMessage());
        assertNotNull(stuckExecution.getEndedAt());

        assertEquals(ExecutionState.FAILED, stuckStep.getStatus());
        assertNotNull(stuckStep.getErrorMessage());
        assertNotNull(stuckStep.getEndedAt());

        verify(executionRepository).saveAll(List.of(stuckExecution));
        verify(stepRepository).saveAll(List.of(stuckStep));
    }

    @Test
    void doesNotTouchPendingExecutions() throws Exception {
        when(executionRepository.findByStatus(ExecutionState.RUNNING)).thenReturn(List.of());
        when(stepRepository.findByStatus(ExecutionState.RUNNING)).thenReturn(List.of());

        runner.run();

        verify(executionRepository, never()).findByStatus(ExecutionState.PENDING);
        verify(stepRepository, never()).findByStatus(ExecutionState.PENDING);
        verify(executionRepository).saveAll(List.of());
        verify(stepRepository).saveAll(List.of());
    }
}
