package com.example.myGithubAction.monitoring;

import com.example.myGithubAction.workflow.service.ExecutionAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opens SSE streams of one execution for its owner and keeps the hub free of dead viewers. */
@Slf4j
@Service
public class ExecutionStreamService {

    static final int MAX_STREAMS_PER_USER = 5;
    static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;
    static final int QUEUE_CAPACITY = 1000;

    private final ExecutionAccess access;
    private final ExecutionSnapshotService snapshots;
    private final ExecutionEventHub hub;
    private final Executor streamExecutor;
    private final Set<SseStreamSink> openSinks = ConcurrentHashMap.newKeySet();
    /** Makes "count, then register" atomic so concurrent opens cannot exceed the per-user limit. */
    private final Object admission = new Object();

    public ExecutionStreamService(ExecutionAccess access, ExecutionSnapshotService snapshots, ExecutionEventHub hub,
                                  @Qualifier("streamExecutor") Executor streamExecutor) {
        this.access = access;
        this.snapshots = snapshots;
        this.hub = hub;
        this.streamExecutor = streamExecutor;
    }

    /**
     * @throws com.example.myGithubAction.auth.exception.ResourceNotFoundException missing or not owned (404)
     * @throws TooManyStreamsException the user already has {@value #MAX_STREAMS_PER_USER} open streams (429)
     */
    public SseEmitter open(Long executionId, Long userId) {
        access.requireOwner(executionId, userId);

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        SseStreamSink sink = new SseStreamSink(emitter, streamExecutor, QUEUE_CAPACITY);
        StreamSubscription subscription =
                new StreamSubscription(executionId, userId, sink, () -> snapshots.load(executionId));

        AtomicBoolean cleaned = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (cleaned.compareAndSet(false, true)) {
                subscription.close();
                sink.close();
                hub.unregister(subscription);
                openSinks.remove(sink);
            }
        };
        sink.onResyncNeeded(subscription::resend);
        sink.onFailure(cleanup);
        emitter.onCompletion(cleanup);
        emitter.onError(e -> cleanup.run());
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete(); // end the response normally; the client reconnects
        });

        synchronized (admission) {
            if (hub.countForUser(userId) >= MAX_STREAMS_PER_USER) {
                throw new TooManyStreamsException(MAX_STREAMS_PER_USER);
            }
            // registered before start(): events published while the snapshot loads are buffered
            hub.register(subscription);
        }
        openSinks.add(sink);
        try {
            subscription.start();
        } catch (RuntimeException e) {
            cleanup.run();
            throw e;
        }
        if (subscription.isClosed()) {
            // finished at connect (snapshot + end already queued) or sink failed: free the slot now
            hub.unregister(subscription);
        }
        return emitter;
    }

    /** SSE comment on every open stream; also how a vanished client is detected (the write fails). */
    @Scheduled(fixedRateString = "${app.stream.keepalive-ms:15000}")
    public void keepalive() {
        for (SseStreamSink sink : openSinks) {
            sink.keepalive();
        }
    }
}
