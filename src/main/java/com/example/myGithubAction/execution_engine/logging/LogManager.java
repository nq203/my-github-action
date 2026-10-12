package com.example.myGithubAction.execution_engine.logging;

import com.example.myGithubAction.messaging.ExecutionEventPublisher;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The only writer of step logs.
 *
 * Every write goes through StepLogStore (which assigns sequence numbers and enforces the size cap)
 * and is followed by a logAppended event. Publisher and store failures are logged and swallowed,
 * so logging can never fail or slow the job.
 */
@Component
public class LogManager {

    private static final Logger log = LoggerFactory.getLogger(LogManager.class);
    private static final long TICK_MS = 100;
    private static final int PUBLISH_QUEUE_CAPACITY = 1000;

    private final StepLogStore store;
    private final ExecutionEventPublisher publisher;
    private final Set<StepLogSink> openSinks = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService flusher;
    /** Single thread so per-step event order is kept; bounded so a broker outage cannot pile up memory. */
    private final ThreadPoolExecutor publishExecutor;

    public LogManager(StepLogStore store, ExecutionEventPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "step-log-flusher");
            t.setDaemon(true);
            return t;
        });
        this.publishExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PUBLISH_QUEUE_CAPACITY), r -> {
                    Thread t = new Thread(r, "step-log-publisher");
                    t.setDaemon(true);
                    return t;
                });
        this.flusher.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** Appends one line immediately (a single-line batch). */
    public void appendLog(Long stepId, String logLine) {
        write(stepId, List.of(logLine));
    }

    /** Opens a batching sink for the streamed output of one step; close it when the output ends. */
    public StepLogSink openSink(Long stepId) {
        StepLogSink sink = new StepLogSink(lines -> write(stepId, lines), openSinks::remove, this::requestFlush);
        openSinks.add(sink);
        return sink;
    }

    /** Flushes logs. No-op: sinks flush on close and via the ticker. */
    public void flush() {
    }

    @PreDestroy
    public void shutdown() {
        flusher.shutdownNow();
        publishExecutor.shutdownNow();
        for (StepLogSink sink : List.copyOf(openSinks)) {
            sink.close();
        }
    }

    private void requestFlush(StepLogSink sink) {
        try {
            flusher.execute(sink::flushRequested);
        } catch (RejectedExecutionException e) {
            // shutting down; close() still flushes the remainder
        }
    }

    private void tick() {
        for (StepLogSink sink : openSinks) {
            try {
                sink.flushIfStale();
            } catch (Exception e) {
                log.warn("Step log flush failed: {}", e.getMessage());
            }
        }
    }

    private void write(Long stepId, List<String> lines) {
        Optional<StepLogStore.Appended> appended;
        try {
            appended = store.append(stepId, lines);
        } catch (Exception e) {
            log.warn("Failed to append log for step {}: {}", stepId, e.getMessage());
            return;
        }
        if (appended == null || appended.isEmpty()) {
            return;
        }
        // The DB is the truth: events are handed to a separate thread and dropped on overflow or
        // failure, so a slow or down broker can never block the writer or the command.
        StepLogStore.Appended a = appended.get();
        try {
            publishExecutor.execute(() -> {
                try {
                    publisher.logAppended(stepId, a.fromSeq(), a.lines());
                } catch (Exception e) {
                    log.warn("Failed to publish log event for step {}: {}", stepId, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Log event for step {} dropped (publish queue full or stopped)", stepId);
        }
    }
}
