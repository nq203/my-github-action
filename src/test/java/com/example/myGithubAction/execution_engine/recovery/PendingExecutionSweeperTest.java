package com.example.myGithubAction.execution_engine.recovery;

import com.example.myGithubAction.messaging.ExecutionDispatcher;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PendingExecutionSweeperTest {

    private WorkFlowExecutionRepository executionRepository;
    private ExecutionDispatcher dispatcher;
    private PendingExecutionSweeper sweeper;

    @BeforeEach
    void setUp() {
        executionRepository = mock(WorkFlowExecutionRepository.class);
        dispatcher = mock(ExecutionDispatcher.class);
        sweeper = new PendingExecutionSweeper(executionRepository, dispatcher, new WorkerProperties());
    }

    @Test
    void republishesStalePendingExecutionWonByThisSweeper() {
        when(executionRepository.findStalePendingIds(any(), any())).thenReturn(List.of(3L));
        when(executionRepository.claimRedispatch(eq(3L), any(), any())).thenReturn(1);

        sweeper.sweep();

        verify(dispatcher).dispatch(3L);
    }

    @Test
    void doesNotRepublishWhenAnotherSweeperWonOrItWasClaimedMeanwhile() {
        when(executionRepository.findStalePendingIds(any(), any())).thenReturn(List.of(3L));
        when(executionRepository.claimRedispatch(eq(3L), any(), any())).thenReturn(0);

        sweeper.sweep();

        verify(dispatcher, never()).dispatch(anyLong());
    }

    @Test
    void rejectedRepublishDoesNotStopTheRestOfTheBatch() {
        when(executionRepository.findStalePendingIds(any(), any())).thenReturn(List.of(3L, 4L));
        when(executionRepository.claimRedispatch(anyLong(), any(), any())).thenReturn(1);
        doThrow(new RejectedExecutionException("broker down")).when(dispatcher).dispatch(3L);

        sweeper.sweep();

        verify(dispatcher).dispatch(4L);
    }
}
