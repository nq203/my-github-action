package com.example.myGithubAction.execution_engine.worker;

import com.example.myGithubAction.messaging.ExecutionEventCodec;
import com.example.myGithubAction.messaging.ExecutionKafkaProperties;
import com.example.myGithubAction.execution_engine.core.ExecutionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutionRequestListenerTest {

    private static final String PAYLOAD = "{\"version\":1,\"executionId\":42,\"requestedAtEpochMs\":1}";

    private ExecutionEngine engine;
    private WorkerCapacity capacity;
    private ExecutionRequestListener listener;

    @BeforeEach
    void setUp() {
        engine = mock(ExecutionEngine.class);
        ExecutionKafkaProperties properties = new ExecutionKafkaProperties();
        properties.getWorker().setMaxConcurrentExecutions(1);
        capacity = new WorkerCapacity(properties);
        listener = new ExecutionRequestListener(engine, capacity, new ExecutionEventCodec());
    }

    @Test
    void claimedExecutionHoldsItsSlotUntilItFinishes() {
        CompletableFuture<Void> running = new CompletableFuture<>();
        when(engine.claim(42L)).thenReturn(true);
        when(engine.runClaimed(42L)).thenReturn(running);

        listener.onRequested(PAYLOAD);

        assertEquals(0, capacity.available(), "slot must stay reserved while the execution runs");
        running.complete(null);
        assertEquals(1, capacity.available());
        verify(engine, never()).failClaimed(anyLong(), any());
    }

    @Test
    void lostClaimIsSkippedAndReleasesTheSlot() {
        when(engine.claim(42L)).thenReturn(false);

        listener.onRequested(PAYLOAD);

        verify(engine, never()).runClaimed(anyLong());
        assertEquals(1, capacity.available());
    }

    @Test
    void noFreeSlotThrowsWithoutClaiming() {
        assertTrue(capacity.tryAcquire()); // the only slot is taken

        assertThrows(WorkerBusyException.class, () -> listener.onRequested(PAYLOAD));

        verify(engine, never()).claim(anyLong());
    }

    @Test
    void claimedButNotStartableExecutionIsFailedAndSlotReleased() {
        when(engine.claim(42L)).thenReturn(true);
        when(engine.runClaimed(42L)).thenThrow(new RejectedExecutionException("pool full"));

        listener.onRequested(PAYLOAD);

        verify(engine).failClaimed(eq(42L), contains("pool full"));
        assertEquals(1, capacity.available());
    }

    @Test
    void crashedExecutionIsFailedAndSlotReleased() {
        CompletableFuture<Void> running = new CompletableFuture<>();
        when(engine.claim(42L)).thenReturn(true);
        when(engine.runClaimed(42L)).thenReturn(running);

        listener.onRequested(PAYLOAD);
        running.completeExceptionally(new IllegalStateException("db down"));

        verify(engine).failClaimed(eq(42L), contains("db down"));
        assertEquals(1, capacity.available());
    }

    @Test
    void malformedPayloadIsRejectedWithoutTouchingTheSlot() {
        assertThrows(ExecutionEventCodec.MalformedEventException.class, () -> listener.onRequested("not json"));

        verify(engine, never()).claim(anyLong());
        assertEquals(1, capacity.available());
    }
}
