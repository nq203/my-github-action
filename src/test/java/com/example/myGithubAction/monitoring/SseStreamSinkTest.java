package com.example.myGithubAction.monitoring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SseStreamSinkTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    /** Records event names; every send blocks until {@link #release} is counted down (a slow reader). */
    static class RecordingEmitter extends SseEmitter {
        final CountDownLatch release;
        final List<String> names = Collections.synchronizedList(new ArrayList<>());
        volatile boolean completed;

        RecordingEmitter(boolean blocked) {
            super(60_000L);
            this.release = new CountDownLatch(blocked ? 1 : 0);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            StringBuilder text = new StringBuilder();
            for (DataWithMediaType part : builder.build()) {
                if (part.getData() instanceof String s) {
                    text.append(s);
                }
            }
            for (String line : text.toString().split("\n")) {
                if (line.startsWith("event:")) {
                    names.add(line.substring("event:".length()));
                }
            }
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    @Test
    void slowConsumerOverflowRequestsResyncAndDoesNotBlockCaller() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter(true);
        SseStreamSink sink = new SseStreamSink(emitter, executor, 10);
        AtomicInteger resyncs = new AtomicInteger();
        CountDownLatch floodOver = new CountDownLatch(1);
        // like StreamSubscription.resend(): answers the request with a fresh snapshot (here only
        // once the flood below is over, so the refilled queue cannot overflow a second time)
        sink.onResyncNeeded(() -> {
            try {
                floodOver.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sink.send("snapshot", Map.of("fresh", true));
            resyncs.incrementAndGet();
        });

        long start = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            sink.send("log", Map.of("i", i));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(elapsedMs).as("send must not block on a slow reader").isLessThan(1_000);
        floodOver.countDown();

        await(() -> resyncs.get() == 1);
        sink.send("log", Map.of("i", 501));
        emitter.release.countDown();

        await(() -> emitter.names.contains("snapshot") && emitter.names.size() >= 2
                && emitter.names.get(emitter.names.size() - 1).equals("log"));
        Thread.sleep(100);
        assertThat(resyncs.get()).as("one resync per overflow").isEqualTo(1);
        List<String> names = List.copyOf(emitter.names);
        // at most the one event already being written when the queue overflowed precedes the snapshot
        assertThat(names.subList(0, names.indexOf("snapshot"))).hasSizeLessThanOrEqualTo(1);
        assertThat(names.subList(names.indexOf("snapshot"), names.size())).containsExactly("snapshot", "log");
    }

    @Test
    void completeStopsFurtherSends() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter(false);
        SseStreamSink sink = new SseStreamSink(emitter, executor, 10);

        sink.send("snapshot", Map.of());
        sink.send("end", Map.of("status", "SUCCESS"));
        sink.complete();
        assertThatThrownBy(() -> sink.send("log", Map.of())).isInstanceOf(IllegalStateException.class);
        sink.keepalive();

        await(() -> emitter.completed);
        Thread.sleep(100);
        assertThat(emitter.names).containsExactly("snapshot", "end");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(10);
        }
    }
}
