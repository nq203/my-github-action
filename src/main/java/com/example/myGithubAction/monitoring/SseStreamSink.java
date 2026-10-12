package com.example.myGithubAction.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link StreamSink} over an {@link SseEmitter} that never blocks its caller.
 *
 * <p>{@link StreamSubscription} calls {@link #send} while holding its lock, on the Kafka listener
 * (or scheduler) thread, so events only go into a bounded queue here; the actual socket writes
 * happen on the shared {@code streamExecutor}, at most one drain task per sink at a time (which
 * keeps event order). A reader that falls {@code capacity} events behind loses its queue: the
 * queue is cleared, every following event except a {@code snapshot} is dropped, and the
 * subscription is asked (on the executor, not on the caller's thread) to send a fresh snapshot.
 * If the stream completes while that snapshot is still owed, the emitter is completed without the
 * dropped {@code end}; the client reconnects and gets snapshot + end.
 *
 * <p>Lock order: subscription lock, then this sink's monitor. Neither the monitor nor the drain
 * thread ever calls back into the subscription while holding a lock.
 */
@Slf4j
public class SseStreamSink implements StreamSink {

    /** Events written per drain task before yielding the executor thread to other sinks. */
    private static final int DRAIN_BATCH = 64;

    private final SseEmitter emitter;
    private final Executor executor;
    private final BlockingQueue<SseEmitter.SseEventBuilder> queue;
    private final AtomicBoolean draining = new AtomicBoolean();

    private volatile Runnable resyncRequest = () -> { };
    private volatile Runnable failureHandler = () -> { };

    // guarded by this
    private boolean resyncPending;
    private long overflowGeneration;
    // written under this, read by the drain thread
    private volatile boolean completeRequested;
    private volatile boolean closed;

    public SseStreamSink(SseEmitter emitter, Executor executor, int capacity) {
        this.emitter = emitter;
        this.executor = executor;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Called (on the executor) after an overflow; must make the subscription send a snapshot. */
    public void onResyncNeeded(Runnable request) {
        this.resyncRequest = request;
    }

    /** Called once when the stream dies on its own (client gone, executor saturated). */
    public void onFailure(Runnable handler) {
        this.failureHandler = handler;
    }

    @Override
    public void send(String eventName, Object data) {
        long generation;
        synchronized (this) {
            if (closed || completeRequested) {
                throw new IllegalStateException("stream closed");
            }
            if (resyncPending) {
                if (!"snapshot".equals(eventName)) {
                    return; // dropped: the snapshot that is on its way supersedes it
                }
                resyncPending = false;
            }
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON);
            if (queue.offer(event)) {
                generation = -1;
            } else {
                queue.clear();
                resyncPending = true;
                generation = ++overflowGeneration;
            }
        }
        if (generation < 0) {
            scheduleDrain();
        } else {
            log.debug("Slow SSE reader: dropped its queue, requesting a snapshot");
            scheduleResync(generation);
        }
    }

    /** Queues an SSE comment line; skipped when the reader is already behind. */
    public void keepalive() {
        synchronized (this) {
            if (closed || completeRequested) {
                return;
            }
            if (!queue.offer(SseEmitter.event().comment(" keepalive"))) {
                return;
            }
        }
        scheduleDrain();
    }

    /** Completes the emitter once everything queued before it is written. Idempotent. */
    @Override
    public void complete() {
        synchronized (this) {
            if (closed || completeRequested) {
                return;
            }
            completeRequested = true;
        }
        scheduleDrain();
    }

    /** Stops all output immediately (the emitter is already finished or being abandoned). Idempotent. */
    public void close() {
        synchronized (this) {
            closed = true;
            queue.clear();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private void scheduleDrain() {
        if (draining.compareAndSet(false, true)) {
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException e) {
                draining.set(false);
                fail(e);
            }
        }
    }

    private void drain() {
        try {
            int written = 0;
            SseEmitter.SseEventBuilder next;
            while (!closed && (next = queue.poll()) != null) {
                emitter.send(next);
                if (++written == DRAIN_BATCH) {
                    executor.execute(this::drain); // still marked draining; lets other sinks run
                    return;
                }
            }
            if (!closed && completeRequested && queue.isEmpty()) {
                closed = true;
                emitter.complete();
                return;
            }
        } catch (Exception e) { // IOException: client gone; IllegalStateException: emitter already done
            fail(e);
            return;
        }
        draining.set(false);
        if (!closed && (!queue.isEmpty() || completeRequested)) {
            scheduleDrain();
        }
    }

    private void scheduleResync(long generation) {
        try {
            executor.execute(() -> {
                try {
                    resyncRequest.run();
                } catch (RuntimeException e) {
                    log.debug("Resync after overflow failed: {}", e.getMessage());
                }
                boolean stillOwed;
                synchronized (this) {
                    stillOwed = resyncPending && overflowGeneration == generation && !completeRequested;
                }
                if (stillOwed) {
                    // no snapshot came (DB error or subscription gone): end the stream so the client reconnects
                    fail(new IllegalStateException("no snapshot after overflow"));
                }
            });
        } catch (RejectedExecutionException e) {
            fail(e);
        }
    }

    private void fail(Throwable cause) {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            queue.clear();
        }
        log.debug("SSE stream closed: {}", cause.toString());
        try {
            emitter.complete(); // ignored by Spring after an IOException; the container then ends the request
        } catch (RuntimeException ignored) {
            // already completed
        }
        try {
            failureHandler.run();
        } catch (RuntimeException e) {
            log.warn("SSE failure handler failed: {}", e.getMessage());
        }
    }
}
