package com.example.myGithubAction.execution_engine.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Batches the output lines of one step and writes them through LogManager.
 *
 * accept() only appends to an in-memory buffer (cheap, never touches the DB or the broker), so the
 * thread reading the command's output keeps draining the pipe. Writes happen off that thread: the
 * LogManager flusher flushes at 50 lines / 32 KB (signalled by accept) or when the oldest buffered
 * line is 200 ms old; close() flushes the remainder synchronously. Writes of one sink are
 * serialized by writeLock and swap the buffer inside it, so seq order matches arrival order.
 */
public class StepLogSink implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StepLogSink.class);

    static final int MAX_LINES = 50;
    static final int MAX_BYTES = 32 * 1024;
    static final long MAX_AGE_MS = 200;

    private final Consumer<List<String>> writer;
    private final Consumer<StepLogSink> onClosed;
    private final Consumer<StepLogSink> requestFlush;
    private final Object writeLock = new Object();
    private final AtomicBoolean flushRequested = new AtomicBoolean();
    private final AtomicLong droppedAfterClose = new AtomicLong();

    // guarded by this
    private List<String> buffer = new ArrayList<>();
    private int bufferedChars;
    private long firstBufferedAtNanos;
    private boolean closed;

    StepLogSink(Consumer<List<String>> writer, Consumer<StepLogSink> onClosed,
                Consumer<StepLogSink> requestFlush) {
        this.writer = writer;
        this.onClosed = onClosed;
        this.requestFlush = requestFlush;
    }

    public void accept(String line) {
        if (line == null) {
            return;
        }
        boolean full;
        synchronized (this) {
            if (closed) {
                if (droppedAfterClose.getAndIncrement() == 0) {
                    log.warn("Log line(s) arrived after the step log sink was closed and were dropped");
                }
                return;
            }
            if (buffer.isEmpty()) {
                firstBufferedAtNanos = System.nanoTime();
            }
            buffer.add(line);
            bufferedChars += line.length() + 1;
            full = buffer.size() >= MAX_LINES || bufferedChars >= MAX_BYTES;
        }
        if (full && flushRequested.compareAndSet(false, true)) {
            requestFlush.accept(this);
        }
    }

    /** Runs on the flusher thread when a size threshold was hit. */
    void flushRequested() {
        flushRequested.set(false);
        flush(false);
    }

    /** Called by the ticker: flushes if the oldest buffered line has waited long enough. */
    void flushIfStale() {
        synchronized (this) {
            if (closed || buffer.isEmpty()
                    || (System.nanoTime() - firstBufferedAtNanos) / 1_000_000 < MAX_AGE_MS) {
                return;
            }
        }
        flush(false);
    }

    @Override
    public void close() {
        flush(true);
        onClosed.accept(this);
    }

    private void flush(boolean closing) {
        synchronized (writeLock) {
            List<String> batch;
            synchronized (this) {
                if (closed) {
                    return;
                }
                batch = buffer;
                buffer = new ArrayList<>();
                bufferedChars = 0;
                if (closing) {
                    closed = true;
                }
            }
            for (int i = 0; i < batch.size(); i += MAX_LINES) {
                writer.accept(new ArrayList<>(batch.subList(i, Math.min(batch.size(), i + MAX_LINES))));
            }
        }
    }
}
