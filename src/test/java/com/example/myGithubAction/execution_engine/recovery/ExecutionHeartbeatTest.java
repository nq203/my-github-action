package com.example.myGithubAction.execution_engine.recovery;

import com.example.myGithubAction.execution_engine.core.ExecutionCancellationRegistry;
import com.example.myGithubAction.execution_engine.core.WorkerIdentity;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutionHeartbeatTest {

    private WorkFlowExecutionRepository executionRepository;
    private ExecutionCancellationRegistry registry;
    private ExecutionHeartbeat heartbeat;

    @BeforeEach
    void setUp() {
        executionRepository = mock(WorkFlowExecutionRepository.class);
        registry = new ExecutionCancellationRegistry();
        heartbeat = new ExecutionHeartbeat(registry, executionRepository, new WorkerIdentity("w1"));
    }

    @Test
    void doesNothingWhenNoExecutionRuns() {
        heartbeat.beat();

        verifyNoInteractions(executionRepository);
    }

    @Test
    void renewsHeartbeatOfEveryExecutionRunningHere() {
        registry.register(1L);
        registry.register(2L);
        when(executionRepository.heartbeat(eq(Set.of(1L, 2L)), eq("w1"), any())).thenReturn(2);

        heartbeat.beat();

        verify(executionRepository).heartbeat(eq(Set.of(1L, 2L)), eq("w1"), any());
        verify(executionRepository, never()).findOwnedRunningIds(any(), any());
        assertFalse(registry.isCancelled(1L));
        assertFalse(registry.isCancelled(2L));
    }

    @Test
    void stopsAnExecutionThatIsNoLongerRunningUnderThisWorker() {
        // e.g. a reaper already declared it lost, or it was cancelled
        registry.register(1L);
        registry.register(2L);
        when(executionRepository.heartbeat(any(), eq("w1"), any())).thenReturn(1);
        when(executionRepository.findOwnedRunningIds(any(), eq("w1"))).thenReturn(List.of(1L));

        heartbeat.beat();

        assertFalse(registry.isCancelled(1L));
        assertTrue(registry.isCancelled(2L), "the execution declared lost must be stopped");
    }

    @Test
    void databaseFailureDoesNotKillTheScheduler() {
        registry.register(1L);
        when(executionRepository.heartbeat(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        heartbeat.beat(); // must not throw

        assertFalse(registry.isCancelled(1L), "a failed heartbeat alone must not stop a healthy execution");
    }
}
