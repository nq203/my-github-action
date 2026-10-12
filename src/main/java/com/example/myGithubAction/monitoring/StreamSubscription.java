package com.example.myGithubAction.monitoring;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.messaging.ExecutionEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One viewer of one execution. Tracks what the viewer already has (statuses, per-step line counts)
 * and turns hub events into SSE events without gaps or duplicates.
 *
 * Thread-safety: every method that touches state takes the single {@code lock}; the sink is called
 * under it to keep event order. The hub never holds a lock while calling in, so there is no
 * lock-order inversion. The snapshot loader (DB read) runs under the lock only for resyncs; the
 * initial load in {@link #start()} runs outside it so event delivery is only buffered meanwhile.
 */
@Slf4j
public class StreamSubscription {

    private final Long executionId;
    private final Long userId;
    private final StreamSink sink;
    private final Supplier<ExecutionSnapshot> snapshotLoader;

    private final Object lock = new Object();
    private final List<ExecutionEvent> buffer = new ArrayList<>();
    private boolean started;
    private boolean closed;
    private String executionStatus;
    private final Map<Long, String> stepStatus = new HashMap<>();
    private final Map<Long, Integer> lineCount = new HashMap<>();

    public StreamSubscription(Long executionId, Long userId, StreamSink sink,
                              Supplier<ExecutionSnapshot> snapshotLoader) {
        this.executionId = executionId;
        this.userId = userId;
        this.sink = sink;
        this.snapshotLoader = snapshotLoader;
    }

    public Long executionId() {
        return executionId;
    }

    public Long userId() {
        return userId;
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** Idempotent; stops all further output without completing the sink. */
    public void close() {
        synchronized (lock) {
            closed = true;
            buffer.clear();
        }
    }

    public void start() {
        ExecutionSnapshot snapshot;
        try {
            snapshot = snapshotLoader.get();
        } catch (RuntimeException ex) {
            close();
            throw ex;
        }
        synchronized (lock) {
            if (started || closed) {
                return;
            }
            started = true;
            adopt(snapshot);
            if (!emit("snapshot", snapshot)) {
                return;
            }
            if (isTerminal(executionStatus)) {
                buffer.clear();
                finish();
                return;
            }
            List<ExecutionEvent> pending = new ArrayList<>(buffer);
            buffer.clear();
            for (ExecutionEvent e : pending) {
                if (closed) {
                    break;
                }
                apply(e);
            }
        }
    }

    public void accept(ExecutionEvent event) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (!started) {
                buffer.add(event);
                return;
            }
            apply(event);
        }
    }

    /**
     * Sends a fresh snapshot (then {@code end} if terminal). Used when the sink had to drop
     * events for a slow reader. No-op before {@link #start()} (the start snapshot covers it) or
     * once closed.
     */
    public void resend() {
        synchronized (lock) {
            if (closed || !started) {
                return;
            }
            resync();
        }
    }

    /** Applies a fresh DB state: forward status changes, a resync if lines are missing, then end if terminal. */
    public void reconcile(ExecutionSnapshot db) {
        synchronized (lock) {
            if (closed || !started) {
                return;
            }
            if (hasMissingLines(db)) {
                // The polled db may be older than events already applied; never adopt it (that could
                // move statuses/lines backwards). A fresh load is never behind published events.
                resync();
            } else {
                for (StepView s : db.steps()) {
                    if (isAhead(stepStatus.get(s.id()), s.status())) {
                        stepStatus.put(s.id(), s.status());
                        if (!emit("step", stepData(s.id(), s.status(), s.startedAt(), s.endedAt(), s.duration(),
                                s.errorMessage()))) {
                            return;
                        }
                    }
                }
                ExecutionView ex = db.execution();
                if (isAhead(executionStatus, ex.status())) {
                    executionStatus = ex.status();
                    if (!emit("execution", executionData(ex.status(), ex.startedAt(), ex.endedAt(),
                            ex.errorMessage()))) {
                        return;
                    }
                }
            }
            if (isTerminal(executionStatus)) {
                finish();
            }
        }
    }

    // ---- everything below runs under the lock ----

    private void apply(ExecutionEvent e) {
        if (e.type() == null) {
            return;
        }
        switch (e.type()) {
            case LOG -> applyLog(e);
            case STEP_STATUS -> applyStep(e);
            case EXECUTION_STATUS -> applyExecution(e);
        }
    }

    private void applyLog(ExecutionEvent e) {
        Integer known = e.stepId() == null ? null : lineCount.get(e.stepId());
        if (known == null || e.fromSeq() == null || e.lines() == null) {
            resync();
            return;
        }
        int next = known + 1;
        int from = e.fromSeq();
        int count = e.lines().size();
        if (from > next) {
            resync();
            return;
        }
        int skip = next - from;
        if (skip >= count) {
            return;
        }
        List<String> tail = e.lines().subList(skip, count);
        lineCount.put(e.stepId(), known + tail.size());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepId", e.stepId());
        data.put("fromSeq", next);
        data.put("lines", List.copyOf(tail));
        emit("log", data);
    }

    private void applyStep(ExecutionEvent e) {
        if (e.stepId() == null || !stepStatus.containsKey(e.stepId())) {
            resync();
            return;
        }
        if (!isAhead(stepStatus.get(e.stepId()), e.status())) {
            return;
        }
        stepStatus.put(e.stepId(), e.status());
        emit("step", stepData(e.stepId(), e.status(), e.startedAt(), e.endedAt(), e.duration(), e.errorMessage()));
    }

    private void applyExecution(ExecutionEvent e) {
        if (!isAhead(executionStatus, e.status())) {
            return;
        }
        executionStatus = e.status();
        if (!emit("execution", executionData(e.status(), e.startedAt(), e.endedAt(), e.errorMessage()))) {
            return;
        }
        if (isTerminal(e.status())) {
            // DB is written before events are published, so it already holds every line of the run.
            ExecutionSnapshot db = load();
            if (db != null && hasMissingLines(db)) {
                adopt(db);
                if (!emit("snapshot", db)) {
                    return;
                }
            }
            finish();
        }
    }

    private void resync() {
        ExecutionSnapshot db = load();
        if (db == null) {
            return;
        }
        adopt(db);
        if (emit("snapshot", db) && isTerminal(executionStatus)) {
            finish();
        }
    }

    private ExecutionSnapshot load() {
        try {
            return snapshotLoader.get();
        } catch (RuntimeException ex) {
            log.warn("Snapshot reload failed for execution {}: {}", executionId, ex.getMessage());
            return null;
        }
    }

    private void adopt(ExecutionSnapshot s) {
        executionStatus = s.execution().status();
        stepStatus.clear();
        lineCount.clear();
        for (StepView step : s.steps()) {
            stepStatus.put(step.id(), step.status());
            lineCount.put(step.id(), step.lineCount());
        }
    }

    private boolean hasMissingLines(ExecutionSnapshot db) {
        for (StepView s : db.steps()) {
            if (s.lineCount() > lineCount.getOrDefault(s.id(), 0)) {
                return true;
            }
        }
        return false;
    }

    private void finish() {
        if (closed) {
            return;
        }
        if (emit("end", Map.of("status", String.valueOf(executionStatus)))) {
            closed = true;
            try {
                sink.complete();
            } catch (RuntimeException ex) {
                log.debug("complete failed for execution {}: {}", executionId, ex.getMessage());
            }
        }
    }

    /** @return false (and closes) when the client is gone */
    private boolean emit(String name, Object data) {
        try {
            sink.send(name, data);
            return true;
        } catch (RuntimeException ex) {
            closed = true;
            buffer.clear();
            log.debug("Sink for execution {} failed: {}", executionId, ex.getMessage());
            return false;
        }
    }

    private static Map<String, Object> executionData(String status, String startedAt, String endedAt, String error) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("status", status);
        d.put("startedAt", startedAt);
        d.put("endedAt", endedAt);
        d.put("errorMessage", error);
        return d;
    }

    private static Map<String, Object> stepData(Long stepId, String status, String startedAt, String endedAt,
                                                Long duration, String error) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("stepId", stepId);
        d.put("status", status);
        d.put("startedAt", startedAt);
        d.put("endedAt", endedAt);
        d.put("duration", duration);
        d.put("errorMessage", error);
        return d;
    }

    private static int rank(String status) {
        if (status == null) {
            return -1;
        }
        try {
            ExecutionState s = ExecutionState.valueOf(status);
            return s == ExecutionState.PENDING ? 0 : s == ExecutionState.RUNNING ? 1 : 2;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static boolean isAhead(String current, String incoming) {
        return rank(incoming) > rank(current);
    }

    private static boolean isTerminal(String status) {
        return rank(status) == 2;
    }
}
