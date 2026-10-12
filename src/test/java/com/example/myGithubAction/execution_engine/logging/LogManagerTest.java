package com.example.myGithubAction.execution_engine.logging;

import com.example.myGithubAction.messaging.ExecutionEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LogManagerTest {

    private StepLogStore store;
    private ExecutionEventPublisher publisher;
    private LogManager logManager;

    @BeforeEach
    void setUp() {
        store = mock(StepLogStore.class);
        publisher = mock(ExecutionEventPublisher.class);
        when(store.append(anyLong(), anyList())).thenAnswer(inv -> {
            List<String> lines = new ArrayList<>(inv.getArgument(1));
            return Optional.of(new StepLogStore.Appended(1, lines));
        });
        logManager = new LogManager(store, publisher);
    }

    @AfterEach
    void tearDown() {
        logManager.shutdown();
    }

    @Test
    void sinkBatchesLinesWithin200ms() {
        StepLogSink sink = logManager.openSink(1L);
        sink.accept("a");
        sink.accept("b");
        sink.accept("c");
        verify(store, never()).append(anyLong(), anyList());

        sink.close();

        verify(store, times(1)).append(1L, List.of("a", "b", "c"));
    }

    @Test
    void fiftyLinesFlushImmediately() {
        StepLogSink sink = logManager.openSink(1L);
        for (int i = 0; i < 50; i++) {
            sink.accept("l" + i);
        }
        verify(store, timeout(1000).times(1)).append(eq(1L), argThat(l -> l.size() == 50));
        sink.close();
        verify(store, times(1)).append(anyLong(), anyList());
    }

    @Test
    void quietLineIsFlushedWithoutFurtherInput() {
        StepLogSink sink = logManager.openSink(1L);
        sink.accept("lonely");

        verify(store, timeout(1000)).append(1L, List.of("lonely"));
        sink.close();
    }

    @Test
    void closeFlushesRemainder() {
        StepLogSink sink = logManager.openSink(1L);
        sink.accept("tail");
        sink.close();

        verify(store).append(1L, List.of("tail"));
    }

    @Test
    void closeWithNothingBufferedWritesNothing() {
        logManager.openSink(1L).close();

        verify(store, never()).append(anyLong(), anyList());
    }

    @Test
    void publisherGetsStoreFromSeqAndStoredLines() {
        when(store.append(eq(7L), anyList()))
                .thenReturn(Optional.of(new StepLogStore.Appended(5, List.of("x", "[log truncated]"))));

        StepLogSink sink = logManager.openSink(7L);
        sink.accept("x");
        sink.accept("y");
        sink.close();

        verify(publisher, timeout(1000)).logAppended(7L, 5, List.of("x", "[log truncated]"));
    }

    @Test
    void publisherFailureDoesNotBreakAppend() {
        doThrow(new RuntimeException("kafka down")).when(publisher).logAppended(anyLong(), anyInt(), anyList());

        logManager.appendLog(1L, "hello");

        verify(store).append(1L, List.of("hello"));
        verify(publisher, timeout(1000)).logAppended(anyLong(), anyInt(), anyList());
    }

    @Test
    void appendLogWritesImmediately() {
        logManager.appendLog(3L, "now");

        verify(store).append(3L, List.of("now"));
        verify(publisher, timeout(1000)).logAppended(3L, 1, List.of("now"));
    }

    @Test
    void storeEmptyPublishesNothing() {
        when(store.append(anyLong(), anyList())).thenReturn(Optional.empty());

        logManager.appendLog(1L, "dropped");
        StepLogSink sink = logManager.openSink(1L);
        sink.accept("dropped too");
        sink.close();

        verifyNoInteractions(publisher);
    }

    @Test
    void blockedPublisherDoesNotBlockWritesOrClose() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        }).when(publisher).logAppended(anyLong(), anyInt(), anyList());

        java.util.concurrent.atomic.AtomicInteger storedLines = new java.util.concurrent.atomic.AtomicInteger();
        when(store.append(anyLong(), anyList())).thenAnswer(inv -> {
            List<String> lines = new ArrayList<>(inv.getArgument(1));
            storedLines.addAndGet(lines.size());
            return Optional.of(new StepLogStore.Appended(1, lines));
        });
        long start = System.nanoTime();
        StepLogSink sink = logManager.openSink(1L);
        for (int i = 0; i < 500; i++) {
            sink.accept("l" + i);
        }
        sink.close();
        logManager.appendLog(1L, "after");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        release.countDown();

        org.junit.jupiter.api.Assertions.assertTrue(elapsedMs < 2000, "took " + elapsedMs + "ms");
        // all 500 lines plus the single appendLog are stored despite the stuck publisher
        org.junit.jupiter.api.Assertions.assertEquals(501, storedLines.get());
    }

    @Test
    void slowStoreStillReceivesEveryLineInOrderWithoutBlockingAccept() {
        List<String> stored = java.util.Collections.synchronizedList(new ArrayList<>());
        when(store.append(anyLong(), anyList())).thenAnswer(inv -> {
            Thread.sleep(50);
            List<String> lines = new ArrayList<>(inv.getArgument(1));
            stored.addAll(lines);
            return Optional.of(new StepLogStore.Appended(1, lines));
        });
        StepLogSink sink = logManager.openSink(1L);
        long start = System.nanoTime();
        for (int i = 0; i < 3000; i++) {
            sink.accept("l" + i);
        }
        long acceptMs = (System.nanoTime() - start) / 1_000_000;
        sink.close();

        org.junit.jupiter.api.Assertions.assertTrue(acceptMs < 500, "accept loop took " + acceptMs + "ms");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            expected.add("l" + i);
        }
        org.junit.jupiter.api.Assertions.assertEquals(expected, new ArrayList<>(stored));
    }
}
