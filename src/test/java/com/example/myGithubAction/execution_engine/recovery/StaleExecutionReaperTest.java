package com.example.myGithubAction.execution_engine.recovery;

import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StaleExecutionReaperTest {

    private WorkFlowExecutionRepository executionRepository;
    private WorkFlowExecutionStepRepository stepRepository;
    private StaleExecutionReaper reaper;

    @BeforeEach
    void setUp() {
        executionRepository = mock(WorkFlowExecutionRepository.class);
        stepRepository = mock(WorkFlowExecutionStepRepository.class);
        WorkerProperties properties = new WorkerProperties();
        properties.setStaleAfterMs(60_000);
        reaper = new StaleExecutionReaper(executionRepository, stepRepository, properties);
    }

    @Test
    void failsStaleExecutionAndItsRunningSteps() {
        when(executionRepository.findStaleRunningIds(any())).thenReturn(List.of(5L));
        when(executionRepository.failIfStale(eq(5L), any(), any(), contains("Worker lost"))).thenReturn(1);

        reaper.reap();

        verify(stepRepository).failRunningSteps(eq(5L), any(), contains("Worker lost"));
    }

    @Test
    void executionThatHeartbeatBeforeTheUpdateIsLeftAlone() {
        // Selected as stale, but the conditional UPDATE re-checks and loses to the late heartbeat.
        when(executionRepository.findStaleRunningIds(any())).thenReturn(List.of(5L));
        when(executionRepository.failIfStale(eq(5L), any(), any(), any())).thenReturn(0);

        reaper.reap();

        verify(stepRepository, never()).failRunningSteps(anyLong(), any(), any());
    }

    @Test
    void cutoffIsStaleAfterMsBeforeNow() {
        reaper.reap();

        LocalDateTime before = LocalDateTime.now().minusSeconds(61);
        LocalDateTime after = LocalDateTime.now().minusSeconds(59);
        verify(executionRepository).findStaleRunningIds(
            argThat(cutoff -> cutoff.isAfter(before) && cutoff.isBefore(after)));
    }

    @Test
    void databaseFailureDoesNotKillTheScheduler() {
        when(executionRepository.findStaleRunningIds(any())).thenThrow(new IllegalStateException("db down"));

        reaper.reap(); // must not throw
    }
}
